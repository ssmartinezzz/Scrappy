import { useCallback, useEffect, useState } from 'react';

import { fetchUltimaCorridaFallida, startScrape } from '../api';

// An ADMIN-only, local offer: dismissing it never changes the historical run.
export function useFailedLastRun(enabled) {
  const [run, setRun] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!enabled) return undefined;
    let alive = true;
    void fetchUltimaCorridaFallida()
      .catch(() => null)
      .then(r => {
        if (!alive) return;
        setRun(r?.hayCorrida && r.fallidos?.length > 0 ? r : null);
      });
    return () => { alive = false; };
  }, [enabled]);

  const dismiss = useCallback(() => {
    setError('');
    setRun(null);
  }, []);

  const reintentar = useCallback(async () => {
    if (!run) return false;
    setBusy(true);
    setError('');
    try {
      const r = await startScrape({ sitios: run.fallidos.map(f => f.sitio) });
      if (!r?.iniciado) {
        setError(r?.mensaje || 'No se pudo iniciar el reintento.');
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
  }, [run]);

  return { run, busy, error, dismiss, reintentar };
}
