import { BrandMark } from './brand/Brand';
import { useState, useEffect, useRef } from 'react';
import { fetchSitios, startScrape, limpiarCatalogo, limpiarMl } from '../api';
import { cn } from '../lib/utils';
import MlStatusPanel from './MlStatusPanel';
import { SEMANTIC } from '../lib/colors';
import { PRECIO_MIN_DEFAULT, PRECIO_MAX_DEFAULT } from '../lib/scrapeDefaults';
import { RUBROS } from '../lib/rubros';
import { sitiosTerminados } from '../lib/scrapeProgress';
import { logoSobreOscuro } from '../lib/brandLogos';
import {
  sitioLabel, sitioLogoMarca, sitioLogoUrl, agruparPorRubro, filtrarSitios,
} from '../lib/sitios';

const RUBRO_COLOR = {
  indumentaria: 'var(--p)',
  tecnologia:   'var(--p2)',
  suplementos:  SEMANTIC.positive,
};

function rubroMeta(r) {
  const found = RUBROS.find(x => x.key === r);
  return {
    icon: found?.icon ?? '🛍',
    label: found?.label ?? r,
    color: RUBRO_COLOR[r] ?? 'var(--t2)',
  };
}

const VISTAS = [['todas', 'Todas'], ['elegidas', 'Elegidas'], ['no-elegidas', 'No elegidas']];

function clearErrorText(status) {
  return status === 409 ? 'Esperá a que termine el scraping.' : `Error ${status}`;
}

function chipSelectedBackground(state) {
  if (state === 'done') return `color-mix(in srgb, ${SEMANTIC.positive} 15%, transparent)`;
  if (state === 'error') return `color-mix(in srgb, ${SEMANTIC.negative} 10%, transparent)`;
  if (state === 'running') return `color-mix(in srgb, ${SEMANTIC.warn} 15%, transparent)`;
  return 'color-mix(in srgb, var(--p) 15%, transparent)';
}

function chipTextColor(sel, state, stateColor) {
  if (!sel) return 'var(--t3)';
  return state === 'idle' ? 'var(--p2)' : stateColor;
}

// `indeterminate` is a DOM property with no HTML attribute, hence the ref.
function TriCheck({ state, onChange, label, disabled }) {
  const ref = useRef(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === 'some';
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      aria-label={label}
      checked={state === 'all'}
      onChange={onChange}
      disabled={disabled}
      className="h-[16px] w-[16px] shrink-0 cursor-pointer accent-[var(--p)] disabled:cursor-default"
    />
  );
}

function SitioChip({ sitio, sel, state, count, disabled, onToggle }) {
  const [logoOk, setLogoOk] = useState(true);
  const marca = sitioLogoMarca(sitio.nombre);
  const logo = sitioLogoUrl(sitio.nombre);
  const stateColor = { done: SEMANTIC.positive, error: SEMANTIC.negative, running: SEMANTIC.warn, idle: 'var(--p)' }[state];
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onToggle}
      className={cn(
        'flex items-center gap-1.5 rounded-full border-[1.5px] py-[4px] pl-2.5 pr-2.5 text-[.72rem] font-semibold transition-colors',
        disabled ? 'cursor-default' : 'cursor-pointer'
      )}
      style={{
        background: sel ? chipSelectedBackground(state) : 'transparent',
        borderColor: sel ? stateColor : 'var(--bd2)',
        color: chipTextColor(sel, state, stateColor),
      }}
    >
      {sel && state === 'idle' && '✓ '}
      {state === 'done'    && '✓ '}
      {state === 'error'   && '✗ '}
      {state === 'running' && (
        <span className="inline-block h-[7px] w-[7px] rounded-full bg-[var(--sem-warn)] [animation:pulse_1s_ease_infinite]" />
      )}
      {logo && logoOk && (
        <span
          className="flex h-[18px] items-center rounded-[4px] px-[4px]"
          style={{ background: logoSobreOscuro(marca) ? '#111' : '#fff' }}
        >
          <img src={logo} alt="" className="h-3.5 w-auto max-w-[56px] object-contain" onError={() => setLogoOk(false)} />
        </span>
      )}
      {sitioLabel(sitio.nombre)}
      {state === 'done' && count > 0 && (
        <span className="text-[.6rem] opacity-60">{count}</span>
      )}
    </button>
  );
}

export default function SplashPanel({
  config, scrapeStatus, scrapeMsg, progreso, backendUnreachable = false,
  onScrapeStart, onWatchRun, onGoToApp, prods, totalProds, tieneData,
}) {
  const [sitios,    setSitios]    = useState([]);
  const [selected,  setSelected]  = useState([]);
  const [precioMin, setPrecioMin] = useState(config?.precioMin || PRECIO_MIN_DEFAULT);
  const [precioMax, setPrecioMax] = useState(config?.precioMax || PRECIO_MAX_DEFAULT);
  const [tab,          setTab]          = useState('launch'); // launch | config
  const [forceRetrain, setForceRetrain] = useState(false);
  const [clearMsg,     setClearMsg]     = useState('');
  const [clearOk,      setClearOk]      = useState(null);
  const [q,            setQ]            = useState('');
  const [vista,        setVista]        = useState('todas');
  const [rubroFiltro,  setRubroFiltro]  = useState('');
  const [abiertos,     setAbiertos]     = useState({}); // accordion overrides, mobile only
  // `scrapeStatus` mirrors the backend's own ScraperStatus, so this component
  // never invents a value for it: when the stream cannot reach the backend, the
  // last thing it said is still RUNNING. What that costs us is the right to
  // keep DISPLAYING progress — an advancing bar over a backend that stopped
  // answering is a screen that lies, which is the bug this slice closes. The
  // launch controls stay locked meanwhile: as far as anyone knows a run is live.
  const isRunning    = scrapeStatus === 'RUNNING';
  const showProgress = isRunning && !backendUnreachable;
  const { terminados, total, pct } = sitiosTerminados(progreso);

  useEffect(() => {
    fetchSitios().then(data => {
      if (!data) return;
      const all = [...(data.base || []), ...(data.extras || [])];
      setSitios(all);
      setSelected(all.map(s => s.nombre));
    }).catch(() => {
      // Unreachable backend: the site picker stays empty; the status banner already says so.
    });
  }, []);

  async function handleScrape() {
    if (selected.length === 0) return;
    onScrapeStart();
    // Armed in `finally`: a rejected POST may still have started the run
    // (2026-08-26 it did). The backend's status is the truth, not this response.
    try {
      await startScrape({ precioMin, precioMax, sitios: selected, forceRetrain });
    } catch { /* the status read in onWatchRun reports what actually happened */ }
    finally {
      onWatchRun(() => onGoToApp());
    }
  }

  async function handleBorrarCatalogo() {
    if (!window.confirm('¿Seguro? Esto borra todos los productos, el historial de precios y las estadísticas de categorías. No se puede deshacer.')) return;
    const r = await limpiarCatalogo();
    const txt = r.ok ? '✓ Catálogo borrado.' : clearErrorText(r.status);
    setClearMsg(txt); setClearOk(r.ok);
    setTimeout(() => { setClearMsg(''); setClearOk(null); }, 5000);
  }

  async function handleBorrarMl() {
    if (!window.confirm('¿Seguro? Esto borra los scores, badges y tendencias ML. Los productos quedan intactos.')) return;
    const r = await limpiarMl();
    const txt = r.ok ? '✓ Datos ML borrados.' : clearErrorText(r.status);
    setClearMsg(txt); setClearOk(r.ok);
    setTimeout(() => { setClearMsg(''); setClearOk(null); }, 5000);
  }

  // Chip status
  const getSitioState = (nombre) => {
    if (!isRunning || !progreso?.sitios) return 'idle';
    const found = progreso.sitios.find(s => s.nombre === nombre);
    return found?.estado || 'idle';
  };

  const selSet = new Set(selected);
  const todosGrupos = agruparPorRubro(sitios);
  const visibles = filtrarSitios(sitios, { q, vista, rubro: rubroFiltro, selected });
  const gruposVisibles = agruparPorRubro(visibles);
  const masGrande = todosGrupos.reduce((m, [r, items]) => (items.length > (m?.n ?? 0) ? { r, n: items.length } : m), null)?.r;

  // Bulk actions act on the currently VISIBLE sites only (search, view and rubro filters apply).
  const nombresVisibles = visibles.map(s => s.nombre);
  const elegirVisibles = () => setSelected([...new Set([...selected, ...nombresVisibles])]);
  const quitarVisibles = () => setSelected(selected.filter(n => !nombresVisibles.includes(n)));
  const invertirVisibles = () => setSelected([
    ...selected.filter(n => !nombresVisibles.includes(n)),
    ...nombresVisibles.filter(n => !selSet.has(n)),
  ]);

  const toggleSitio = (nombre) => setSelected(
    selSet.has(nombre) ? selected.filter(x => x !== nombre) : [...selected, nombre]
  );
  const toggleRubro = (items) => {
    const nombres = items.map(s => s.nombre);
    const todos = nombres.every(n => selSet.has(n));
    setSelected(todos
      ? selected.filter(n => !nombres.includes(n))
      : [...new Set([...selected, ...nombres])]);
  };

  const resumenRubros = todosGrupos
    .map(([r, items]) => [r, items.filter(s => selSet.has(s.nombre)).length])
    .filter(([, n]) => n > 0);

  const btnAccion = 'cursor-pointer rounded-btn border border-border bg-s1 px-[12px] py-1.5 text-[.72rem] font-semibold text-t2 transition-colors hover:border-primary hover:text-primary2 disabled:cursor-default disabled:opacity-50';
  const precioWrap = 'flex min-w-0 flex-1 items-center gap-[8px] rounded-btn border border-border bg-s1 px-[12px] focus-within:border-primary';
  const precioInput = 'min-w-0 flex-1 bg-transparent py-[8px] text-[.82rem] text-t1 outline-none disabled:opacity-60';

  return (
    <div className="fixed inset-0 overflow-y-auto bg-bg">

      {/* ── Topbar ─────────────────────────────────────────────── */}
      <header className="flex items-center justify-between gap-[12px] border-b border-border bg-s1 px-[16px] py-[12px] lg:px-8">
        <div className="flex min-w-0 items-center gap-[12px] lg:gap-[24px]">
          <div className="flex items-center gap-2.5">
            <BrandMark size={32} className="rounded-[8px]" />
            <h1 className="bg-gradient-to-br from-primary2 to-primary bg-clip-text font-display text-[1.25rem] tracking-[-.03em] text-transparent">
              Scrappy
            </h1>
          </div>
          {/* Breadcrumb — la salida al catálogo, gateada sobre `tieneData`
              ("hay algo a lo que volver"), no sobre la cantidad de productos
              ni el estado de la corrida. */}
          {tieneData && (
            <nav aria-label="Migas de pan">
              <ol className="flex items-center gap-[4px] text-[.78rem] text-t4">
                <li>
                  <button
                    className="cursor-pointer rounded-btn bg-transparent px-[4px] py-0.5 font-semibold text-primary2 transition-colors hover:text-primary"
                    onClick={onGoToApp}
                    type="button"
                  >
                    Catálogo<span aria-hidden="true" className="lg:hidden"> →</span>
                  </button>
                </li>
                <li aria-hidden="true" className="hidden lg:block">/</li>
                <li aria-current="page" className="hidden text-t2 lg:block">Scraping</li>
              </ol>
            </nav>
          )}
        </div>
        <div className="flex items-center gap-[8px]">
          {[['launch', '▶ Lanzar', 'hidden lg:inline-flex'], ['config', '⚙ Config', 'inline-flex']].map(([k, l, vis]) => (
            <button
              key={k}
              type="button"
              onClick={() => setTab(k)}
              className={cn(
                'cursor-pointer items-center rounded-btn border px-[12px] py-1.5 text-[.74rem] font-bold transition-colors',
                vis,
                tab === k
                  ? 'border-border bg-s1 text-primary2 shadow-sm'
                  : 'border-transparent bg-transparent text-t4 hover:text-t2'
              )}
            >
              {k === 'config'
                ? <><span aria-hidden="true">⚙</span><span className="sr-only lg:not-sr-only lg:ml-[4px]">Config</span></>
                : l}
            </button>
          ))}
        </div>
      </header>

      {tab === 'launch' && (
        <div className="mx-auto flex flex-col lg:grid max-w-[1440px] gap-[16px] p-[16px] pb-28 lg:grid-cols-[340px_minmax(0,1fr)] lg:items-start lg:gap-[24px] lg:p-8">

          {/* ── Left column: price, summary, launch ──────────────── */}
          <aside className="flex min-w-0 flex-col gap-[16px] lg:sticky lg:top-[24px] lg:rounded-card lg:border lg:border-border lg:bg-s1 lg:p-[20px]">
            <div>
              <div className="mb-1.5 text-[.65rem] font-bold uppercase tracking-[.1em] text-t4">
                Rango de precio (ARS)
              </div>
              <div className="flex items-center gap-[8px]">
                <label className={precioWrap}>
                  <span className="text-[.6rem] font-bold uppercase tracking-[.08em] text-t4">Mín</span>
                  <span className="text-[.82rem] text-t3">$</span>
                  <input type="number" className={precioInput} aria-label="Precio mínimo"
                    placeholder="Mín" value={precioMin}
                    onChange={e => setPrecioMin(+e.target.value)} disabled={isRunning}/>
                </label>
                <label className={precioWrap}>
                  <span className="text-[.6rem] font-bold uppercase tracking-[.08em] text-t4">Máx</span>
                  <span className="text-[.82rem] text-t3">$</span>
                  <input type="number" className={precioInput} aria-label="Precio máximo"
                    placeholder="Máx" value={precioMax}
                    onChange={e => setPrecioMax(+e.target.value)} disabled={isRunning}/>
                </label>
              </div>
            </div>

            {sitios.length > 0 && (
              <div className="hidden rounded-card border border-border bg-s2 p-3.5 lg:block">
                <div className="text-[.6rem] font-bold uppercase tracking-[.1em] text-t4">Vas a scrapear</div>
                <div className="mt-[4px] font-display text-[1.35rem] font-extrabold text-t1">
                  {selected.length} de {sitios.length} sitios
                </div>
                <div className="mt-[4px] flex flex-wrap gap-x-[12px] gap-y-0.5 text-[.72rem] font-semibold text-t2">
                  {resumenRubros.map(([r, n]) => (
                    <span key={r} title={rubroMeta(r).label}>{rubroMeta(r).icon} {n}</span>
                  ))}
                </div>
              </div>
            )}

            {!isRunning && (
              <label className="flex cursor-pointer items-center gap-[8px] text-[.73rem] text-t4">
                <input type="checkbox" checked={forceRetrain}
                  onChange={e => setForceRetrain(e.target.checked)}/>
                {' '}Forzar reentrenamiento del modelo
              </label>
            )}

            {isRunning && backendUnreachable && (
              <p role="alert" className="text-center text-[.72rem] text-danger">
                No se pudo contactar al servidor.
              </p>
            )}

            {/* Progress bar */}
            {showProgress && (
              <div className="flex flex-col gap-1.5">
                <div className="mb-0.5 flex justify-between text-[.7rem] text-t4">
                  <span>{scrapeMsg || 'Procesando...'}</span>
                  <span className="font-bold text-primary2">
                    {total > 0 && <span className="mr-[8px] font-normal text-t4">{terminados}/{total} sitios</span>}
                    {Math.round(pct)}%
                  </span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-btn bg-s3">
                  <div
                    className="h-full rounded-btn bg-gradient-to-r from-primary to-primary2 shadow-[0_0_12px_var(--p)] transition-[width] duration-500"
                    style={{ width:`${pct}%` }}
                  />
                </div>
                {totalProds > 0 && (
                  <button className="btn-primary p-2.5 text-[.82rem]" onClick={onGoToApp}>
                    👁 Ver {totalProds.toLocaleString('es-AR')} productos disponibles
                  </button>
                )}
              </div>
            )}

            {/* Launch — one element: pinned to the bottom on mobile, inline in the card on desktop */}
            {!isRunning && (
              <div className="fixed inset-x-0 bottom-0 z-20 flex flex-col gap-[7px] border-t border-border bg-s1 p-[12px] lg:static lg:z-auto lg:border-0 lg:bg-transparent lg:p-0">
                <button onClick={handleScrape} disabled={selected.length === 0}
                  className={cn(
                    'rounded-btn p-[13px] text-[.92rem] font-extrabold tracking-tight transition-all',
                    selected.length > 0
                      ? 'cursor-pointer bg-gradient-to-br from-primary to-primary2 text-on-accent shadow-[0_4px_20px_color-mix(in_srgb,var(--p)_40%,transparent)]'
                      : 'cursor-not-allowed bg-s3 text-t4'
                  )}>
                  ▶ Iniciar scraping — {selected.length} sitios
                </button>
                {totalProds > 0 && (
                  <button className="btn-sm btn-ghost hidden p-[8px] text-[.75rem] text-t4 lg:block" onClick={onGoToApp}>
                    Ver resultados anteriores ({totalProds.toLocaleString('es-AR')}) →
                  </button>
                )}
              </div>
            )}
          </aside>

          {/* ── Right column: toolbar + rubro cards ──────────────── */}
          <main className="flex min-w-0 flex-col gap-[16px]">
            <div className="flex flex-wrap items-center gap-[12px] lg:rounded-card lg:border lg:border-border lg:bg-s1 lg:p-[16px]">
              <input
                type="search"
                value={q}
                onChange={e => setQ(e.target.value)}
                placeholder="Buscar tienda…"
                aria-label="Buscar tienda"
                className="form-input order-1 min-w-0 w-full text-[.82rem] lg:w-auto lg:flex-1"
              />
              <div role="group" aria-label="Vista" className="order-2 flex w-full gap-[4px] rounded-btn bg-s2 p-[4px] lg:w-auto">
                {VISTAS.map(([k, l]) => (
                  <button
                    key={k}
                    type="button"
                    aria-pressed={vista === k}
                    onClick={() => setVista(k)}
                    className={cn(
                      'flex-1 cursor-pointer rounded-btn px-[12px] py-1.5 text-[.72rem] font-semibold transition-colors lg:flex-none',
                      vista === k ? 'bg-s1 text-t1 shadow-sm' : 'bg-transparent text-t4 hover:text-t2'
                    )}
                  >{l}</button>
                ))}
              </div>

              <div className="order-5 flex w-full items-center justify-between gap-[8px] lg:order-3 lg:w-auto">
                <span className="text-[.72rem] font-semibold text-t2 lg:hidden">
                  {selected.length} de {sitios.length} elegidas
                </span>
                <div className="flex gap-[8px]">
                  <button type="button" className={btnAccion} disabled={isRunning} onClick={elegirVisibles}>Todas</button>
                  <button type="button" className={btnAccion} disabled={isRunning} onClick={quitarVisibles}>Ninguna</button>
                  <button type="button" className={cn(btnAccion, 'hidden lg:block')} disabled={isRunning} onClick={invertirVisibles}>Invertir</button>
                </div>
              </div>

              <div className="order-4 -mx-[16px] flex w-[calc(100%+2rem)] gap-[8px] overflow-x-auto px-[16px] pb-[4px] lg:mx-0 lg:w-full lg:flex-wrap lg:overflow-visible lg:px-0 lg:pb-0">
                {[['', '🛍', 'Todos los rubros', sitios.length],
                  ...todosGrupos.map(([r, items]) => [r, rubroMeta(r).icon, rubroMeta(r).label, items.length])
                ].map(([r, icon, label, n]) => (
                  <button
                    key={r || 'todos'}
                    type="button"
                    aria-pressed={rubroFiltro === r}
                    onClick={() => setRubroFiltro(r)}
                    className={cn(
                      'flex shrink-0 cursor-pointer items-center gap-1.5 rounded-full px-[12px] py-1.5 text-[.72rem] font-semibold transition-colors',
                      rubroFiltro === r ? 'bg-t1 text-bg' : 'bg-s2 text-t2 hover:text-t1'
                    )}
                  >
                    {r && <span aria-hidden="true">{icon}</span>}
                    {label}
                    <span className="text-[.62rem] opacity-60">{n}</span>
                  </button>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-[16px] lg:grid lg:grid-cols-2">
              {gruposVisibles.map(([rubro, visibles]) => {
                const meta = rubroMeta(rubro);
                const items = (todosGrupos.find(([r]) => r === rubro) || [rubro, visibles])[1];
                const nSel = items.filter(s => selSet.has(s.nombre)).length;
                const estado = nSel === 0 ? 'none' : nSel === items.length ? 'all' : 'some';
                const abierto = abiertos[rubro] ?? rubro === masGrande;
                return (
                  <section
                    key={rubro}
                    className={cn(
                      'min-w-0 rounded-card border border-border bg-s1 p-3.5',
                      items.length > 12 && 'lg:col-span-2'
                    )}
                  >
                    <div className="flex items-center gap-2.5">
                      <TriCheck
                        state={estado}
                        label={`Elegir todas de ${meta.label}`}
                        disabled={isRunning}
                        onChange={() => toggleRubro(items)}
                      />
                      <button
                        type="button"
                        onClick={() => setAbiertos({ ...abiertos, [rubro]: !abierto })}
                        className="flex min-w-0 cursor-pointer items-center gap-1.5 bg-transparent text-left lg:cursor-default"
                        style={{ color: meta.color }}
                      >
                        <span aria-hidden="true">{meta.icon}</span>
                        <span className="text-[.8rem] font-bold">{meta.label}</span>
                      </button>
                      <span className="text-[.7rem] text-t4">{nSel}/{items.length}</span>
                      <button
                        type="button"
                        disabled={isRunning}
                        onClick={() => toggleRubro(items)}
                        className="ml-auto cursor-pointer bg-transparent text-[.72rem] font-semibold text-primary2 hover:text-primary disabled:cursor-default disabled:opacity-50"
                      >
                        {estado === 'all' ? 'Quitar todas' : 'Elegir todas'}
                      </button>
                      <span
                        aria-hidden="true"
                        className={cn('text-[.65rem] text-t4 transition-transform lg:hidden', abierto && 'rotate-90')}
                      >▸</span>
                    </div>
                    <div className={cn('mt-[12px] flex-wrap gap-1.5 lg:flex', abierto ? 'flex' : 'hidden')}>
                      {visibles.map(s => (
                        <SitioChip
                          key={s.nombre}
                          sitio={s}
                          sel={selSet.has(s.nombre)}
                          state={getSitioState(s.nombre)}
                          count={progreso?.sitios?.find(x => x.nombre === s.nombre)?.count}
                          disabled={isRunning}
                          onToggle={() => toggleSitio(s.nombre)}
                        />
                      ))}
                    </div>
                  </section>
                );
              })}
              {sitios.length > 0 && gruposVisibles.length === 0 && (
                <p className="col-span-full py-[24px] text-center text-[.78rem] text-t4">
                  Ninguna tienda coincide con el filtro.
                </p>
              )}
            </div>
          </main>
        </div>
      )}

      {/* ── CONFIG ─────────────────────────────────────────────── */}
      {tab === 'config' && (
        <div className="mx-auto w-full max-w-[720px] p-[16px] lg:p-8">
  <div className="flex flex-col gap-[16px]">

            {/* DB */}
            <div className="rounded-card border border-border bg-s2 p-[16px]">
              <div className="mb-[4px] text-[.75rem] font-bold text-t1">
                💾 Base de datos
              </div>
              <div className="mb-2.5 text-[.7rem] leading-relaxed text-t4">
                PostgreSQL — backup/restore vía <code>pg_dump</code>/<code>pg_restore</code> contra <code>DATABASE_URL</code>
              </div>

              <div className="border-t border-s3 pt-[12px]">
                <div className="mb-[8px] text-[.68rem] font-bold uppercase tracking-[.06em] text-danger">
                  Borrar datos
                </div>
                <div className="flex flex-wrap gap-[7px]">
                  <button className="btn-sm cursor-pointer border-none bg-danger text-on-accent"
                    onClick={handleBorrarCatalogo}>
                    🗑 Borrar catálogo y historial
                  </button>
                  <button className="btn-sm cursor-pointer border-none bg-warning text-on-accent"
                    onClick={handleBorrarMl}>
                    🗑 Borrar solo datos ML
                  </button>
                </div>
                {clearMsg && (
                  <div className={cn('mt-[8px] text-[.7rem]', clearOk ? 'text-success' : 'text-danger')}>
                    {clearMsg}
                  </div>
                )}
              </div>
            </div>

            {/* Precio */}
            <div>
              <div className="mb-1.5 text-[.65rem] font-bold uppercase tracking-[.1em] text-t4">
                Rango de precio por defecto (ARS)
              </div>
              <div className="flex items-center gap-[8px]">
                <input type="number" className="form-input min-w-0 flex-1"
                  placeholder="Mín" value={precioMin}
                  onChange={e => setPrecioMin(+e.target.value)}/>
                <span className="text-t4">—</span>
                <input type="number" className="form-input min-w-0 flex-1"
                  placeholder="Máx" value={precioMax}
                  onChange={e => setPrecioMax(+e.target.value)}/>
              </div>
            </div>


            {/* ML Training */}
            <div className="rounded-card border border-border bg-s2 p-[16px]">
              <div className="mb-[4px] text-[.75rem] font-bold text-t1">
                🤖 Modelo ML personalizado
              </div>
              <div className="mb-[8px] text-[.7rem] leading-relaxed text-t4">
                Entrenás un clasificador con los productos del DB.
                <br/>• <strong>Fase 1</strong> — TF-IDF + SGD (texto): ~1 min
                <br/>• <strong>Fase 2</strong> — EfficientNet-B3 (GPU) / MobileNetV3 (CPU): ~10min con 3080
              </div>
              <MlStatusPanel/>
            </div>

            {/* Info */}
            <div className="border-t border-s3 pt-2.5">
              {[
                ['🌐 API', 'localhost:3000 (VITE_API_BASE_URL)'],
                ['🗄 Base de datos', 'PostgreSQL (DATABASE_URL)'],
                ['🤖 ML', 'Python 3.11 · Percentil + Z-score + IQR'],
              ].map(([label, val]) => (
                <div key={label} className="flex justify-between border-b border-s3 py-[4px] text-[.72rem]">
                  <span className="text-t4">{label}</span>
                  <span className="font-semibold text-t2">{val}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      <style>{`
        @keyframes pulse {
          0%,100% { opacity:1; transform:scale(1); }
          50%      { opacity:.5; transform:scale(1.4); }
        }
      `}</style>
    </div>
  );
}
