// scrape-run-persistence-and-resume, slice 0. Owns the whole status state
// machine of a scrape run: the mount read, the polling interval, and the
// "I could not reach the backend" state.
//
// It lives here, not inline in App.jsx, for two reasons. First, the two bugs
// it fixes are both interval-lifecycle bugs, and this is the only shape where
// the fix is testable directly instead of through three layers of splash UI.
// Second, slice 6 wires a resume action onto this same poller.
import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchStatus } from '../api';

export const POLL_INTERVAL_MS = 1800;

const TERMINAL = new Set(['DONE', 'ERROR']);

/**
 * A status read that never rejects. `fetchStatus` resolves to null on a non-ok
 * response, but `authedFetch` calls raw `fetch`, which REJECTS when nothing is
 * listening — so a dead backend never reached the `!st` branch at all, it just
 * killed the interval callback with an unhandled rejection. Both shapes mean
 * the same thing here: no status.
 */
async function readStatus() {
  try {
    return await fetchStatus();
  } catch {
    return null;
  }
}

export function useScrapeStatusPolling() {
  const [status, setStatus]     = useState('IDLE');
  const [mensaje, setMensaje]   = useState('');
  const [progreso, setProgreso] = useState(null);
  const [totalProds, setTotalProds] = useState(0);
  const [backendUnreachable, setBackendUnreachable] = useState(false);
  // A run already in flight when this mounts — after a resume, or a reload
  // mid-run. Raised ONCE by the mount read and never again: the interval
  // drives RUNNING on its own afterwards, and a flag that tracked the live
  // status would re-arm the interval on every render that saw one.
  const [pollingNeeded, setPollingNeeded] = useState(false);

  // ONE ref for the whole mount. The previous code rebuilt `{current:null}` on
  // every render, and every poll re-renders, so the next `startPolling` read a
  // fresh null, failed to clear the interval it meant to replace, and left two
  // of them running against the same state.
  const pollingRef = useRef(null);

  const stopPolling = useCallback(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  // Without this the interval outlives the component, polling a backend for a
  // screen nobody is looking at and setting state on something unmounted.
  useEffect(() => stopPolling, [stopPolling]);

  useEffect(() => {
    let alive = true;
    readStatus().then(st => {
      if (!alive) return;
      if (!st) { setBackendUnreachable(true); return; }
      setBackendUnreachable(false);
      setStatus(st.status || 'IDLE');
      setMensaje(st.mensaje || '');
      setProgreso(st.progreso || null);
      if (st.status === 'RUNNING') setPollingNeeded(true);
    });
    return () => { alive = false; };
  }, []);

  const startPolling = useCallback((onDone) => {
    stopPolling();
    pollingRef.current = setInterval(async () => {
      const st = await readStatus();

      // A failed read is a state of its own, never a silent `return`. Swallowing
      // it left the last RUNNING frozen on screen for as long as the tab stayed
      // open — a screen that keeps claiming progress that stopped hours ago.
      // The interval deliberately keeps running: the flag clears by itself as
      // soon as the backend answers again.
      if (!st) { setBackendUnreachable(true); return; }

      setBackendUnreachable(false);
      setStatus(st.status); setMensaje(st.mensaje); setProgreso(st.progreso);
      if (st.status === 'RUNNING' && st.tieneData) setTotalProds(st.progreso?.total || 0);
      if (TERMINAL.has(st.status)) {
        stopPolling();
        onDone?.();
      }
    }, POLL_INTERVAL_MS);
  }, [stopPolling]);

  const markRunning = useCallback(() => {
    setStatus('RUNNING');
    setBackendUnreachable(false);
  }, []);

  return {
    status, mensaje, progreso, totalProds, backendUnreachable, pollingNeeded,
    startPolling, stopPolling, markRunning,
  };
}
