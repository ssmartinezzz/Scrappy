/**
 * Records `unhandledRejection` events for the lifetime of a test. Vitest also reports them as
 * run-level errors, but that does not fail the individual test; this makes the assertion local.
 */
export function trackUnhandledRejections() {
  const seen = [];
  const onRejection = reason => { seen.push(reason); };
  process.on('unhandledRejection', onRejection);
  return {
    seen,
    /** Lets pending rejections surface (they fire after the microtask queue drains). */
    async settle() { await new Promise(resolve => setTimeout(resolve, 20)); },
    stop() { process.off('unhandledRejection', onRejection); },
  };
}
