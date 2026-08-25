// Test-only stand-ins for two browser primitives jsdom does not implement
// (verified 2026-08-19: `new JSDOM('').window.BroadcastChannel` and
// `.navigator.locks` are both `undefined` in jsdom 30 / node 24). Production
// code in authSession.js feature-detects both and degrades without a
// polyfill — these fakes exist so tests can exercise the coordinated path
// deliberately, per the apply brief ("mock the primitives in jsdom").
//
// FakeBroadcastChannel is a real, if minimal, implementation of the pub/sub
// contract: instances sharing a `name` see each other's postMessage calls,
// asynchronously (queueMicrotask), and never their own — exactly like the
// real BroadcastChannel. Two independent authSession module instances
// (obtained via freshAuthSessionModule() below) connected through the same
// static registry is what "two tabs" means in these tests.

export class FakeBroadcastChannel {
  static registry = new Map();

  constructor(name) {
    this.name = name;
    this.onmessage = null;
    this._listeners = new Set();
    if (!FakeBroadcastChannel.registry.has(name)) {
      FakeBroadcastChannel.registry.set(name, new Set());
    }
    FakeBroadcastChannel.registry.get(name).add(this);
  }

  postMessage(data) {
    const peers = FakeBroadcastChannel.registry.get(this.name) || new Set();
    for (const peer of peers) {
      if (peer === this) continue; // BroadcastChannel never delivers to its own sender
      queueMicrotask(() => {
        const event = { data };
        peer.onmessage?.(event);
        peer._listeners.forEach(fn => fn(event));
      });
    }
  }

  addEventListener(type, fn) {
    if (type === 'message') this._listeners.add(fn);
  }

  removeEventListener(type, fn) {
    if (type === 'message') this._listeners.delete(fn);
  }

  close() {
    FakeBroadcastChannel.registry.get(this.name)?.delete(this);
  }

  static resetRegistry() {
    FakeBroadcastChannel.registry.clear();
  }
}

/**
 * FIFO mutex standing in for `navigator.locks`. Real Web Locks are scoped
 * per browser context (shared across same-origin tabs), so — unlike
 * FakeBroadcastChannel — this is installed ONCE on the shared `navigator`
 * global rather than per module instance, which is what makes it a genuine
 * cross-tab mutual-exclusion simulation and not two independent locks.
 */
export function makeFakeLocks() {
  let queue = Promise.resolve();
  return {
    request(_name, callback) {
      const run = () => callback();
      const result = queue.then(run, run);
      queue = result.then(() => {}, () => {});
      return result;
    },
  };
}

export function installFakeCoordinationPrimitives() {
  FakeBroadcastChannel.resetRegistry();
  globalThis.BroadcastChannel = FakeBroadcastChannel;
  if (!globalThis.navigator) globalThis.navigator = {};
  globalThis.navigator.locks = makeFakeLocks();
}

export function uninstallCoordinationPrimitives() {
  delete globalThis.BroadcastChannel;
  if (globalThis.navigator) delete globalThis.navigator.locks;
}
