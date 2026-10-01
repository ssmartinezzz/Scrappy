import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, useState } from 'react';

import { fetchMlEstado, openEventStream } from '../api';
import { useAuth } from '../auth/AuthProvider';
import { connectEventStream } from '../lib/eventStream';
import { readStatus } from '../lib/readStatus';

const TERMINAL = new Set(['DONE', 'ERROR']);
const STATE_EVENTS = new Set(['snapshot', 'scrape.status', 'scrape.progress', 'ml.status']);
const INITIAL = { scrape: null, estado: null, backfill: null };

function trainingOf(d) {
  return { running: d.running, phase: d.phase, pct: d.pct, msg: d.msg, startedAt: d.startedAt };
}

/** Folds one server event into the mirror of `/api/status` and `/api/ml/estado`. */
export function reduceStatusEvent(state, action) {
  const { data } = action;
  switch (action.type) {
    case 'reset':
      return INITIAL;
    case 'snapshot':
      return { scrape: data?.status ?? null, estado: data?.ml ?? null, backfill: null };
    case 'scrape.status': {
      const prev = state.scrape || {};
      // The previous run's finished progress bar must not flash under a new run.
      const restarted = data.status === 'RUNNING' && prev.status !== 'RUNNING';
      return {
        ...state,
        scrape: { ...prev, status: data.status, mensaje: data.mensaje, ...(restarted && { progreso: null }) },
      };
    }
    case 'scrape.progress':
      return { ...state, scrape: { ...(state.scrape || {}), progreso: data } };
    case 'ml.status':
      return data.kind === 'backfill'
        ? { ...state, backfill: trainingOf(data) }
        : { ...state, estado: { ...(state.estado || {}), training: trainingOf(data) } };
    case 'replace-scrape':
      return { ...state, scrape: action.st };
    case 'replace-estado':
      return { ...state, estado: action.e };
    case 'scrape-extras':
      // Status events carry no tieneData/total/mlRefinadas: a one-off read after the
      // run ends fills them in, unless a newer run has already started.
      return state.scrape && TERMINAL.has(state.scrape.status)
        ? { ...state, scrape: { ...action.st, status: state.scrape.status, mensaje: state.scrape.mensaje, progreso: state.scrape.progreso } }
        : state;
    case 'estado-extras':
      return { ...state, estado: { ...action.e, training: state.estado?.training ?? action.e.training } };
    default:
      return state;
  }
}

const NO_STREAM = { phase: 'idle' };
const NO_BUS = { subscribe: () => () => {} };

const ScrapeContext = createContext(null);
const MlContext = createContext({ estado: null, backfill: null });
const StreamStateContext = createContext(NO_STREAM);
const BusContext = createContext(NO_BUS);

/**
 * The app's ONE status connection. Mounted once, under the auth provider: it
 * connects when a session exists and drops when it ends, so a logout never
 * leaves a stream open on a dead token.
 */
export function EventStreamProvider({ children, open = openEventStream, connectOptions }) {
  const { authenticated } = useAuth();
  const [state, dispatch] = useReducer(reduceStatusEvent, INITIAL);
  const [stream, setStream] = useState(NO_STREAM);
  const listeners = useRef(new Map());
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const bus = useMemo(() => ({
    subscribe(name, fn) {
      const set = listeners.current.get(name) || new Set();
      set.add(fn);
      listeners.current.set(name, set);
      return () => set.delete(fn);
    },
  }), []);

  const emit = useCallback((name, data) => {
    listeners.current.get(name)?.forEach(fn => fn(data));
  }, []);

  const onEvent = useCallback((name, data) => {
    if (!alive.current) return;
    if (STATE_EVENTS.has(name)) dispatch({ type: name, data });
    emit(name, data);

    if (name === 'snapshot' && data?.ml?.training) emit('training', data.ml.training);
    if (name === 'scrape.status' && TERMINAL.has(data.status)) {
      // `readStatus` never rejects.
      void readStatus().then(st => { if (alive.current && st) dispatch({ type: 'scrape-extras', st }); });
    }
    if (name === 'ml.status' && data.kind !== 'backfill') {
      emit('training', trainingOf(data));
      if (!data.running) {
        void fetchMlEstado().catch(() => null)
          .then(e => { if (alive.current && e) dispatch({ type: 'estado-extras', e }); });
      }
    }
    if (name === 'resync') {
      void Promise.all([readStatus(), fetchMlEstado().catch(() => null)]).then(([st, e]) => {
        if (!alive.current) return;
        if (st) dispatch({ type: 'replace-scrape', st });
        if (e) {
          dispatch({ type: 'replace-estado', e });
          if (e.training) emit('training', e.training);
        }
      });
    }
  }, [emit]);

  useEffect(() => {
    if (!authenticated) {
      dispatch({ type: 'reset' });
      setStream(NO_STREAM);
      return undefined;
    }
    const handle = connectEventStream({ open, onEvent, onState: setStream, ...connectOptions });
    return () => handle.close();
  }, [authenticated, open, onEvent, connectOptions]);

  const ml = useMemo(() => ({ estado: state.estado, backfill: state.backfill }), [state.estado, state.backfill]);

  return (
    <BusContext.Provider value={bus}>
      <StreamStateContext.Provider value={stream}>
        <ScrapeContext.Provider value={state.scrape}>
          <MlContext.Provider value={ml}>{children}</MlContext.Provider>
        </ScrapeContext.Provider>
      </StreamStateContext.Provider>
    </BusContext.Provider>
  );
}

/** The scrape status as last pushed (`/api/status` shape), or null before the first snapshot. */
export function useScrapeStatus() {
  return useContext(ScrapeContext);
}

/**
 * `estado`: `/api/ml/estado` shape. `training`: its live training block. `resultado`: what
 * `/api/ml/resultado` would answer, derived the way the backend does. `backfill`: embeddings job.
 */
export function useMlStatus() {
  const { estado, backfill } = useContext(MlContext);
  const training = estado?.training ?? null;
  return useMemo(() => ({
    estado,
    training,
    backfill,
    resultado: training
      ? { running: training.running, phase: training.phase, pct: training.pct, msg: training.msg,
          done: !training.running && training.phase !== 'idle' }
      : null,
  }), [estado, training, backfill]);
}

/** `{ phase: 'idle' | 'connecting' | 'live' | 'reconnecting' | 'closed', ... }`; `idle` outside a provider. */
export function useStreamState() {
  return useContext(StreamStateContext);
}

/**
 * Runs `handler(data)` for a server event: `resync`, `db.changed`, `ml.status`, ... plus
 * `training`, which fires with the training block on ml.status, snapshots and resync reads.
 */
export function useStreamEvent(name, handler) {
  const { subscribe } = useContext(BusContext);
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => subscribe(name, data => latest.current(data)), [subscribe, name]);
}
