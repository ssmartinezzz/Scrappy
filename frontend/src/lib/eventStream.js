const RETRY_BASE_MS = 1000;
const RETRY_CAP_MS = 30000;
const WATCHDOG_MS = 45000;
const MIN_HEALTHY_MS = 5000;

/**
 * Incremental Server-Sent Events parser. `EventSource` cannot send an
 * Authorization header, so the stream is read by hand and has to honour the
 * wire format itself: a CR or LF or CRLF ends a line (the pair may be split
 * across two chunks), `:` opens a comment, consecutive `data:` lines join with
 * `\n`, and a blank line dispatches the event.
 */
export function createSseParser(onMessage) {
  let buffer = '';
  let event = '';
  let id = '';
  let data = [];
  let first = true;
  let skipLf = false;

  function line(text) {
    if (text === '') {
      if (data.length) onMessage({ event: event || 'message', data: data.join('\n'), id });
      event = '';
      data = [];
      return;
    }
    if (text[0] === ':') return;
    const colon = text.indexOf(':');
    const field = colon === -1 ? text : text.slice(0, colon);
    let value = colon === -1 ? '' : text.slice(colon + 1);
    if (value[0] === ' ') value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
    else if (field === 'id') id = value;
  }

  return {
    push(chunk) {
      buffer += chunk;
      if (first && buffer.length) {
        first = false;
        if (buffer[0] === '\uFEFF') buffer = buffer.slice(1);
      }
      let start = 0;
      for (let i = 0; i < buffer.length; i++) {
        const c = buffer[i];
        if (skipLf) {
          skipLf = false;
          if (c === '\n') { start = i + 1; continue; }
        }
        if (c !== '\n' && c !== '\r') continue;
        line(buffer.slice(start, i));
        start = i + 1;
        // A CR may be the first half of a CRLF whose LF arrives in the next chunk.
        if (c === '\r') skipLf = true;
      }
      buffer = buffer.slice(start);
    },
  };
}

/** Full jitter: a random point in [0, min(cap, base * 2^(failures-1))). */
export function backoffDelay(failures, random = Math.random, base = RETRY_BASE_MS, cap = RETRY_CAP_MS) {
  const ceiling = Math.min(cap, base * 2 ** Math.max(0, failures - 1));
  return Math.floor(random() * ceiling);
}

function sleepUnlessAborted(ms, signal) {
  return new Promise(resolve => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}

/**
 * Keeps ONE status stream open until `close()`: reads `open()`'s body, decodes
 * each event's JSON and reconnects on its own.
 *
 * - `open({ signal, headers })` must return a fetch Response; in the app it is
 *   authedFetch, so an expired token is refreshed before the stream starts.
 * - A 401 (after that single refresh) or a 403 ends it for good and is reported
 *   as `closed`: retrying cannot fix either, and the auth layer owns the logout.
 * - Anything else retries with full-jitter backoff, reset by the first frame of
 *   a connection. The server closing a healthy stream (it does, every 10
 *   minutes) reconnects at once and is not reported as a failure.
 * - A connection that delivers no bytes for `watchdogMs` is aborted: the
 *   server pings every 15 s, so silence means a dead link that TCP has not
 *   noticed yet.
 *
 * `onState` receives `{ phase: 'connecting' | 'live' | 'reconnecting' | 'closed' }`
 * plus `attempt` and `retryInMs` while reconnecting and `reason` once closed.
 */
export function connectEventStream({
  open,
  onEvent,
  onState = () => {},
  sleep = sleepUnlessAborted,
  random = Math.random,
  now = Date.now,
  baseDelayMs = RETRY_BASE_MS,
  maxDelayMs = RETRY_CAP_MS,
  watchdogMs = WATCHDOG_MS,
  minHealthyMs = MIN_HEALTHY_MS,
}) {
  const lifetime = new AbortController();
  let failures = 0;

  async function attempt() {
    const connection = new AbortController();
    const abortConnection = () => connection.abort();
    lifetime.signal.addEventListener('abort', abortConnection, { once: true });

    const startedAt = now();
    let stalled = false;
    let frames = 0;
    let watchdog;
    const arm = () => {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => { stalled = true; connection.abort(); }, watchdogMs);
    };

    try {
      arm();
      const response = await open({ signal: connection.signal, headers: { Accept: 'text/event-stream' } });
      if (response.status === 401) return { kind: 'fatal', reason: 'unauthenticated' };
      if (response.status === 403) return { kind: 'fatal', reason: 'forbidden' };
      if (!response.ok || !response.body) return { kind: 'failed' };

      const reader = response.body.getReader();
      connection.signal.addEventListener('abort', () => { reader.cancel().catch(() => {}); }, { once: true });
      const decoder = new TextDecoder();
      const parser = createSseParser(message => {
        frames++;
        if (frames === 1) {
          failures = 0;
          onState({ phase: 'live' });
        }
        let data;
        try { data = JSON.parse(message.data); } catch { return; }
        onEvent(message.event, data);
      });

      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        arm();
        parser.push(decoder.decode(value, { stream: true }));
      }
      const healthy = frames > 0 && !stalled && now() - startedAt >= minHealthyMs;
      return { kind: healthy ? 'rotated' : 'failed' };
    } catch {
      return { kind: 'failed' };
    } finally {
      clearTimeout(watchdog);
      lifetime.signal.removeEventListener('abort', abortConnection);
    }
  }

  (async () => {
    onState({ phase: 'connecting' });
    while (!lifetime.signal.aborted) {
      const outcome = await attempt();
      if (lifetime.signal.aborted) return;
      if (outcome.kind === 'fatal') {
        onState({ phase: 'closed', reason: outcome.reason });
        return;
      }
      if (outcome.kind === 'rotated') continue;
      failures++;
      const retryInMs = backoffDelay(failures, random, baseDelayMs, maxDelayMs);
      onState({ phase: 'reconnecting', attempt: failures, retryInMs });
      await sleep(retryInMs, lifetime.signal);
    }
  })();

  return { close: () => lifetime.abort() };
}
