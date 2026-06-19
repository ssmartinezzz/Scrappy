import { useEffect, useState } from 'react';
import { SEÑAL_CONFIG, scoreColor } from '../senalConfig';

function MiniSparkline({ url }) {
  const [hist, setHist] = useState([]);
  useEffect(() => {
    if (!url) return;
    fetch(`/api/historial?url=${encodeURIComponent(url)}`)
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (d?.historial?.length) setHist(d.historial.slice(-16)); })
      .catch(() => {});
  }, [url]);
  if (hist.length < 2) return null;
  const precios = hist.map(h => h.precio);
  const min = Math.min(...precios), max = Math.max(...precios);
  const range = max - min || 1;
  const W = 220, H = 38;
  const pts = precios.map((p, i) =>
    `${(i/(precios.length-1))*W},${H-((p-min)/range)*H}`
  ).join(' ');
  const lx = W, ly = H-((precios[precios.length-1]-min)/range)*H;
  return (
    <div style={{ margin:'6px 0 2px' }}>
      <svg width={W} height={H+14} viewBox={`0 0 ${W} ${H+14}`} style={{ overflow:'visible' }}>
        <polyline points={pts} fill="none" stroke="rgba(163,113,247,.5)"
          strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round"/>
        <circle cx={lx} cy={ly} r="3" fill="var(--p2)"/>
        <text x={lx+4} y={ly+4} fontSize="8" fill="var(--t3)">
          ${(precios[precios.length-1]/1000).toFixed(0)}k
        </text>
        <text x={0}   y={H+13} fontSize="7" fill="var(--t4)">{hist[0]?.fecha?.slice(0,7)}</text>
        <text x={W-22} y={H+13} fontSize="7" fill="var(--t4)" textAnchor="end">hoy</text>
      </svg>
    </div>
  );
}

export default function BuySignal({ url }) {
  const [data,     setData]     = useState(null);
  const [inflacion,setInflacion]= useState(null);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState(false);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    if (!url) { setLoading(false); return; }
    setLoading(true); setError(false);
    Promise.all([
      fetch(`/api/recomendacion?url=${encodeURIComponent(url)}`).then(r => r.ok ? r.json() : null),
      fetch('/api/inflacion').then(r => r.ok ? r.json() : null),
    ])
    .then(([rec, inf]) => { setData(rec); setInflacion(inf); })
    .catch(() => setError(true))
    .finally(() => setLoading(false));
  }, [url]);

  // Guard against any render errors
  if (error) return null;
  if (loading) return (
    <div style={{ fontSize:'.65rem', color:'var(--t4)', padding:'6px 0', opacity:.7 }}>
      Analizando precio...
    </div>
  );
  if (!data || !data.senal || data.senal === 'sin_datos') return (
    <div style={{
      fontSize:'.68rem', color:'var(--t4)', padding:'6px 8px',
      background:'var(--s3)', borderRadius:8, marginTop:8, fontStyle:'italic',
    }}>
      📉 Sin historial aún — aparece tras varios scrapings del mismo producto
    </div>
  );

  const cfg = SEÑAL_CONFIG[data.senal] || SEÑAL_CONFIG.precio_normal;
  const hasDetail = data.puntosHistorial >= 2;

  return (
    <div style={{
      background: cfg.bg, border: `1.5px solid ${cfg.border}`,
      borderRadius: 10, padding: '10px 12px', marginTop: 8,
    }}>
      {/* ── PRIMARY: señal + score — the single most prominent element ── */}
      <div style={{ display:'flex', justifyContent:'space-between', alignItems:'center' }}>
        <div style={{ display:'flex', alignItems:'center', gap:7 }}>
          <span style={{ fontSize:'1.15rem' }}>{cfg.icon}</span>
          <span style={{ fontSize:'.88rem', fontWeight:800, color:'var(--t1)' }}>{cfg.label}</span>
        </div>
        <span style={{ fontSize:'.78rem', fontWeight:800, color: scoreColor(data.scoreCompra) }}>
          {data.scoreCompra}/100
        </span>
      </div>

      <div style={{ fontSize:'.71rem', color:'var(--t3)', marginTop:5 }}>{data.mensaje}</div>

      <MiniSparkline url={url}/>

      {/* ── SECONDARY: collapsed by default, expand on click ── */}
      {hasDetail && (
        <button
          onClick={() => setExpanded(v => !v)}
          style={{
            marginTop:6, padding:'3px 0', background:'none', border:'none',
            color:'var(--t4)', fontSize:'.62rem', fontWeight:700, cursor:'pointer',
            display:'flex', alignItems:'center', gap:4,
          }}>
          {expanded ? '▲ Ocultar detalle' : '▼ Ver detalle (rango, tendencia, IPC)'}
        </button>
      )}

      {hasDetail && expanded && (
        <div style={{ marginTop:4, paddingTop:6, borderTop:'1px solid var(--bd)' }}>
          <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:'4px 10px', fontSize:'.63rem' }}>
            <span style={{ color:'var(--t4)' }}>
              Rango hist.: <strong style={{ color:'var(--t2)' }}>{data.pctDelMin}° pct.</strong>
            </span>
            <span style={{ color:'var(--t4)' }}>
              Cambio real: <strong style={{ color: data.cambioReal < 0 ? '#3fb950' : '#e84393' }}>
                {data.cambioReal > 0 ? '+' : ''}{data.cambioReal}%
              </strong>
            </span>
            <span style={{ color:'var(--t4)' }}>
              Mín: <strong style={{ color:'var(--t2)' }}>${(data.precioMin/1000).toFixed(0)}k</strong>
            </span>
            <span style={{ color:'var(--t4)' }}>
              Máx: <strong style={{ color:'var(--t2)' }}>${(data.precioMax/1000).toFixed(0)}k</strong>
            </span>
          </div>

          <div style={{ fontSize:'.62rem', color:'var(--t4)', marginTop:6, display:'flex', gap:10, flexWrap:'wrap' }}>
            <span>Tendencia: <strong style={{ color: data.tendencia==='bajando'?'#3fb950':data.tendencia==='subiendo'?'#e84393':'var(--t3)' }}>
              {data.tendencia==='bajando'?'↘ bajando':data.tendencia==='subiendo'?'↗ subiendo':'→ estable'}
            </strong></span>
            {inflacion?.mensual && (
              <span>IPC: <strong style={{ color:'var(--t2)' }}>{inflacion.mensual.toFixed(1)}%</strong>/mes
              &nbsp;·&nbsp;{inflacion.interanual?.toFixed(0)}% anual</span>
            )}
          </div>

          {inflacion?.actualizado && (
            <div style={{ fontSize:'.57rem', color:'var(--t4)', marginTop:4, opacity:.6 }}>
              Datos INDEC · IPC General · {inflacion.actualizado}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
