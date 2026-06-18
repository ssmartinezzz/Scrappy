import { useState, useEffect, useRef } from 'react';
import { fetchSitios, startScrape, exportarDB, importarDB, limpiarCatalogo, limpiarMl } from '../api';
import MlStatusPanel from './MlStatusPanel';

const RUBRO_META = {
  indumentaria: { icon:'👕', color:'#a371f7', label:'Indumentaria' },
  tecnologia:   { icon:'💻', color:'#58a6ff', label:'Tecnología'   },
  suplementos:  { icon:'💊', color:'#3fb950', label:'Nutrición'    },
};

function RubroLabel({ r }) {
  const m = RUBRO_META[r] || { icon:'🛍', color:'var(--t4)', label:r };
  return (
    <span style={{ fontSize:'.58rem', fontWeight:700, color:m.color,
                   textTransform:'uppercase', letterSpacing:'.08em' }}>
      {m.icon} {m.label}
    </span>
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
    <div style={{
      position:'fixed', inset:0,
      background:'var(--bg)',
      display:'flex', flexDirection:'column',
      alignItems:'center', justifyContent:'center',
      padding:'1rem', gap:'1.25rem',
    }}>

      {/* ── Header ─────────────────────────────────────────────── */}
      <div style={{ textAlign:'center' }}>
        <div style={{
          fontSize:'2.8rem', lineHeight:1,
          filter:'drop-shadow(0 0 24px rgba(163,113,247,.6))',
        }}>🛍</div>
        <h1 style={{
          fontSize:'1.5rem', fontWeight:900, margin:'8px 0 4px',
          background:'linear-gradient(135deg, var(--p2), var(--p))',
          WebkitBackgroundClip:'text', WebkitTextFillColor:'transparent',
          letterSpacing:'-.04em',
        }}>
          Scraper Ropa AR
        </h1>
        <div style={{ fontSize:'.75rem', color:'var(--t4)' }}>
          Inteligencia de precios · Argentina
        </div>
      </div>

      {/* ── Card ───────────────────────────────────────────────── */}
      <div style={{
        background:'var(--s1)', border:'1px solid var(--bd)',
        borderRadius:18, width:'100%', maxWidth:580,
        boxShadow:'0 32px 80px rgba(0,0,0,.6)',
        overflow:'hidden',
      }}>

        {/* Tab bar */}
        <div style={{ display:'flex', borderBottom:'1px solid var(--bd)' }}>
          {[['launch','▶ Lanzar'],['config','⚙ Config']].map(([k,l]) => (
            <button key={k} onClick={() => setTab(k)} style={{
              flex:1, padding:'.7rem .5rem', background:'none', border:'none',
              cursor:'pointer', fontSize:'.78rem', fontWeight:700,
              color: tab===k ? 'var(--p2)' : 'var(--t4)',
              borderBottom: `2px solid ${tab===k ? 'var(--p2)' : 'transparent'}`,
              transition:'all .15s',
            }}>{l}</button>
          ))}
        </div>

        <div style={{ padding:'1.25rem 1.5rem' }}>

          {/* ── TAB LAUNCH ──────────────────────────────────────── */}
          {tab === 'launch' && (
            <div style={{ display:'flex', flexDirection:'column', gap:'1.1rem' }}>

              {/* Precio range */}
              <div>
                <div style={{ fontSize:'.65rem', fontWeight:700, color:'var(--t4)',
                              textTransform:'uppercase', letterSpacing:'.1em', marginBottom:6 }}>
                  Rango de precio (ARS)
                </div>
                <div style={{ display:'flex', gap:8, alignItems:'center' }}>
                  <input type="number" className="form-input" style={{ flex:1, fontSize:'.82rem' }}
                    placeholder="Mín" value={precioMin}
                    onChange={e => setPrecioMin(+e.target.value)} disabled={isRunning}/>
                  <span style={{ color:'var(--t4)', fontSize:'.85rem' }}>—</span>
                  <input type="number" className="form-input" style={{ flex:1, fontSize:'.82rem' }}
                    placeholder="Máx" value={precioMax}
                    onChange={e => setPrecioMax(+e.target.value)} disabled={isRunning}/>
                </div>
              </div>

              {/* Sitios agrupados */}
              {sitios.length > 0 && (
                <div>
                  <div style={{
                    display:'flex', justifyContent:'space-between', alignItems:'center', marginBottom:8,
                  }}>
                    <div style={{ fontSize:'.65rem', fontWeight:700, color:'var(--t4)',
                                  textTransform:'uppercase', letterSpacing:'.1em' }}>
                      Sitios <span style={{ color:'var(--p2)' }}>({selected.length}/{sitios.length})</span>
                    </div>
                    <button className="btn-sm btn-ghost" style={{ fontSize:'.62rem', padding:'2px 8px' }}
                      onClick={() => setSelected(
                        selected.length === sitios.length ? [] : sitios.map(s => s.nombre)
                      )}>
                      {selected.length === sitios.length ? 'Deseleccionar todos' : 'Seleccionar todos'}
                    </button>
                  </div>

                  {Object.entries(byRubro).map(([rubro, items]) => (
                    <div key={rubro} style={{ marginBottom:10 }}>
                      <div style={{ marginBottom:5 }}><RubroLabel r={rubro}/></div>
                      <div style={{ display:'flex', flexWrap:'wrap', gap:5 }}>
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
                              style={{
                                padding:'5px 11px', borderRadius:20, fontSize:'.72rem',
                                fontWeight:600, cursor: isRunning ? 'default' : 'pointer',
                                background: sel
                                  ? (state === 'done' ? 'rgba(63,185,80,.15)' :
                                     state === 'error' ? 'rgba(232,67,147,.1)' :
                                     state === 'running' ? 'rgba(240,165,0,.15)' :
                                     'rgba(163,113,247,.15)')
                                  : 'transparent',
                                border: `1.5px solid ${sel ? stateColor || 'var(--p)' : 'var(--s3)'}`,
                                color: sel
                                  ? (state !== 'idle' ? stateColor : 'var(--p2)')
                                  : 'var(--t4)',
                                transition:'all .12s',
                                display:'flex', alignItems:'center', gap:5,
                              }}>
                              {state === 'done'    && '✓ '}
                              {state === 'error'   && '✗ '}
                              {state === 'running' && <span style={{
                                display:'inline-block', width:7, height:7, borderRadius:'50%',
                                background:'#f0a500', animation:'pulse 1s ease infinite',
                              }}/>}
                              {s.nombre}
                              {state === 'done' && progreso?.sitios?.find(x=>x.nombre===s.nombre)?.count > 0 && (
                                <span style={{ opacity:.6, fontSize:'.6rem' }}>
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
                <div style={{ display:'flex', flexDirection:'column', gap:6 }}>
                  <div style={{ display:'flex', justifyContent:'space-between',
                                fontSize:'.7rem', color:'var(--t4)', marginBottom:2 }}>
                    <span>{scrapeMsg || 'Procesando...'}</span>
                    <span style={{ fontWeight:700, color:'var(--p2)' }}>{Math.round(pct)}%</span>
                  </div>
                  <div style={{ background:'var(--s3)', borderRadius:6, height:6, overflow:'hidden' }}>
                    <div style={{
                      height:'100%', borderRadius:6,
                      width:`${pct}%`, transition:'width .5s ease',
                      background:'linear-gradient(90deg, var(--p), var(--p2))',
                      boxShadow:'0 0 12px var(--p)',
                    }}/>
                  </div>
                  {totalProds > 0 && (
                    <button className="btn-primary" onClick={onGoToApp}
                      style={{ padding:10, fontSize:'.82rem' }}>
                      👁 Ver {totalProds.toLocaleString('es-AR')} productos disponibles
                    </button>
                  )}
                </div>
              )}

              {/* Force retrain */}
              {!isRunning && (
                <label style={{ display:'flex', alignItems:'center', gap:8, cursor:'pointer',
                                fontSize:'.73rem', color:'var(--t4)' }}>
                  <input type="checkbox" checked={forceRetrain}
                    onChange={e => setForceRetrain(e.target.checked)}/>
                  Forzar reentrenamiento del modelo
                </label>
              )}

              {/* Launch button */}
              {!isRunning && (
                <div style={{ display:'flex', flexDirection:'column', gap:7 }}>
                  <button onClick={handleScrape} disabled={selected.length === 0}
                    style={{
                      padding:'13px', borderRadius:10, border:'none', cursor:'pointer',
                      fontSize:'.92rem', fontWeight:800, letterSpacing:'-.01em',
                      background: selected.length > 0
                        ? 'linear-gradient(135deg, var(--p), var(--p2))'
                        : 'var(--s3)',
                      color: selected.length > 0 ? '#fff' : 'var(--t4)',
                      boxShadow: selected.length > 0 ? '0 4px 20px rgba(163,113,247,.4)' : 'none',
                      transition:'all .2s',
                    }}>
                    ▶ Iniciar scraping — {selected.length} sitios
                  </button>
                  {totalProds > 0 && (
                    <button className="btn-sm btn-ghost" onClick={onGoToApp}
                      style={{ padding:'8px', fontSize:'.75rem', color:'var(--t4)' }}>
                      Ver resultados anteriores ({totalProds.toLocaleString('es-AR')} productos) →
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {/* ── TAB CONFIG ──────────────────────────────────────── */}
          {tab === 'config' && (
            <div style={{ display:'flex', flexDirection:'column', gap:'1rem' }}>

              {/* DB */}
              <div style={{
                background:'var(--s2)', borderRadius:10, padding:'1rem',
                border:'1px solid var(--bd)',
              }}>
                <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--t1)', marginBottom:4 }}>
                  💾 Base de datos
                </div>
                <div style={{ fontSize:'.7rem', color:'var(--t4)', marginBottom:10, lineHeight:1.5 }}>
                  Exportá para backup · Importá para restaurar datos anteriores
                </div>
                <div style={{ display:'flex', gap:7 }}>
                  <button className="btn-sm btn-outline" onClick={handleExport}>
                    ⬇ Exportar DB
                  </button>
                  <button className="btn-sm btn-ghost" onClick={() => fileRef.current?.click()}>
                    ⬆ Importar DB
                  </button>
                  <input ref={fileRef} type="file" accept=".db"
                    style={{ display:'none' }} onChange={handleImport}/>
                </div>
                {dbMsg && (
                  <div style={{ fontSize:'.7rem', marginTop:8,
                    color: dbMsg.startsWith('✓') ? '#3fb950' :
                           dbMsg.startsWith('✗') ? '#e84393' : 'var(--t4)' }}>
                    {dbMsg}
                  </div>
                )}

                <div style={{ marginTop:14, borderTop:'1px solid var(--s3)', paddingTop:12 }}>
                  <div style={{ fontSize:'.68rem', fontWeight:700, color:'#ef4444',
                                marginBottom:8, textTransform:'uppercase', letterSpacing:'.06em' }}>
                    Borrar datos
                  </div>
                  <div style={{ display:'flex', gap:7, flexWrap:'wrap' }}>
                    <button className="btn-sm" onClick={handleBorrarCatalogo}
                      style={{ background:'#ef4444', color:'#fff', border:'none', cursor:'pointer' }}>
                      🗑 Borrar catálogo y historial
                    </button>
                    <button className="btn-sm" onClick={handleBorrarMl}
                      style={{ background:'#f97316', color:'#fff', border:'none', cursor:'pointer' }}>
                      🗑 Borrar solo datos ML
                    </button>
                  </div>
                  {clearMsg && (
                    <div style={{ fontSize:'.7rem', marginTop:8,
                      color: clearOk ? '#3fb950' : '#e84393' }}>
                      {clearMsg}
                    </div>
                  )}
                </div>
              </div>

              {/* Precio */}
              <div>
                <div style={{ fontSize:'.65rem', fontWeight:700, color:'var(--t4)',
                              textTransform:'uppercase', letterSpacing:'.1em', marginBottom:6 }}>
                  Rango de precio por defecto (ARS)
                </div>
                <div style={{ display:'flex', gap:8, alignItems:'center' }}>
                  <input type="number" className="form-input" style={{ flex:1 }}
                    placeholder="Mín" value={precioMin}
                    onChange={e => setPrecioMin(+e.target.value)}/>
                  <span style={{ color:'var(--t4)' }}>—</span>
                  <input type="number" className="form-input" style={{ flex:1 }}
                    placeholder="Máx" value={precioMax}
                    onChange={e => setPrecioMax(+e.target.value)}/>
                </div>
              </div>


              {/* ML Training */}
              <div style={{ background:'var(--s2)', borderRadius:10, padding:'1rem', border:'1px solid var(--bd)' }}>
                <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--t1)', marginBottom:4 }}>
                  🤖 Modelo ML personalizado
                </div>
                <div style={{ fontSize:'.7rem', color:'var(--t4)', marginBottom:8, lineHeight:1.5 }}>
                  Entrenás un clasificador con los productos del DB.
                  <br/>• <strong>Fase 1</strong> — TF-IDF + LogReg (texto): ~1 min
                  <br/>• <strong>Fase 2</strong> — EfficientNet-B3 (GPU) / MobileNetV3 (CPU): ~10min con 3080
                </div>
                <MlStatusPanel/>
              </div>

              {/* Info */}
              <div style={{ borderTop:'1px solid var(--s3)', paddingTop:10 }}>
                {[
                  ['🌐 Puerto', 'localhost:3000'],
                  ['🗄 Base de datos', 'scraper.db (SQLite)'],
                  ['🤖 ML', 'Python 3.11 · Percentil + Z-score + IQR'],
                ].map(([label, val]) => (
                  <div key={label} style={{
                    display:'flex', justifyContent:'space-between',
                    fontSize:'.72rem', padding:'4px 0',
                    borderBottom:'1px solid var(--s3)',
                  }}>
                    <span style={{ color:'var(--t4)' }}>{label}</span>
                    <span style={{ color:'var(--t2)', fontWeight:600 }}>{val}</span>
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
