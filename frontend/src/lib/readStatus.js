import { fetchStatus } from '../api';

/**
 * The one-shot status read (RootGate, AppLayout and the splash on mount, the read after a
 * run ends or the stream resyncs). `/api/status` fails in two different shapes and every
 * caller has to cover both: `fetchStatus` resolves to `null` on a non-ok response, but it
 * goes through `authedFetch` → raw `fetch`, which REJECTS when nothing is listening. A caller
 * that only checks for `null` never reaches that branch; the rejection kills whatever was
 * awaiting it.
 *
 * @returns {Promise<object|null>} the status, or `null` for "no status" —
 *          never a rejection.
 */
export async function readStatus() {
  try {
    return await fetchStatus();
  } catch {
    return null;
  }
}
