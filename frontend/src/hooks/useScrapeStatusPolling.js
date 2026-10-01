import { useCallback, useEffect, useRef, useState } from 'react';

import { readStatus } from '../lib/readStatus';
import { useScrapeStatus, useStreamState } from './EventStreamProvider';

const TERMINAL = new Set(['DONE', 'ERROR']);

/**
 * The splash's view of a scrape run. The server pushes every change on the
 * status stream; this hook adds what the stream cannot say: the one-shot read
 * that paints the screen before the first snapshot lands, and "I could not
 * reach the backend" as a state of its own.
 */
export function useScrapeStatusPolling() {
  const live = useScrapeStatus();
  const stream = useStreamState();

  const [view, setView] = useState({ status: 'IDLE', mensaje: '', progreso: null, totalProds: 0, tieneData: false });
  const [readFailed, setReadFailed] = useState(false);
  // A run already in flight when this mounts (after a resume, or a reload mid-run),
  // raised ONCE by the first status this hook learns of.
  const [runInFlightAtMount, setRunInFlightAtMount] = useState(false);

  const firstStatusSeen = useRef(false);
  const streamSeen = useRef(false);
  const pushes = useRef(0);
  const onDone = useRef(null);

  const apply = useCallback(st => {
    setView(prev => ({
      status: st.status || 'IDLE',
      mensaje: st.mensaje || '',
      progreso: st.progreso || null,
      tieneData: !!st.tieneData,
      // `st.total` is the CATALOGUE size; `st.progreso.total` is the number of sites in the
      // run. Reading the latter made the splash offer "Ver 29 productos disponibles".
      totalProds: st.tieneData ? (st.total || 0) : prev.totalProds,
    }));
    if (!firstStatusSeen.current) {
      firstStatusSeen.current = true;
      if (st.status === 'RUNNING') setRunInFlightAtMount(true);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void readStatus().then(st => {
      // A snapshot that beat this read is newer than it.
      if (!alive || streamSeen.current) return;
      if (!st) { setReadFailed(true); return; }
      setReadFailed(false);
      apply(st);
    });
    return () => { alive = false; };
  }, [apply]);

  useEffect(() => {
    if (!live) return;
    streamSeen.current = true;
    pushes.current++;
    setReadFailed(false);
    apply(live);
  }, [live, apply]);

  useEffect(() => {
    if (!TERMINAL.has(view.status) || !onDone.current) return;
    const done = onDone.current;
    onDone.current = null;
    done();
  }, [view.status]);

  /**
   * Calls `done` once, when the run reaches DONE or ERROR. `reconcile` also reads the status
   * once: right after POST /api/scrape the screen shows an optimistic RUNNING, and a rejected
   * POST pushes no event to correct it.
   */
  const watchRun = useCallback((done, { reconcile = false } = {}) => {
    onDone.current = done;
    if (!reconcile) return;
    const pushesBefore = pushes.current;
    void readStatus().then(st => {
      // Anything the server pushed while this read was in flight is newer than its answer.
      if (pushes.current !== pushesBefore) return;
      if (!st) { setReadFailed(true); return; }
      setReadFailed(false);
      apply(st);
    });
  }, [apply]);

  const markRunning = useCallback(() => {
    setView(prev => ({ ...prev, status: 'RUNNING' }));
    setReadFailed(false);
  }, []);

  return {
    ...view,
    backendUnreachable: readFailed || stream.phase === 'reconnecting',
    runInFlightAtMount,
    watchRun,
    markRunning,
  };
}
