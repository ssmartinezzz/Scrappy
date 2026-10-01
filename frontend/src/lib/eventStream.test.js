import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { backoffDelay, connectEventStream, createSseParser } from './eventStream';
import { fakeStream, httpResponse, sseFrame } from '../test/fakeEventStream';

function parse(chunks) {
  const out = [];
  const parser = createSseParser(m => out.push(m));
  chunks.forEach(c => parser.push(c));
  return out;
}

describe('createSseParser', () => {
  it('dispatches a frame written without a space after the colon, the way the server writes it', () => {
    expect(parse(['event:scrape.status\ndata:{"status":"RUNNING"}\n\n']))
      .toEqual([{ event: 'scrape.status', data: '{"status":"RUNNING"}', id: '' }]);
  });

  it('accepts the optional single space after the colon', () => {
    expect(parse(['event: resync\ndata: {}\n\n'])).toEqual([{ event: 'resync', data: '{}', id: '' }]);
  });

  it('reassembles a frame cut at every possible byte boundary', () => {
    const wire = sseFrame('scrape.progress', { total: 3, completados: 1 });
    const out = parse([...wire]);
    expect(out).toHaveLength(1);
    expect(JSON.parse(out[0].data)).toEqual({ total: 3, completados: 1 });
    expect(out[0].event).toBe('scrape.progress');
  });

  it('reads CRLF line endings', () => {
    expect(parse(['event:snapshot\r\ndata:{"a":1}\r\n\r\n'])).toEqual([{ event: 'snapshot', data: '{"a":1}', id: '' }]);
  });

  it('does not read a CRLF split across two chunks as two line breaks', () => {
    // The LF opening the next chunk was read as a blank line, which closed the frame
    // before its data line arrived and dropped the event name.
    expect(parse(['event:a\r', '\ndata:{}\r', '\n\r', '\n'])).toEqual([{ event: 'a', data: '{}', id: '' }]);
  });

  it('reads bare CR line endings', () => {
    expect(parse(['event:a\rdata:{}\r\r'])).toEqual([{ event: 'a', data: '{}', id: '' }]);
  });

  it('ignores comment lines, heartbeats included, and dispatches nothing for them', () => {
    expect(parse([': ping\n\n', ':ping\n\n'])).toEqual([]);
  });

  it('ignores a comment in the middle of a frame', () => {
    expect(parse(['event:a\n: ping\ndata:{}\n\n'])).toEqual([{ event: 'a', data: '{}', id: '' }]);
  });

  it('joins consecutive data lines with a newline', () => {
    expect(parse(['event:a\ndata:{"x":\ndata:1}\n\n'])[0].data).toBe('{"x":\n1}');
  });

  it('dispatches several frames that arrive in one chunk, in order', () => {
    const out = parse([sseFrame('a', 1) + sseFrame('b', 2) + sseFrame('c', 3)]);
    expect(out.map(m => m.event)).toEqual(['a', 'b', 'c']);
  });

  it('dispatches nothing for a frame without data, and names a nameless frame "message"', () => {
    expect(parse(['event:a\n\n', 'data:{}\n\n'])).toEqual([{ event: 'message', data: '{}', id: '' }]);
  });

  it('carries the id field and strips a leading byte order mark', () => {
    expect(parse(['﻿id:7\nevent:a\ndata:{}\n\n'])).toEqual([{ event: 'a', data: '{}', id: '7' }]);
  });

  it('keeps a half-received frame pending instead of dispatching it', () => {
    expect(parse(['event:a\ndata:{}\n'])).toEqual([]);
  });
});

describe('backoffDelay', () => {
  it('draws from [0, ceiling) with a ceiling that doubles from 1 s', () => {
    const top = () => 0.999999;
    expect([1, 2, 3, 4].map(n => backoffDelay(n, top))).toEqual([999, 1999, 3999, 7999]);
  });

  it('caps the ceiling at 30 s', () => {
    expect(backoffDelay(20, () => 0.999999)).toBe(29999);
  });

  it('is full jitter: the draw can be zero and scales linearly with the random source', () => {
    expect(backoffDelay(3, () => 0)).toBe(0);
    expect(backoffDelay(3, () => 0.5)).toBe(2000);
  });
});

describe('connectEventStream', () => {
  let clock;
  let sleeps;
  let states;
  let events;
  let handle;

  beforeEach(() => {
    vi.useFakeTimers();
    clock = 0;
    sleeps = [];
    states = [];
    events = [];
    handle = null;
  });

  afterEach(() => {
    handle?.close();
    vi.useRealTimers();
  });

  const sleep = (ms, signal) => new Promise(resolve => {
    const entry = { ms, release: resolve };
    sleeps.push(entry);
    signal?.addEventListener('abort', resolve, { once: true });
  });

  function start(open, options = {}) {
    handle = connectEventStream({
      open,
      onEvent: (name, data) => events.push([name, data]),
      onState: s => states.push(s),
      sleep,
      random: () => 0.5,
      now: () => clock,
      ...options,
    });
    return handle;
  }

  const settle = () => vi.advanceTimersByTimeAsync(0);
  const phases = () => states.map(s => s.phase);

  it('opens the stream asking for text/event-stream, and reports connecting then live on the first frame', async () => {
    const s = fakeStream();
    const open = vi.fn().mockResolvedValue(s.response);

    start(open);
    await settle();
    expect(phases()).toEqual(['connecting']);

    s.frame('snapshot', { status: {}, ml: {} });
    await settle();

    expect(open).toHaveBeenCalledTimes(1);
    expect(open.mock.calls[0][0].headers).toEqual({ Accept: 'text/event-stream' });
    expect(open.mock.calls[0][0].signal).toBeInstanceOf(AbortSignal);
    expect(phases()).toEqual(['connecting', 'live']);
    expect(events).toEqual([['snapshot', { status: {}, ml: {} }]]);
  });

  it('delivers events in order with their JSON decoded', async () => {
    const s = fakeStream();
    start(vi.fn().mockResolvedValue(s.response));

    s.frame('snapshot', { n: 0 });
    s.frame('scrape.status', { status: 'RUNNING', mensaje: 'go' });
    s.push(': ping\n\n');
    s.frame('resync', {});
    await settle();

    expect(events.map(e => e[0])).toEqual(['snapshot', 'scrape.status', 'resync']);
    expect(events[1][1]).toEqual({ status: 'RUNNING', mensaje: 'go' });
  });

  it('skips a frame whose data is not JSON and keeps reading', async () => {
    const s = fakeStream();
    start(vi.fn().mockResolvedValue(s.response));

    s.push('event:a\ndata:{not json\n\n');
    s.frame('b', { ok: true });
    await settle();

    expect(events).toEqual([['b', { ok: true }]]);
  });

  it('reconnects at once, without a backoff or a reconnecting state, when the server closes a healthy stream', async () => {
    const first = fakeStream();
    const second = fakeStream();
    const open = vi.fn().mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response);
    start(open);

    first.frame('snapshot', { n: 1 });
    await settle();
    clock = 600000;
    first.end();
    await settle();
    second.frame('snapshot', { n: 2 });
    await settle();

    expect(open).toHaveBeenCalledTimes(2);
    expect(sleeps).toEqual([]);
    expect(phases()).not.toContain('reconnecting');
    expect(events.map(e => e[1].n)).toEqual([1, 2]);
  });

  it('backs off when a stream closes almost as soon as it opened, instead of spinning', async () => {
    const first = fakeStream();
    const open = vi.fn().mockResolvedValueOnce(first.response).mockResolvedValue(httpResponse(500));
    start(open);

    first.frame('snapshot', {});
    await settle();
    first.end();
    await settle();

    expect(sleeps).toHaveLength(1);
    expect(states.at(-1)).toMatchObject({ phase: 'reconnecting', attempt: 1 });
  });

  it('retries a network error after a jittered delay and reports the attempt', async () => {
    const open = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    start(open, { random: () => 0.5 });
    await settle();

    expect(states.at(-1)).toEqual({ phase: 'reconnecting', attempt: 1, retryInMs: 500 });
    expect(sleeps.map(s => s.ms)).toEqual([500]);

    sleeps[0].release();
    await settle();
    expect(open).toHaveBeenCalledTimes(2);
    expect(states.at(-1)).toEqual({ phase: 'reconnecting', attempt: 2, retryInMs: 1000 });
  });

  it('retries on a non-ok response that is neither 401 nor 403', async () => {
    const open = vi.fn().mockResolvedValue(httpResponse(503));
    start(open);
    await settle();

    expect(states.at(-1)).toMatchObject({ phase: 'reconnecting', attempt: 1 });
    sleeps[0].release();
    await settle();
    expect(open).toHaveBeenCalledTimes(2);
  });

  it('grows the ceiling up to 30 s while failures keep coming', async () => {
    const open = vi.fn().mockRejectedValue(new TypeError('down'));
    start(open, { random: () => 0.999999 });
    await settle();
    for (let i = 0; i < 8; i++) { sleeps.at(-1).release(); await settle(); }

    expect(sleeps.map(s => s.ms)).toEqual([999, 1999, 3999, 7999, 15999, 29999, 29999, 29999, 29999]);
  });

  it('resets the backoff after the first frame of a connection', async () => {
    const good = fakeStream();
    const open = vi.fn()
      .mockRejectedValueOnce(new TypeError('down'))
      .mockRejectedValueOnce(new TypeError('down'))
      .mockResolvedValueOnce(good.response)
      .mockRejectedValue(new TypeError('down'));
    start(open, { random: () => 0.999999 });
    await settle();
    sleeps[0].release(); await settle();
    sleeps[1].release(); await settle();
    expect(sleeps.map(s => s.ms)).toEqual([999, 1999]);

    good.frame('snapshot', {});
    await settle();
    good.fail();
    await settle();

    expect(sleeps.at(-1).ms).toBe(999);
  });

  it('stops for good on a 401 that survived the refresh, and reports why', async () => {
    const open = vi.fn().mockResolvedValue(httpResponse(401));
    start(open);
    await settle();
    await vi.advanceTimersByTimeAsync(120000);

    expect(open).toHaveBeenCalledTimes(1);
    expect(sleeps).toEqual([]);
    expect(states.at(-1)).toEqual({ phase: 'closed', reason: 'unauthenticated' });
  });

  it('stops for good on a 403 instead of looping: authenticating again would not help', async () => {
    const open = vi.fn().mockResolvedValue(httpResponse(403));
    start(open);
    await settle();
    await vi.advanceTimersByTimeAsync(120000);

    expect(open).toHaveBeenCalledTimes(1);
    expect(sleeps).toEqual([]);
    expect(states.at(-1)).toEqual({ phase: 'closed', reason: 'forbidden' });
  });

  it('aborts a connection that delivers no bytes for 45 s and reconnects', async () => {
    const dead = fakeStream();
    const open = vi.fn().mockResolvedValueOnce(dead.response).mockResolvedValue(httpResponse(500));
    start(open);
    dead.frame('snapshot', {});
    await settle();
    const signal = open.mock.calls[0][0].signal;

    await vi.advanceTimersByTimeAsync(44000);
    expect(signal.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1500);

    expect(signal.aborted).toBe(true);
    expect(states.at(-1)).toMatchObject({ phase: 'reconnecting' });
  });

  it('treats a heartbeat comment as life: the watchdog restarts on every chunk', async () => {
    const s = fakeStream();
    const open = vi.fn().mockResolvedValue(s.response);
    start(open);
    s.frame('snapshot', {});
    await settle();
    const signal = open.mock.calls[0][0].signal;

    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(40000);
      s.push(': ping\n\n');
      await settle();
    }

    expect(signal.aborted).toBe(false);
    expect(open).toHaveBeenCalledTimes(1);
  });

  it('aborts a connect that never answers, not only a stream that went quiet', async () => {
    const open = vi.fn(() => new Promise(() => {}));
    start(open);
    await settle();
    const signal = open.mock.calls[0][0].signal;

    await vi.advanceTimersByTimeAsync(45000);

    expect(signal.aborted).toBe(true);
  });

  it('close() aborts the open connection and never reconnects', async () => {
    const s = fakeStream();
    const open = vi.fn().mockResolvedValue(s.response);
    start(open);
    s.frame('snapshot', {});
    await settle();
    const signal = open.mock.calls[0][0].signal;
    const before = states.length;

    handle.close();
    await vi.advanceTimersByTimeAsync(120000);

    expect(signal.aborted).toBe(true);
    expect(open).toHaveBeenCalledTimes(1);
    expect(states).toHaveLength(before);
  });

  it('close() during a backoff wait ends the loop', async () => {
    const open = vi.fn().mockRejectedValue(new TypeError('down'));
    start(open);
    await settle();
    expect(sleeps).toHaveLength(1);

    handle.close();
    await settle();

    expect(open).toHaveBeenCalledTimes(1);
  });
});
