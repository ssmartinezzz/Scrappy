const encoder = new TextEncoder();

export function sseFrame(name, data) {
  return `event:${name}\ndata:${JSON.stringify(data)}\n\n`;
}

/** A controllable `/api/events` Response: push frames in, end it or break it from the test. */
export function fakeStream() {
  let controller;
  const body = new ReadableStream({ start(c) { controller = c; } });
  const stream = {
    response: { ok: true, status: 200, body },
    push: text => controller.enqueue(encoder.encode(text)),
    frame: (name, data) => stream.push(sseFrame(name, data)),
    end: () => controller.close(),
    fail: () => controller.error(new TypeError('network error')),
  };
  return stream;
}

export function httpResponse(status) {
  return { ok: status >= 200 && status < 300, status, body: null };
}
