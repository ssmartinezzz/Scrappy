import { useState, useEffect } from 'react';
import { fetchMlResultado, fetchMlEstado, fetchStatus } from '../api';
import { fmt } from '../api';

const RUBRO_DEF = [
  { key: '',              icon: '🛍', label: 'Todos'        },
  { key: 'indumentaria',  icon: '👕', label: 'Indumentaria' },
  { key: 'tecnologia',    icon: '💻', label: 'Tecnología'   },
  { key: 'suplementos',   icon: '💊', label: 'Suplementos'  },
];

export default function Topbar({
  meta, facets, sitioFiltro, rubroFiltro,
  onSitioChange, onRubroChange, onReScrape,
  gymrat, onGymratToggle,
}) {
  const [collapsed, setCollapsed] = useState(false);
  const sitioMap  = meta?.marcas        || {};
  const rubrosMap = facets?.rubros      || {};
  const total     = meta?.total         || 0;

  // Solo mostrar rubros que tienen productos (o todos por defecto)
  const rubros = RUBRO_DEF.filter(r =>
    r.key === '' || (rubrosMap[r.key] || 0) > 0
  );

  // Polling estado ML
  const [mlStatus, setMlStatus] = useState(null);
  useEffect(() => {
    let alive = true;
    const poll = async () => {
      const r = await fetchMlResultado().catch(() => null);
      if (alive) setMlStatus(r);
      if (alive && r?.running) setTimeout(poll, 4000);
    };
    poll();
    return () => { alive = false; };
  }, []);

  // IPC widget data
  const [ipcData, setIpcData] = useState(null);
  useEffect(() => {
    fetch('/api/inflacion')
      .then(r => r.ok ? r.json() : null)
      .then(d => setIpcData(d))
      .catch(() => {});
  }, []);

  // ML banner data
  const [mlBanner, setMlBanner] = useState(null);
  useEffect(() => {
    Promise.all([
      fetchStatus().catch(() => null),
      fetchMlEstado().catch(() => null),
    ]).then(([st, ml]) => setMlBanner({ st, ml }));
  }, []);

  // Sitios: todos los que tienen productos, ordenados desc
  const sitios = Object.entries(sitioMap)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);

  const gymratCount = facets?.gymratCount || 0;

  return (
    <div style={{ background:'var(--s1)', borderBottom:'1px solid var(--bd)', flexShrink:0 }}>
      {/* Row 1: Logo + Rubro tabs */}
      <div style={{
        display:'flex', alignItems:'center', gap:8,
        padding:'.45rem 1rem', borderBottom:'1px solid var(--s3)',
      }}>
        <span style={{ fontSize:'1rem', marginRight:4 }}>🛒</span>
        <strong style={{ fontSize:'.85rem', color:'var(--p2)', marginRight:12 }}>Scraper AR</strong>

        {/* Rubro tabs */}
        <div style={{ display:'flex', gap:3 }}>
          {rubros.map(r => {
            const count = r.key ? rubrosMap[r.key] || 0 : total;
            const active = rubroFiltro === r.key;
            return (
              <button key={r.key} onClick={() => onRubroChange(r.key)} style={{
                padding:'4px 10px', borderRadius:20, border:'none',
                cursor:'pointer', fontSize:'.72rem', fontWeight:700,
                background: active ? 'var(--p)' : 'transparent',
                color: active ? '#fff' : 'var(--t4)',
                transition:'all .12s',
                display:'flex', alignItems:'center', gap:4,
              }}>
                {r.icon} {r.label}
                {count > 0 && (
                  <span style={{ opacity:.7, fontSize:'.62rem' }}>
                    {count.toLocaleString('es-AR')}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* ML Training indicator */}
        {mlStatus?.running && (
          <div style={{
            display:'flex', alignItems:'center', gap:5,
            padding:'3px 10px', borderRadius:20, marginLeft:8,
            background:'rgba(163,113,247,.18)', border:'1px solid rgba(163,113,247,.4)',
            fontSize:'.68rem', color:'#c084fc',
            animation:'mlpulse 1.5s ease-in-out infinite',
          }}>
            <span style={{ fontSize:'.75rem' }}>🤖</span>
            Entrenando ML...
          </div>
        )}
        {mlStatus?.done && !mlStatus?.running && (
          <div style={{
            display:'flex', alignItems:'center', gap:5,
            padding:'3px 10px', borderRadius:20, marginLeft:8,
            background:'rgba(63,185,80,.15)', border:'1px solid rgba(63,185,80,.4)',
            fontSize:'.68rem', color:'#3fb950', cursor:'default',
          }} title='Modelo ML entrenado y activo'>
            🤖 Modelo actualizado
          </div>
        )}
        {meta?.mlModeloActivo && !mlStatus?.running && !mlStatus?.done && (
          <div style={{
            display:'flex', alignItems:'center', gap:4,
            padding:'3px 10px', borderRadius:20, marginLeft:8,
            background:'rgba(63,185,80,.1)', border:'1px solid rgba(63,185,80,.25)',
            fontSize:'.65rem', color:'#3fb950',
          }} title={`Categorías refinadas por ML: ${(meta?.mlRefinadas||0).toLocaleString('es-AR')}`}>
            🤖 ML {(meta?.mlRefinadas||0) > 0 ? `·${meta.mlRefinadas} refinadas` : 'activo'}
          </div>
        )}
        <style>{`@keyframes mlpulse{0%,100%{opacity:1}50%{opacity:.55}}`}</style>

        {/* IPC Widget */}
        {ipcData?.mensual != null && (
          <div className="ipc-widget">
            <span>📊 IPC: <strong>{ipcData.mensual.toFixed(1)}%</strong>/mes</span>
            {ipcData.interanual != null && (
              <span style={{ color:'var(--t4)' }}>| Anual: <strong style={{ color:'var(--t2)' }}>{Math.round(ipcData.interanual)}%</strong></span>
            )}
          </div>
        )}

        {/* ML Banner */}
        {mlBanner && (
          <div className="ml-banner">
            {mlBanner.ml?.hasTextModel && mlBanner.ml?.textMeta
              ? `🧠 ML ${(mlBanner.ml.textMeta.accuracy != null ? (mlBanner.ml.textMeta.accuracy * 100).toFixed(1) : '?')}% acc · ${mlBanner.st?.mlRefinadas || 0} ref.`
              : '🧠 ML estadístico'}
          </div>
        )}

        {/* Re-scrape button */}
        <div style={{ marginLeft:'auto' }}>
          <button onClick={onReScrape} style={{
            padding:'4px 10px', borderRadius:20, fontSize:'.7rem',
            border:'1.5px solid var(--bd)', background:'transparent',
            color:'var(--t4)', cursor:'pointer',
          }}>
            ↺ Nuevo scraping
          </button>
        </div>
      </div>

      {/* Row 2: Sitio breadcrumbs */}
      {sitios.length > 0 && (
        <div style={{
          display:'flex', gap:4, padding:'.3rem 1rem', overflowX:'auto',
          scrollbarWidth:'none', flexWrap:'nowrap', alignItems:'center',
        }}>
          <button
            onClick={() => onSitioChange('')}
            style={{
              padding:'2px 9px', borderRadius:12, flexShrink:0,
              border:'none', cursor:'pointer', fontSize:'.68rem', fontWeight:700,
              background: !sitioFiltro ? 'var(--p)' : 'var(--s2)',
              color: !sitioFiltro ? '#fff' : 'var(--t4)',
            }}>
            Todas {total > 0 && <span style={{opacity:.7}}>{total.toLocaleString('es-AR')}</span>}
          </button>

          {gymratCount > 0 && (
            <button
              onClick={onGymratToggle}
              style={{
                padding:'2px 9px', borderRadius:12, flexShrink:0,
                border:'none', cursor:'pointer', fontSize:'.67rem', fontWeight: gymrat ? 700 : 400,
                background: gymrat ? '#84cc16' : 'var(--s2)',
                color: gymrat ? '#fff' : 'var(--t4)',
                whiteSpace:'nowrap', transition:'all .12s',
              }}>
              🏋️ Gym <span style={{ opacity:.8 }}>{gymratCount.toLocaleString('es-AR')}</span>
            </button>
          )}

          {sitios.map(([nombre, count]) => {
            const active = sitioFiltro === nombre;
            return (
              <button key={nombre}
                onClick={() => onSitioChange(active ? '' : nombre)}
                style={{
                  padding:'2px 9px', borderRadius:12, flexShrink:0,
                  border: `1px solid ${active ? 'var(--p)' : 'var(--bd)'}`,
                  cursor:'pointer', fontSize:'.67rem', fontWeight: active ? 700 : 400,
                  background: active ? 'rgba(163,113,247,.15)' : 'transparent',
                  color: active ? 'var(--p2)' : 'var(--t4)',
                  whiteSpace:'nowrap', transition:'all .12s',
                }}>
                {nombre} <span style={{ opacity:.6 }}>{count}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
