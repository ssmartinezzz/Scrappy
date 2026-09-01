import { fetchStatus } from '../api';

/**
 * The ONE status read in the app. `/api/status` fails in two different shapes
 * and every caller has to cover both: `fetchStatus` resolves to `null` on a
 * non-ok response, but it goes through `authedFetch` → raw `fetch`, which
 * REJECTS when nothing is listening. A caller that only checks for `null` never
 * reaches that branch at all — the rejection kills whatever was awaiting it.
 *
 * Inside an interval callback that is the expensive one: the tick dies with an
 * unhandled rejection, the interval keeps firing and dying every 1800ms, and
 * the last good RUNNING stays frozen on screen for as long as the tab is open.
 *
 * It lives here rather than inside one hook because there are two pollers and
 * three mount reads, and the first copy of this fix only reached one of them.
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
