// scrape-run-persistence-and-resume, slice 6. The ADMIN-facing half of the
// interrupted-run offer: read what a dead process left open, and either take
// it or put it away.
//
// `enabled` is the role gate, and it gates the REQUEST, not the render. Both
// routes are ADMIN in ApiRoutePolicy.TABLE, so asking as a VIEWER buys a 403
// for a question the spec says must never be posed ("VIEWER is not notified:
// no notification and no affordance about the interrupted run").
import { useCallback, useEffect, useState } from 'react';

import { fetchInterrumpida, retomarScrape } from '../api';

export function useInterruptedRun(enabled) {
  const [run, setRun]     = useState(null);
  const [busy, setBusy]   = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!enabled) return undefined;
    let alive = true;
    // Two failure shapes, one meaning. `fetchInterrumpida` resolves null on a
    // non-ok response, but `authedFetch` calls bare fetch, which REJECTS when
    // nothing is listening — an uncaught one here would take down the page
    // this notice merely sits on top of.
    fetchInterrumpida()
      .catch(() => null)
      .then(r => {
        if (!alive) return;
        setRun(r?.hayInterrumpida ? r : null);
      });
    return () => { alive = false; };
  }, [enabled]);

  // Client-side only, and deliberately so: there is no discard endpoint, and
  // `interrumpida` is cleared by resuming and nothing else. This hides the
  // banner for the session; a reload brings it back, because the run really
  // is still interrupted. Pretending otherwise would be a lie about state we
  // do not own.
  const dismiss = useCallback(() => { setRun(null); setError(''); }, []);

  const retomar = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const r = await retomarScrape();
      // 200 with `retomando:false` means another scrape got in first. Reading
      // only "the call succeeded" would clear the banner and send the user to
      // a progress screen for a run nobody started.
      if (!r?.retomando) {
        setError(r?.mensaje || 'No se pudo retomar la corrida.');
        return false;
      }
      setRun(null);
      return true;
    } catch {
      setError('No se pudo contactar al servidor.');
      return false;
    } finally {
      setBusy(false);
    }
  }, []);

  return { run, busy, error, dismiss, retomar };
}
