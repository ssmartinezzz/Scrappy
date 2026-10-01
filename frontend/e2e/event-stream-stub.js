// A controllable stand-in for GET /api/events, for specs about what the UI DOES with pushed
// events rather than about the wire.
//
// It wraps `window.fetch` instead of using `route.fulfill`: fulfill sends one finished body, so
// the stream "ends" at once and the client reconnects after its backoff, which makes every
// assertion race the reconnect. Here the stream stays open until the spec pushes, drops or
// refuses it — the same shape as the real endpoint. Every other request goes to the real network.
//
// The wire format, the parser and the reconnect loop are the app's own code: only the server is
// replaced. The real endpoint is exercised end to end by event-stream.spec.js.

export const SNAPSHOT_IDLE = {
  status: { status: 'IDLE', mensaje: '', progreso: null, tieneData: true, total: 10 },
  ml: { hasTextModel: false, hasImageModel: false, training: { running: false, phase: 'idle', pct: 0, msg: '', startedAt: '' } },
};

export async function instalarElStreamFalso(context) {
  await context.addInitScript(initial => {
    const realFetch = window.fetch.bind(window);
    const encoder = new TextEncoder();
    let controller = null;
    let refusing = false;
    let opened = 0;
    const frame = (name, data) => encoder.encode(`event:${name}\ndata:${JSON.stringify(data)}\n\n`);

    window.__sse = {
      snapshot: initial,
      opened: () => opened,
      push: (name, data) => controller?.enqueue(frame(name, data)),
      // What a killed backend does to an open connection, then to every retry.
      cortar: () => {
        refusing = true;
        try { controller?.error(new TypeError('network error')); } catch { /* already closed */ }
        controller = null;
      },
      restaurar: () => { refusing = false; },
    };

    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (!url.includes('/api/events')) return realFetch(input, init);
      opened++;
      if (refusing) return Promise.reject(new TypeError('Failed to fetch'));
      const body = new ReadableStream({
        start(c) {
          controller = c;
          c.enqueue(frame('snapshot', window.__sse.snapshot));
          init?.signal?.addEventListener('abort', () => { try { c.close(); } catch { /* closed */ } });
        },
      });
      return Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    };
  }, SNAPSHOT_IDLE);
}

export const empujar = (page, nombre, datos) => page.evaluate(([n, d]) => window.__sse.push(n, d), [nombre, datos]);
export const cortarElStream = page => page.evaluate(() => window.__sse.cortar());
export const restaurarElStream = page => page.evaluate(() => window.__sse.restaurar());
export const fijarElSnapshot = (page, snapshot) => page.evaluate(s => { window.__sse.snapshot = s; }, snapshot);
export const conexionesAbiertas = page => page.evaluate(() => window.__sse.opened());
