import { useState, useEffect, useRef } from 'react';
import { fetchSitios, startScrape, exportarDB, importarDB, limpiarCatalogo, limpiarMl } from '../api';
import { cn } from '../lib/utils';
import MlStatusPanel from './MlStatusPanel';

const RUBRO_META = {
  indumentaria: { icon:'👕', color:'#a371f7', label:'Indumentaria' },
  tecnologia:   { icon:'💻', color:'#58a6ff', label:'Tecnología'   },
  suplementos:  { icon:'💊', color:'#3fb950', label:'Nutrición'    },
};

function RubroLabel({ r }) {
  const m = RUBRO_META[r] || { icon:'🛍', color:'var(--t4)', label:r };
  return (
    <span
      className="text-[.58rem] font-bold uppercase tracking-[.08em]"
      style={{ color: m.color }}
    >
      {m.icon} {m.label}
    </span>
  );
}

// ─── Collapsible disclosure (same pattern as Sidebar's Section) ──────────────
function Disclosure({ title, children, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="border-t border-s3 pt-3">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between bg-transparent text-[.7rem] font-semibold text-t4"
      >
        <span>{title}</span>
        <span className={cn('text-[.65rem] opacity-50 transition-transform', open && 'rotate-180')}>
          ▾
        </span>
      </button>
      {open && <div className="mt-2.5">{children}</div>}
    </div>
  );
}

export default function SplashPanel({
  config, scrapeStatus, scrapeMsg, progreso,
  onScrapeStart, onStartPolling, onGoToApp, prods, totalProds,
}) {
  const [sitios,    setSitios]    = useState([]);
  const [selected,  setSelected]  = useState([]);
  const [precioMin, setPrecioMin] = useState(config?.precioMin || 0);
  const [precioMax, setPrecioMax] = useState(config?.precioMax || 300000);
  const [tab,          setTab]          = useState('launch'); // launch | config
  const [pct,          setPct]          = useState(5);
  const [dbMsg,        setDbMsg]        = useState('');
  const [forceRetrain, setForceRetrain] = useState(false);
  const [clearMsg,     setClearMsg]     = useState('');
  const [clearOk,      setClearOk]      = useState(null);
  const pctRef    = useRef(5);
  const fileRef   = useRef();
  const isRunning = scrapeStatus === 'RUNNING';

  useEffect(() => {
    fetchSitios().then(data => {
      if (!data) return;
      const all = [...(data.base || []), ...(data.extras || [])];
      setSitios(all);
      setSelected(all.map(s => s.nombre));
    });
  }, []);

  // Progress animation
  useEffect(() => {
    if (!isRunning) return;
    const t = setInterval(() => {
      const real = progreso?.total > 0
        ? Math.round((progreso.completados / progreso.total) * 92)
        : 0;
      const next = Math.max(pctRef.current + 1.2, real);
      const capped = Math.min(next, 94);
      setPct(capped); pctRef.current = capped;
    }, 600);
    return () => clearInterval(t);
  }, [isRunning, progreso]);

  async function handleScrape() {
    if (selected.length === 0) return;
    setPct(5); pctRef.current = 5;
    onScrapeStart();
    await startScrape({ precioMin, precioMax, sitios: selected, forceRetrain });
    onStartPolling(() => onGoToApp());
  }

  async function handleBorrarCatalogo() {
    if (!window.confirm('¿Seguro? Esto borra todos los productos, el historial de precios y las estadísticas de categorías. No se puede deshacer.')) return;
    const r = await limpiarCatalogo();
    const txt = r.ok ? '✓ Catálogo borrado.' : (r.status === 409 ? 'Esperá a que termine el scraping.' : `Error ${r.status}`);
    setClearMsg(txt); setClearOk(r.ok);
    setTimeout(() => { setClearMsg(''); setClearOk(null); }, 5000);
  }

  async function handleBorrarMl() {
    if (!window.confirm('¿Seguro? Esto borra los scores, badges y tendencias ML. Los productos quedan intactos.')) return;
    const r = await limpiarMl();
    const txt = r.ok ? '✓ Datos ML borrados.' : (r.status === 409 ? 'Esperá a que termine el scraping.' : `Error ${r.status}`);
    setClearMsg(txt); setClearOk(r.ok);
    setTimeout(() => { setClearMsg(''); setClearOk(null); }, 5000);
  }

  const byRubro = sitios.reduce((acc, s) => {
    const r = s.rubro || 'indumentaria';
    (acc[r] = acc[r] || []).push(s);
    return acc;
  }, {});

  // Sitio chip status
  const getSitioState = (nombre) => {
    if (!isRunning || !progreso?.sitios) return 'idle';
    const found = progreso.sitios.find(s => s.nombre === nombre);
    return found?.estado || 'idle';
  };

  const sitioColor = s => ({ done:'#3fb950', error:'#e84393', running:'#f0a500', idle:'var(--bd2)' }[s]);

  // Export / Import
  async function handleExport() {
    exportarDB(); setDbMsg('⬇ Descargando scraper.db...');
    setTimeout(() => setDbMsg(''), 3000);
  }
  async function handleImport(e) {
    const file = e.target.files?.[0]; if (!file) return;
    setDbMsg('Importando...');
    const res = await importarDB(file);
    setDbMsg(res?.ok ? `✓ DB importada (${(res.bytes/1024/1024).toFixed(1)} MB)` : '✗ Error');
    setTimeout(() => setDbMsg(''), 5000);
    if (fileRef.current) fileRef.current.value = '';
  }

  return (
    <div className="fixed inset-0 flex flex-col items-center justify-center gap-5 bg-bg p-4">

      {/* ── Header ─────────────────────────────────────────────── */}
      <div className="text-center">
        <div className="text-[2.8rem] leading-none drop-shadow-[0_0_24px_rgba(163,113,247,.6)]">🛍</div>
        <h1
          className="my-1 bg-gradient-to-br from-primary2 to-primary bg-clip-text text-2xl font-black tracking-tight text-transparent"
        >
          Scraper Ropa AR
        </h1>
        <div className="text-xs text-t4">
          Inteligencia de precios · Argentina
        </div>
      </div>

      {/* ── Card ───────────────────────────────────────────────── */}
      <div className="w-full max-w-[580px] overflow-hidden rounded-card border border-border bg-s1 shadow-[0_32px_80px_rgba(0,0,0,.6)]">

        {/* Tab bar */}
        <div className="flex border-b border-border">
          {[['launch','▶ Lanzar'],['config','⚙ Config']].map(([k,l]) => (
            <button
              key={k}
              onClick={() => setTab(k)}
              className={cn(
                'flex-1 cursor-pointer border-b-2 border-transparent bg-transparent px-2 py-3 text-[.78rem] font-bold text-t4 transition-colors',
                tab === k && 'border-primary2 text-primary2'
              )}
            >{l}</button>
          ))}
        </div>

        <div className="px-6 py-5">

          {/* ── TAB LAUNCH — single vertical flow: precio → sitios → launch ── */}
          {tab === 'launch' && (
            <div className="flex flex-col gap-[1.1rem]">

              {/* Precio range */}
              <div>
                <div className="mb-1.5 text-[.65rem] font-bold uppercase tracking-[.1em] text-t4">
                  Rango de precio (ARS)
                </div>
                <div className="flex items-center gap-2">
                  <input type="number" className="form-input flex-1 text-[.82rem]"
                    placeholder="Mín" value={precioMin}
                    onChange={e => setPrecioMin(+e.target.value)} disabled={isRunning}/>
                  <span className="text-[.85rem] text-t4">—</span>
                  <input type="number" className="form-input flex-1 text-[.82rem]"
                    placeholder="Máx" value={precioMax}
                    onChange={e => setPrecioMax(+e.target.value)} disabled={isRunning}/>
                </div>
              </div>

              {/* Sitios agrupados */}
              {sitios.length > 0 && (
                <div>
                  <div className="mb-2 flex items-center justify-between">
                    <div className="text-[.65rem] font-bold uppercase tracking-[.1em] text-t4">
                      Sitios <span className="text-primary2">({selected.length}/{sitios.length})</span>
                    </div>
                    <button className="btn-sm btn-ghost px-2 py-0.5 text-[.62rem]"
                      onClick={() => setSelected(
                        selected.length === sitios.length ? [] : sitios.map(s => s.nombre)
                      )}>
                      {selected.length === sitios.length ? 'Deseleccionar todos' : 'Seleccionar todos'}
                    </button>
                  </div>

                  {Object.entries(byRubro).map(([rubro, items]) => (
                    <div key={rubro} className="mb-2.5">
                      <div className="mb-1"><RubroLabel r={rubro}/></div>
                      <div className="flex flex-wrap gap-1.5">
                        {items.map(s => {
                          const sel   = selected.includes(s.nombre);
                          const state = getSitioState(s.nombre);
                          const stateColor = sitioColor(state);
                          return (
                            <button key={s.nombre} disabled={isRunning}
                              onClick={() => setSelected(sel
                                ? selected.filter(x => x !== s.nombre)
                                : [...selected, s.nombre]
                              )}
                              className={cn(
                                'flex items-center gap-1.5 rounded-full border-[1.5px] px-2.5 py-1 text-[.72rem] font-semibold transition-colors',
                                isRunning ? 'cursor-default' : 'cursor-pointer'
                              )}
                              style={{
                                background: sel
                                  ? (state === 'done' ? 'rgba(63,185,80,.15)' :
                                     state === 'error' ? 'rgba(232,67,147,.1)' :
                                     state === 'running' ? 'rgba(240,165,0,.15)' :
                                     'rgba(163,113,247,.15)')
                                  : 'transparent',
                                borderColor: sel ? (stateColor || 'var(--p)') : 'var(--s3)',
                                color: sel
                                  ? (state !== 'idle' ? stateColor : 'var(--p2)')
                                  : 'var(--t4)',
                              }}>
                              {state === 'done'    && '✓ '}
                              {state === 'error'   && '✗ '}
                              {state === 'running' && (
                                <span
    className="inline-block h-[7px] w-[7px] rounded-full bg-[#f0a500] [animation:pulse_1s_ease_infinite]"
                                />
                              )}
                              {s.nombre}
                              {state === 'done' && progreso?.sitios?.find(x=>x.nombre===s.nombre)?.count > 0 && (
                                <span className="text-[.6rem] opacity-60">
                                  {progreso.sitios.find(x=>x.nombre===s.nombre).count}
                                </span>
                              )}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* Progress bar */}
              {isRunning && (
                <div className="flex flex-col gap-1.5">
                  <div className="mb-0.5 flex justify-between text-[.7rem] text-t4">
                    <span>{scrapeMsg || 'Procesando...'}</span>
                    <span className="font-bold text-primary2">{Math.round(pct)}%</span>
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

              {/* Opciones avanzadas (forceRetrain demoted to a collapsed disclosure) */}
              {!isRunning && (
                <Disclosure title="⚙ Opciones avanzadas">
                  <label className="flex cursor-pointer items-center gap-2 text-[.73rem] text-t4">
                    <input type="checkbox" checked={forceRetrain}
                      onChange={e => setForceRetrain(e.target.checked)}/>
                    Forzar reentrenamiento del modelo
                  </label>
                </Disclosure>
              )}

              {/* Launch button — pinned to the bottom of the vertical flow */}
              {!isRunning && (
                <div className="flex flex-col gap-[7px]">
                  <button onClick={handleScrape} disabled={selected.length === 0}
                    className={cn(
                      'rounded-btn p-[13px] text-[.92rem] font-extrabold tracking-tight transition-all',
                      selected.length > 0
                        ? 'cursor-pointer bg-gradient-to-br from-primary to-primary2 text-white shadow-[0_4px_20px_rgba(163,113,247,.4)]'
                        : 'cursor-not-allowed bg-s3 text-t4'
                    )}>
                    ▶ Iniciar scraping — {selected.length} sitios
                  </button>
                  {totalProds > 0 && (
                    <button className="btn-sm btn-ghost p-2 text-[.75rem] text-t4" onClick={onGoToApp}>
                      Ver resultados anteriores ({totalProds.toLocaleString('es-AR')} productos) →
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {/* ── TAB CONFIG ──────────────────────────────────────── */}
          {tab === 'config' && (
            <div className="flex flex-col gap-4">

              {/* DB */}
              <div className="rounded-card border border-border bg-s2 p-4">
                <div className="mb-1 text-[.75rem] font-bold text-t1">
                  💾 Base de datos
                </div>
                <div className="mb-2.5 text-[.7rem] leading-relaxed text-t4">
                  Exportá para backup · Importá para restaurar datos anteriores
                </div>
                <div className="flex gap-[7px]">
                  <button className="btn-sm btn-outline" onClick={handleExport}>
                    ⬇ Exportar DB
                  </button>
                  <button className="btn-sm btn-ghost" onClick={() => fileRef.current?.click()}>
                    ⬆ Importar DB
                  </button>
                  <input ref={fileRef} type="file" accept=".db"
                    className="hidden" onChange={handleImport}/>
                </div>
                {dbMsg && (
                  <div className={cn(
                    'mt-2 text-[.7rem]',
                    dbMsg.startsWith('✓') ? 'text-success' :
                    dbMsg.startsWith('✗') ? 'text-danger' : 'text-t4'
                  )}>
                    {dbMsg}
                  </div>
                )}

                <div className="mt-3.5 border-t border-s3 pt-3">
                  <div className="mb-2 text-[.68rem] font-bold uppercase tracking-[.06em] text-danger">
                    Borrar datos
                  </div>
                  <div className="flex flex-wrap gap-[7px]">
                    <button className="btn-sm cursor-pointer border-none bg-danger text-white"
                      onClick={handleBorrarCatalogo}>
                      🗑 Borrar catálogo y historial
                    </button>
                    <button className="btn-sm cursor-pointer border-none bg-warning text-white"
                      onClick={handleBorrarMl}>
                      🗑 Borrar solo datos ML
                    </button>
                  </div>
                  {clearMsg && (
                    <div className={cn('mt-2 text-[.7rem]', clearOk ? 'text-success' : 'text-danger')}>
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
                <div className="flex items-center gap-2">
                  <input type="number" className="form-input flex-1"
                    placeholder="Mín" value={precioMin}
                    onChange={e => setPrecioMin(+e.target.value)}/>
                  <span className="text-t4">—</span>
                  <input type="number" className="form-input flex-1"
                    placeholder="Máx" value={precioMax}
                    onChange={e => setPrecioMax(+e.target.value)}/>
                </div>
              </div>


              {/* ML Training */}
              <div className="rounded-card border border-border bg-s2 p-4">
                <div className="mb-1 text-[.75rem] font-bold text-t1">
                  🤖 Modelo ML personalizado
                </div>
                <div className="mb-2 text-[.7rem] leading-relaxed text-t4">
                  Entrenás un clasificador con los productos del DB.
                  <br/>• <strong>Fase 1</strong> — TF-IDF + LogReg (texto): ~1 min
                  <br/>• <strong>Fase 2</strong> — EfficientNet-B3 (GPU) / MobileNetV3 (CPU): ~10min con 3080
                </div>
                <MlStatusPanel/>
              </div>

              {/* Info */}
              <div className="border-t border-s3 pt-2.5">
                {[
                  ['🌐 Puerto', 'localhost:3000'],
                  ['🗄 Base de datos', 'scraper.db (SQLite)'],
                  ['🤖 ML', 'Python 3.11 · Percentil + Z-score + IQR'],
                ].map(([label, val]) => (
                  <div key={label} className="flex justify-between border-b border-s3 py-1 text-[.72rem]">
                    <span className="text-t4">{label}</span>
                    <span className="font-semibold text-t2">{val}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes pulse {
          0%,100% { opacity:1; transform:scale(1); }
          50%      { opacity:.5; transform:scale(1.4); }
        }
      `}</style>
    </div>
  );
}
