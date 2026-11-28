import { useCallback, useEffect, useState } from 'react';

import { fetchUltimaCorridaFallida, startScrape } from '../api';
import { clasificarFallidos } from '../lib/blockedSites';

const CLAVE_OCULTA = 'failedLastRun.dismissedUuid';

// Storage can throw (private mode, blocked site data): the banner then just shows again.
function uuidOculto() {
  try { return localStorage.getItem(CLAVE_OCULTA); } catch { return null; }
}

function recordarOculto(uuid) {
  try { localStorage.setItem(CLAVE_OCULTA, uuid); } catch { /* not persisted */ }
}

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
        const hay = r?.hayCorrida && r.fallidos?.length > 0;
        setRun(hay && !(r.uuid && r.uuid === uuidOculto()) ? r : null);
      });
    return () => { alive = false; };
  }, [enabled]);

  const dismiss = useCallback(() => {
    setError('');
    if (run?.uuid) recordarOculto(run.uuid);
    setRun(null);
  }, [run]);

  const reintentar = useCallback(async () => {
    if (!run) return false;
    const { reintentables } = clasificarFallidos(run.fallidos);
    if (reintentables.length === 0) return false;
    setBusy(true);
    setError('');
    try {
      const r = await startScrape({ sitios: reintentables.map(f => f.sitio) });
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
