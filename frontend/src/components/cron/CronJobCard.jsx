// Create/edit detail card for one cron job, in the animated "coach scheduling
// card" language the user supplied: a framer-motion card (staggered entrance,
// spring physics, reduced-motion aware) rendered in a modal overlay, with a
// slide-up second view — here repurposed as the per-execution log viewer
// (the coach card's booking-confirmation slide-up).
//
// Ported TSX -> JSX + project tokens (card->s2, muted->s3, foreground->t1,
// muted-foreground->t4, emerald->success, primary/10 -> color-mix). Sitios
// chips reuse SplashPanel's rubro grouping; an EMPTY sitios array means "todos
// los sitios" server-side (CronJob), so selecting every site persists as [],
// and Guardar is disabled at 0 selected.
import { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import {
  X, ChevronLeft, CalendarClock, Server,
  CheckCircle2, XCircle, Loader2, SkipForward, Cpu, Zap, RefreshCw,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { fetchSitios, createCronJob, updateCronJob, fetchCronExecutions } from '../../api';
import { SEMANTIC } from '../../lib/colors';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Checkbox } from '../ui/checkbox';

const RUBRO_META = {
  indumentaria: { color: 'var(--p)',        label: 'Indumentaria' },
  tecnologia:   { color: 'var(--p2)',       label: 'Tecnología' },
  suplementos:  { color: SEMANTIC.positive, label: 'Nutrición' },
};

const STATUS_META = {
  running: { Icon: Loader2,      tone: 'text-warning', label: 'En curso', spin: true },
  success: { Icon: CheckCircle2, tone: 'text-success', label: 'OK' },
  error:   { Icon: XCircle,      tone: 'text-danger',  label: 'Error' },
  skipped: { Icon: SkipForward,  tone: 'text-t4',      label: 'Saltado' },
};

const EMPTY_FORM = {
  name: '', precioMin: 0, precioMax: 300000, sitios: [],
  forceRetrain: false, useGpu: true, cronExpr: '0 0 3 * * *', enabled: true,
};

export default function CronJobCard({ job, onClose, onSaved }) {
  const isNew = !job?.id;
  const shouldReduceMotion = useReducedMotion();
  const anim = !shouldReduceMotion;

  const [form, setForm]             = useState({ ...EMPTY_FORM, ...job });
  const [allSitios, setAllSitios]   = useState([]);
  const [selected, setSelected]     = useState([]);
  const [executions, setExecutions] = useState([]);
  const [saving, setSaving]         = useState(false);
  const [error, setError]           = useState('');
  const [detailExec, setDetailExec] = useState(null); // slide-up log view
  const scrollRef = useRef(null);

  // Close on Escape.
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') (detailExec ? setDetailExec(null) : onClose?.()); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [detailExec, onClose]);

  useEffect(() => {
    fetchSitios().then(data => {
      if (!data) return;
      const all = [...(data.base || []), ...(data.extras || [])];
      setAllSitios(all);
      // job.sitios empty/[] === "todos" server-side -> reflect as all selected.
      setSelected((job?.sitios && job.sitios.length > 0) ? job.sitios : all.map(s => s.nombre));
    });
  }, [job]);

  useEffect(() => {
    if (isNew) { setExecutions([]); return; }
    fetchCronExecutions(job.id).then(data => setExecutions(data?.executions || []));
  }, [job, isNew]);

  const byRubro = allSitios.reduce((acc, s) => {
    const r = s.rubro || 'indumentaria';
    (acc[r] = acc[r] || []).push(s);
    return acc;
  }, {});

  async function handleSave() {
    if (!form.name.trim() || !form.cronExpr.trim() || selected.length === 0) return;
    setSaving(true);
    setError('');
    const payload = {
      name: form.name.trim(),
      precioMin: Number(form.precioMin) || 0,
      precioMax: Number(form.precioMax) || 0,
      // All known sites selected persists as [] ("todos" server-side), so it
      // keeps meaning "todos" even if a new site is onboarded later.
      sitios: selected.length === allSitios.length ? [] : selected,
      forceRetrain: !!form.forceRetrain,
      useGpu: !!form.useGpu,
      cronExpr: form.cronExpr.trim(),
      enabled: !!form.enabled,
    };
    const res = isNew ? await createCronJob(payload) : await updateCronJob(job.id, payload);
    setSaving(false);
    if (!res || res.ok === false) { setError(res?.mensaje || 'No se pudo guardar el job.'); return; }
    onSaved?.();
  }

  // ── framer-motion variants (mirrors the coach card) ────────────────────────
  const container = {
    hidden: { opacity: 0 },
    visible: { opacity: 1, transition: { staggerChildren: 0.06, delayChildren: 0.05 } },
  };
  const item = {
    hidden: { opacity: 0, x: -20, scale: 0.98, filter: 'blur(4px)' },
    visible: { opacity: 1, x: 0, scale: 1, filter: 'blur(0px)', transition: { type: 'spring', stiffness: 400, damping: 28, mass: 0.6 } },
  };
  const chip = {
    hidden: { opacity: 0, scale: 0.85 },
    visible: { opacity: 1, scale: 1, transition: { type: 'spring', stiffness: 400, damping: 25 } },
  };
  const allSelected = allSitios.length > 0 && selected.length === allSitios.length;

  return (
    <motion.div
      className="fixed inset-0 z-[350] flex sm:items-center sm:justify-center sm:p-4"
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
    >
      {/* Scrim */}
      <div className="absolute inset-0 bg-black/60" onClick={() => onClose?.()} aria-hidden="true" />

      <motion.div
        role="dialog" aria-modal="true" aria-label={isNew ? 'Nuevo cron job' : `Editar ${job.name}`}
        variants={anim ? container : undefined}
        initial={anim ? 'hidden' : false}
        animate="visible"
        className="relative z-10 flex h-full w-full flex-col overflow-hidden bg-s2 shadow-lg sm:h-auto sm:max-h-[90dvh] sm:max-w-2xl sm:rounded-card sm:border sm:border-border"
      >
        {/* ── Main view ─────────────────────────────────────────────────────── */}
        <motion.div
          initial={false}
          animate={{ opacity: detailExec ? 0.3 : 1, scale: detailExec ? 0.97 : 1 }}
          transition={{ type: 'spring', stiffness: 300, damping: 30, mass: 0.8 }}
          ref={scrollRef}
          className="min-h-0 flex-1 overflow-y-auto"
        >
          {/* Header */}
          <motion.div variants={anim ? item : undefined} className="sticky top-0 z-10 flex items-start justify-between gap-3 border-b border-border bg-s2 p-3 sm:p-4">
            <div className="flex min-w-0 items-center gap-2">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-btn" style={{ backgroundColor: 'color-mix(in srgb, var(--p) 14%, transparent)' }}>
                <CalendarClock aria-hidden="true" className="h-5 w-5 text-primary" strokeWidth={2} />
              </span>
              <div className="min-w-0">
                <h2 className="truncate text-base font-bold text-t1 sm:text-lg">{isNew ? 'Nuevo cron job' : job.name}</h2>
                <p className="text-xs text-t4">Scraping programado</p>
              </div>
            </div>
            <div className="flex shrink-0 items-start gap-2">
              <div className="flex flex-col items-end">
                <span className="text-[.6rem] font-bold uppercase tracking-wide text-t4">Próxima</span>
                <span className="tabular-nums text-xs font-semibold text-success sm:text-sm">
                  {isNew ? 'Al guardar' : (job.nextRunAt ? job.nextRunAt.replace('T', ' ') : '—')}
                </span>
              </div>
              <button type="button" onClick={() => onClose?.()} aria-label="Cerrar"
                className="-mr-1 flex h-8 w-8 items-center justify-center rounded-btn text-t4 transition-colors hover:bg-s3 hover:text-t1">
                <X aria-hidden="true" className="h-5 w-5" strokeWidth={2} />
              </button>
            </div>
          </motion.div>

          <div className="grid gap-3 p-3 sm:p-4 md:grid-cols-2">
            {/* Left column: core fields */}
            <motion.div variants={anim ? item : undefined} className="flex flex-col gap-3">
              <label className="flex flex-col gap-1 text-xs font-medium text-t3">
                Nombre
                <Input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Ej: Scraping nocturno" />
              </label>

              <label className="flex flex-col gap-1 text-xs font-medium text-t3">
                Expresión cron <span className="font-normal text-t4">(seg min hora día mes díaSem)</span>
                <Input className="font-mono" value={form.cronExpr} placeholder="0 0 3 * * *"
                  onChange={e => setForm(f => ({ ...f, cronExpr: e.target.value }))} />
              </label>

              <fieldset className="flex flex-col gap-1">
                <legend className="mb-1 text-[.6rem] font-bold uppercase tracking-wide text-t4">Rango de precio (ARS)</legend>
                <div className="flex items-center gap-2">
                  <Input type="number" inputMode="numeric" aria-label="Precio mínimo" placeholder="Mín"
                    value={form.precioMin} onChange={e => setForm(f => ({ ...f, precioMin: +e.target.value }))} />
                  <span className="text-t4">—</span>
                  <Input type="number" inputMode="numeric" aria-label="Precio máximo" placeholder="Máx"
                    value={form.precioMax} onChange={e => setForm(f => ({ ...f, precioMax: +e.target.value }))} />
                </div>
              </fieldset>

              <div className="flex flex-col gap-2 pt-1">
                <CheckRow icon={RefreshCw} checked={form.enabled} onChange={v => setForm(f => ({ ...f, enabled: v }))} label="Job habilitado" />
                <CheckRow icon={Zap} checked={form.forceRetrain} onChange={v => setForm(f => ({ ...f, forceRetrain: v }))} label="Forzar reentrenamiento del modelo" />
                <CheckRow icon={Cpu} checked={form.useGpu} onChange={v => setForm(f => ({ ...f, useGpu: v }))} label="Usar GPU si está disponible" hint="Desactivar fuerza CPU" />
              </div>
            </motion.div>

            {/* Right column: sitios chips + history */}
            <motion.div variants={anim ? item : undefined} className="flex flex-col gap-3">
              {allSitios.length > 0 && (
                <div>
                  <div className="mb-2 flex items-center justify-between">
                    <span className="flex items-center gap-1.5 text-[.6rem] font-bold uppercase tracking-wide text-t4">
                      <Server aria-hidden="true" className="h-3.5 w-3.5" strokeWidth={2} />
                      Sitios <span className="text-primary2">({selected.length}/{allSitios.length})</span>
                    </span>
                    <button type="button" className="text-[.62rem] font-semibold text-primary2 hover:underline"
                      onClick={() => setSelected(allSelected ? [] : allSitios.map(s => s.nombre))}>
                      {allSelected ? 'Ninguno' : 'Todos'}
                    </button>
                  </div>
                  <div className="flex flex-col gap-2">
                    {Object.entries(byRubro).map(([rubro, items]) => {
                      const rm = RUBRO_META[rubro] || { color: 'var(--t4)', label: rubro };
                      return (
                        <div key={rubro}>
                          <div className="mb-1 text-[.56rem] font-bold uppercase tracking-[.08em]" style={{ color: rm.color }}>{rm.label}</div>
                          <motion.div variants={anim ? container : undefined} className="flex flex-wrap gap-1.5">
                            {items.map(s => {
                              const sel = selected.includes(s.nombre);
                              return (
                                <motion.button key={s.nombre} type="button" variants={anim ? chip : undefined}
                                  whileHover={anim ? { scale: 1.05, y: -1 } : undefined}
                                  whileTap={anim ? { scale: 0.97 } : undefined}
                                  onClick={() => setSelected(sel ? selected.filter(x => x !== s.nombre) : [...selected, s.nombre])}
                                  aria-pressed={sel}
                                  className="cursor-pointer rounded-full border-[1.5px] px-2.5 py-1 text-xs font-semibold transition-colors"
                                  style={{
                                    background: sel ? 'color-mix(in srgb, var(--p) 15%, transparent)' : 'transparent',
                                    borderColor: sel ? 'var(--p)' : 'var(--bd2)',
                                    color: sel ? 'var(--p2)' : 'var(--t4)',
                                  }}>
                                  {s.nombre}
                                </motion.button>
                              );
                            })}
                          </motion.div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {!isNew && (
                <div className="border-t border-border pt-3">
                  <div className="mb-1.5 text-[.6rem] font-bold uppercase tracking-wide text-t4">Historial de ejecuciones</div>
                  {executions.length === 0 ? (
                    <p className="text-xs text-t4">Sin ejecuciones todavía.</p>
                  ) : (
                    <div className="flex max-h-[220px] flex-col gap-1 overflow-y-auto pr-1">
                      {executions.map(ex => {
                        const m = STATUS_META[ex.status] || STATUS_META.skipped;
                        return (
                          <button key={ex.id} type="button" onClick={() => setDetailExec(ex)}
                            className="flex items-center gap-2 rounded-btn px-2 py-1.5 text-left text-xs transition-colors hover:bg-s3">
                            <m.Icon aria-hidden="true" className={cn('h-3.5 w-3.5 shrink-0', m.tone, m.spin && 'animate-spin')} strokeWidth={2} />
                            <span className={cn('font-semibold', m.tone)}>{m.label}</span>
                            <span className="flex-1 truncate tabular-nums text-t4">{(ex.startedAt || '—').replace('T', ' ')}</span>
                            <span className="tabular-nums text-t4">{ex.durationMs != null ? `${Math.round(ex.durationMs / 1000)}s` : ''}</span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}
            </motion.div>
          </div>

          {error && <p className="px-4 text-xs text-danger sm:px-5">{error}</p>}

          {/* Footer actions */}
          <motion.div variants={anim ? item : undefined} className="sticky bottom-0 z-10 flex justify-end gap-2 border-t border-border bg-s2 p-3 sm:p-4">
            <Button variant="ghost" onClick={() => onClose?.()}>Cancelar</Button>
            <Button disabled={saving || !form.name.trim() || !form.cronExpr.trim() || selected.length === 0} onClick={handleSave}>
              {saving && <Loader2 aria-hidden="true" className="mr-1.5 h-4 w-4 animate-spin" strokeWidth={2} />}
              {isNew ? 'Crear job' : 'Guardar cambios'}
            </Button>
          </motion.div>
        </motion.div>

        {/* ── Slide-up execution log view ───────────────────────────────────── */}
        <AnimatePresence>
          {detailExec && (
            <motion.div
              key="detail"
              initial={anim ? { y: '100%', opacity: 0 } : false}
              animate={{ y: '0%', opacity: 1 }}
              exit={anim ? { y: '100%', opacity: 0 } : { opacity: 0 }}
              transition={{ type: 'spring', stiffness: 300, damping: 30, mass: 0.8 }}
              className="absolute inset-0 flex flex-col bg-s2"
            >
              <div className="flex items-center justify-between border-b border-border p-4">
                <button type="button" onClick={() => setDetailExec(null)}
                  className="flex items-center gap-1.5 text-sm font-medium text-t4 transition-colors hover:text-t1">
                  <ChevronLeft aria-hidden="true" className="h-4 w-4" strokeWidth={2} /> Volver
                </button>
                <h3 className="text-sm font-semibold text-t1">Detalle de ejecución</h3>
                <button type="button" onClick={() => onClose?.()} aria-label="Cerrar" className="text-t4 hover:text-t1">
                  <X aria-hidden="true" className="h-4 w-4" strokeWidth={2} />
                </button>
              </div>

              <div className="flex flex-col gap-3 overflow-y-auto p-4">
                <ExecStat exec={detailExec} />
                {detailExec.skippedReason && (
                  <p className="rounded-btn bg-s3 px-3 py-2 text-xs text-t3">Motivo: {detailExec.skippedReason}</p>
                )}
                <div>
                  <div className="mb-1 text-[.6rem] font-bold uppercase tracking-wide text-t4">Log</div>
                  <pre className="max-h-[45dvh] overflow-auto whitespace-pre-wrap break-words rounded-btn bg-bg p-3 font-mono text-[.68rem] text-t2">
                    {detailExec.logOutput || '(sin log)'}
                  </pre>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </motion.div>
    </motion.div>
  );
}

// Checkbox row with an icon + label + optional hint (44px tap area).
function CheckRow({ icon: Icon, checked, onChange, label, hint }) {
  return (
    <label className="flex cursor-pointer items-center gap-2 py-1 text-sm text-t2">
      <Checkbox checked={checked} onCheckedChange={onChange} />
      <Icon aria-hidden="true" className="h-4 w-4 shrink-0 text-t4" strokeWidth={1.75} />
      <span className="leading-tight">
        {label}
        {hint && <span className="block text-[.68rem] text-t4">{hint}</span>}
      </span>
    </label>
  );
}

function ExecStat({ exec }) {
  const m = STATUS_META[exec.status] || STATUS_META.skipped;
  const dur = exec.durationMs != null ? `${Math.round(exec.durationMs / 1000)}s` : '—';
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-btn border border-border p-3">
      <span className={cn('flex items-center gap-1.5 font-semibold', m.tone)}>
        <m.Icon aria-hidden="true" className={cn('h-4 w-4', m.spin && 'animate-spin')} strokeWidth={2} /> {m.label}
      </span>
      <span className="text-xs text-t4">Inicio: <span className="tabular-nums text-t2">{(exec.startedAt || '—').replace('T', ' ')}</span></span>
      <span className="text-xs text-t4">Fin: <span className="tabular-nums text-t2">{(exec.finishedAt || '—').replace('T', ' ')}</span></span>
      <span className="text-xs text-t4">Duración: <span className="tabular-nums text-t2">{dur}</span></span>
    </div>
  );
}
