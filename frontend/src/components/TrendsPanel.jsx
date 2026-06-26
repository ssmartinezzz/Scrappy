import { useEffect, useState } from 'react';
import { fetchTendencias, fetchMlEstado, fmt, BADGE_LABELS } from '../api';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, Cell, ScatterChart, Scatter,
  ReferenceLine, LabelList,
} from 'recharts';
import { SEMANTIC, BADGE_COLORS as BADGE_COLOR } from '../lib/colors';

// ─── Paleta ──────────────────────────────────────────────────────────────────
// CV_COLOR was a local hex map confirmed via grep during task planning — not
// in the design's original migration-map table, repointed here per the
// "no file left behind" instruction.
const CV_COLOR = cv =>
  cv < 40 ? SEMANTIC.positive : cv < 80 ? SEMANTIC.warn : SEMANTIC.negative;

// ─── Tooltip personalizado ────────────────────────────────────────────────────
const CustomTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null;
  const d = payload[0]?.payload;
  return (
    <div style={{
      background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:8,
      padding:'.6rem .85rem', fontSize:'.72rem', boxShadow:'0 8px 24px rgba(0,0,0,.5)',
    }}>
      <div style={{ fontWeight:700, color:'var(--t1)', marginBottom:4 }}>{d?.categoria || label}</div>
      <div style={{ color:'var(--t4)' }}>Mediana: <strong style={{color:'var(--t1)'}}>
        ${fmt(d?.medPrecio || d?.med || 0)}</strong></div>
      {d?.n      && <div style={{color:'var(--t4)'}}>Productos: <strong>{d.n}</strong></div>}
      {d?.cv !== undefined && <div style={{color: CV_COLOR(d.cv)}}>CV: {d.cv}% ({d.cv<40?'homogéneo':d.cv<80?'moderado':'muy variable'})</div>}
      {d?.q1     && <div style={{color:'var(--t4)'}}>Q1: ${fmt(d.q1)} · Q3: ${fmt(d.q3)}</div>}
    </div>
  );
};

// ─── KPI Card ─────────────────────────────────────────────────────────────────
const KpiCard = ({ icon, value, label, color = 'var(--t1)', sub }) => (
  <div style={{
    background:'var(--s2)', border:'1px solid var(--bd)', borderRadius:10,
    padding:'.75rem 1rem', flex:1, minWidth:120,
  }}>
    <div style={{ fontSize:'1.2rem', marginBottom:4 }}>{icon}</div>
    <div style={{ fontSize:'1.1rem', fontWeight:800, color, letterSpacing:'-.02em' }}>{value}</div>
    <div style={{ fontSize:'.68rem', color:'var(--t4)', marginTop:2 }}>{label}</div>
    {sub && <div style={{ fontSize:'.65rem', color:'var(--t4)', marginTop:1 }}>{sub}</div>}
  </div>
);

// ─── Horizontal bar chart for category prices ─────────────────────────────────
const CategoryPriceChart = ({ data }) => {
  if (!data?.length) return null;
  const sorted = [...data].sort((a,b) => b.med - a.med).slice(0,14);
  return (
    <div>
      <div className="detail-section-title">📊 Precio mediano por categoría</div>
      <ResponsiveContainer width="100%" height={sorted.length * 32 + 40}>
        <BarChart data={sorted} layout="vertical" margin={{ left:10, right:60, top:5, bottom:5 }}>
          <CartesianGrid horizontal={false} stroke="var(--bd)" strokeDasharray="3 3"/>
          <XAxis type="number" tick={{ fontSize:10, fill:'var(--t4)' }}
                 tickFormatter={v => `$${fmt(v)}`} axisLine={false} tickLine={false}/>
          <YAxis dataKey="categoria" type="category" width={100}
                 tick={{ fontSize:11, fill:'var(--t2)' }} axisLine={false} tickLine={false}/>
          <Tooltip content={<CustomTooltip/>}/>
          <Bar dataKey="med" radius={[0,4,4,0]} maxBarSize={20}>
            {sorted.map((d,i) => (
              <Cell key={i} fill={CV_COLOR(d.cv || 0)} fillOpacity={0.85}/>
            ))}
            <LabelList dataKey="med" position="right"
                       formatter={v=>`$${fmt(v)}`}
                       style={{ fill:'var(--t3)', fontSize:10 }}/>
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div style={{ display:'flex', gap:12, marginTop:4, justifyContent:'center' }}>
        {[[SEMANTIC.positive,'CV bajo (<40%): precios homogéneos'],
          [SEMANTIC.warn,'CV medio (40-80%): variación moderada'],
          [SEMANTIC.negative,'CV alto (>80%): precios muy dispersos']].map(([c,l]) => (
          <span key={c} style={{ fontSize:'.62rem', color:'var(--t4)', display:'flex', gap:4, alignItems:'center' }}>
            <span style={{ width:10, height:10, borderRadius:2, background:c, display:'inline-block'}}/>
            {l}
          </span>
        ))}
      </div>
    </div>
  );
};

// ─── Scatter: cantidad vs precio mediano (bubble por CV) ──────────────────────
const CategoryBubble = ({ data }) => {
  if (!data?.length) return null;
  const pts = data.map(d => ({
    ...d,
    x: d.med,
    y: d.n,
    z: Math.max(8, Math.min(40, (d.cv || 0) / 2.5)),
  })).slice(0, 20);
  return (
    <div>
      <div className="detail-section-title">🎯 Cantidad vs precio mediano</div>
      <div style={{ fontSize:'.68rem', color:'var(--t4)', marginBottom:6 }}>
        Tamaño de burbuja = variabilidad de precios (CV). Cuadrante inferior-izquierdo = más accesible.
      </div>
      <ResponsiveContainer width="100%" height={240}>
        <ScatterChart margin={{ left:10, right:20, top:10, bottom:20 }}>
          <CartesianGrid stroke="var(--bd)" strokeDasharray="3 3"/>
          <XAxis dataKey="x" name="Precio mediano" type="number"
                 tickFormatter={v=>`$${fmt(v)}`}
                 tick={{ fontSize:10, fill:'var(--t4)' }} axisLine={false}>
          </XAxis>
          <YAxis dataKey="y" name="Cantidad" type="number"
                 tick={{ fontSize:10, fill:'var(--t4)' }} axisLine={false}/>
          <Tooltip cursor={{ strokeDasharray:'3 3' }} content={<CustomTooltip/>}/>
          <Scatter data={pts} shape={({ cx, cy, payload }) => (
            <g>
              <circle cx={cx} cy={cy} r={payload.z}
                      fill={CV_COLOR(payload.cv || 0)} fillOpacity={0.7}
                      stroke={CV_COLOR(payload.cv || 0)} strokeWidth={1}/>
              <text x={cx} y={cy-payload.z-3} textAnchor="middle"
                    fontSize={9} fill="var(--t3)">{payload.categoria}</text>
            </g>
          )}/>
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
};

// ─── Insight automático ───────────────────────────────────────────────────────
const Insights = ({ cats, badges, total }) => {
  if (!cats?.length) return null;
  const cheapest  = cats.reduce((a,b) => a.med < b.med ? a : b, cats[0]);
  const priciest  = cats.reduce((a,b) => a.med > b.med ? a : b, cats[0]);
  const mostProd  = cats.reduce((a,b) => a.n   > b.n   ? a : b, cats[0]);
  const highCV    = cats.filter(c => c.cv > 70).slice(0,3);
  const ofertaCount = badges?.oferta_real || 0;
  const histLow   = badges?.precio_historico_bajo || 0;
  return (
    <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
      <div className="detail-section-title">💡 Insights de mercado</div>
      {[
        { icon:'💸', text: `La categoría más accesible es <strong>${cheapest.categoria}</strong> con mediana $${fmt(cheapest.med)}` },
        { icon:'💎', text: `La más cara es <strong>${priciest.categoria}</strong> con mediana $${fmt(priciest.med)}` },
        { icon:'📦', text: `<strong>${mostProd.categoria}</strong> tiene la mayor oferta: ${mostProd.n} productos` },
        highCV.length > 0 && { icon:'⚠️', text: `Alta variabilidad en: <strong>${highCV.map(c=>c.categoria).join(', ')}</strong> — conviene comparar bien` },
        ofertaCount > 0 && { icon:'✅', text: `Hay <strong>${ofertaCount}</strong> ofertas reales detectadas estadísticamente (${(ofertaCount/total*100).toFixed(1)}% del catálogo)` },
        histLow > 0 && { icon:'🏆', text: `<strong>${histLow}</strong> productos están en su mínimo histórico de precio` },
      ].filter(Boolean).map((ins, i) => (
        <div key={i} style={{
          display:'flex', gap:10, alignItems:'flex-start',
          background:'var(--s2)', borderRadius:8, padding:'.5rem .75rem',
          border:'1px solid var(--bd)', fontSize:'.75rem', color:'var(--t3)',
        }}>
          <span style={{ flexShrink:0 }}>{ins.icon}</span>
          <span dangerouslySetInnerHTML={{ __html: ins.text }}/>
        </div>
      ))}
    </div>
  );
};

// ─── Top deals section ────────────────────────────────────────────────────────
const TopDeals = ({ productos, onProductClick }) => {
  if (!productos?.length) return null;
  const byBadge = {
    precio_historico_bajo: productos.filter(p => p.badge === 'precio_historico_bajo'),
    oferta_real:           productos.filter(p => p.badge === 'oferta_real'),
    precio_bajo:           productos.filter(p => p.badge === 'precio_bajo' || p.segment === 'budget'),
  };
  const sections = Object.entries(byBadge).filter(([,ps]) => ps.length > 0);
  if (!sections.length) return null;

  return (
    <div>
      <div className="detail-section-title">🛍 Mejores oportunidades</div>
      {sections.map(([badge, ps]) => (
        <div key={badge} style={{ marginBottom:12 }}>
          <div style={{
            fontSize:'.68rem', color: BADGE_COLOR[badge] || 'var(--t4)',
            fontWeight:700, marginBottom:6,
          }}>
            {BADGE_LABELS[badge] || badge}
          </div>
          <div style={{ display:'flex', gap:8, overflowX:'auto', paddingBottom:4 }}>
            {ps.slice(0,8).map((p, i) => (
              <div key={p.url || i} onClick={() => onProductClick(p)}
                   style={{
                     flex:'0 0 140px', background:'var(--s2)', borderRadius:8,
                     border:'1px solid var(--bd)', cursor:'pointer',
                     transition:'border-color .15s', overflow:'hidden',
                   }}
                   onMouseOver={e=>e.currentTarget.style.borderColor='var(--p)'}
                   onMouseOut={e=>e.currentTarget.style.borderColor='var(--bd)'}
              >
                {p.img && (
                  <img src={p.img} alt={p.nombre} loading="lazy"
                       style={{ width:'100%', height:100, objectFit:'cover', display:'block' }}
                       onError={e=>e.target.style.display='none'}/>
                )}
                <div style={{ padding:'6px 8px' }}>
                  <div style={{
                    fontSize:'.7rem', color:'var(--t1)', fontWeight:600,
                    overflow:'hidden', display:'-webkit-box',
                    WebkitLineClamp:2, WebkitBoxOrient:'vertical',
                  }}>{p.nombre}</div>
                  <div style={{ fontSize:'.75rem', fontWeight:700,
                                color: BADGE_COLOR[badge] || 'var(--g)', marginTop:4 }}>
                    ${fmt(p.precio)}
                  </div>
                  <div style={{ fontSize:'.6rem', color:'var(--t4)' }}>
                    {p.marca || p.sitio}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
};

// ─── ML Aprendizaje panel ────────────────────────────────────────────────────
function MlAprendizaje({ badges, total }) {
  const [ml, setMl] = useState(null);

  useEffect(() => {
    fetchMlEstado().then(setMl).catch(() => {});
  }, []);

  const meta     = ml?.textMeta;
  const hasText  = ml?.hasTextModel;

  const badgeEntries = Object.entries(badges || {}).sort((a, b) => b[1] - a[1]);

  if (!hasText && badgeEntries.length === 0) return null;

  return (
    <div className="ml-aprendizaje-panel">
      <h4>🧠 ¿Qué aprendió la IA?</h4>
      {hasText && meta ? (
        <p style={{ margin:'0 0 8px', fontSize:'.72rem', color:'var(--t4)' }}>
          Modelo texto: <strong style={{ color:'var(--t2)' }}>
            {(meta.accuracy != null ? (meta.accuracy * 100).toFixed(1) : '?')}% accuracy
          </strong> · <strong style={{ color:'var(--t2)' }}>
            {meta.num_classes || meta.clases || meta.classes || '?'}
          </strong> categorías
        </p>
      ) : (
        <p style={{ margin:'0 0 8px', fontSize:'.72rem', color:'var(--t4)' }}>
          Sin modelo ML entrenado — usando estadísticas de precio
        </p>
      )}
      {badgeEntries.length > 0 && (
        <div className="badge-stats">
          {badgeEntries.map(([k, v]) => {
            const lbl = BADGE_LABELS[k] || k;
            const col = BADGE_COLOR[k];
            return (
              <span key={k} className="badge-stat"
                    style={col ? { borderColor: col, color: col } : {}}>
                {lbl}: {v}
              </span>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── TrendsPanel principal ────────────────────────────────────────────────────
export default function TrendsPanel({ onClusterClick, onProductClick }) {
  const [res, setRes] = useState(null);          // { state, data } | null (cargando)
  const [tab,  setTab]  = useState('mercado'); // mercado | oportunidades | clusters

  useEffect(() => { fetchTendencias().then(setRes); }, []);

  if (res === null) return (
    <div className="trends-panel">
      <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        Cargando…
      </div>
    </div>
  );

  if (res.state === 'failed') return (
    <div className="trends-panel">
      <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        El análisis ML no pudo generarse. Revisá los logs del servidor (scraper.log) y
        volvé a ejecutar un scraping.
      </div>
    </div>
  );

  if (res.state === 'error') return (
    <div className="trends-panel">
      <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        No se pudo conectar con el servidor. Verificá tu conexión y volvé a intentar.
      </div>
    </div>
  );

  if (res.state === 'empty' || !res.data) return (
    <div className="trends-panel">
      <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        Ejecutá un scraping para ver el análisis de mercado.
      </div>
    </div>
  );

  const data = res.data;

  const catDist  = data.distribucionCategorias || {};
  const cats     = data.categoriaStats || [];
  const clusters = data.trendingClusters || [];
  const badges   = data.badgeCounts || {};
  const total    = data.totalProductos || 1;
  const tops     = data.topProductos || [];

  // Preparar datos para charts
  const chartData = Object.entries(catDist).map(([cat, st]) => ({
    categoria: cat, med: st.median || 0, n: st.n || 0,
    cv: st.cv || 0, q1: st.q1, q3: st.q3,
  })).filter(d => d.n >= 3 && d.med > 0)
    .sort((a,b) => b.med - a.med);

  const totalProds = total;
  const globalMed  = chartData.length
    ? Math.round(chartData.reduce((sum,d) => sum + d.med * d.n, 0) /
                 chartData.reduce((sum,d) => sum + d.n, 0))
    : 0;

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Sub-tab bar */}
      <div style={{
        display:'flex', borderBottom:'1px solid var(--bd)',
        background:'var(--s1)', position:'sticky', top:'var(--sticky-offset)', zIndex:'var(--z-chrome-sub)',
      }}>
        {[['mercado','📈 Mercado'],['oportunidades','💰 Oportunidades'],['clusters','🔥 Clusters']].map(([k,l]) => (
          <button key={k} onClick={() => setTab(k)} style={{
            padding:'.55rem 1rem', background:'none', border:'none', cursor:'pointer',
            fontSize:'.78rem', fontWeight:600,
            color: tab===k ? 'var(--p2)' : 'var(--t4)',
            borderBottom: tab===k ? '2px solid var(--p2)' : '2px solid transparent',
          }}>{l}</button>
        ))}
      </div>

      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem',
                    display:'flex', flexDirection:'column', gap:'1.5rem' }}>

        {/* ── TAB MERCADO ───────────────────────────────────────────── */}
        {tab === 'mercado' && (
          <>
            {/* KPIs */}
            <div style={{ display:'flex', gap:8, flexWrap:'wrap' }}>
              <KpiCard icon="📦" value={fmt(totalProds)} label="Productos scrapeados" color="var(--p2)"/>
              <KpiCard icon="💰" value={`$${fmt(globalMed)}`} label="Precio mediano global" color={SEMANTIC.positive}/>
              <KpiCard icon="✅" value={badges.oferta_real || 0}
                       label="Ofertas reales" sub={`${((badges.oferta_real||0)/total*100).toFixed(1)}% del catálogo`}
                       color={SEMANTIC.oferta}/>
              <KpiCard icon="🏆" value={badges.precio_historico_bajo || 0}
                       label="Mínimos históricos" color={SEMANTIC.warn}/>
            </div>

            {/* Bar chart */}
            <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
              <CategoryPriceChart data={chartData}/>
            </div>

            {/* Bubble chart */}
            {chartData.length >= 4 && (
              <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
                <CategoryBubble data={chartData}/>
              </div>
            )}

            {/* Insights */}
            <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
              <Insights cats={chartData} badges={badges} total={total}/>
            </div>

            {/* ML Aprendizaje */}
            <MlAprendizaje badges={badges} total={total}/>
          </>
        )}

        {/* ── TAB OPORTUNIDADES ────────────────────────────────────── */}
        {tab === 'oportunidades' && (
          <>
            <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
              <TopDeals productos={tops} onProductClick={onProductClick}/>
              {tops.length === 0 && (
                <div style={{ color:'var(--t4)', fontSize:'.8rem', textAlign:'center', padding:'2rem' }}>
                  Las oportunidades aparecen después del primer scraping con ML.
                </div>
              )}
            </div>

            {/* Badge summary */}
            <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
              <div className="detail-section-title">📊 Distribución de badges</div>
              {Object.entries(badges).length > 0 ? (
                <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
                  {Object.entries(badges).sort((a,b) => b[1]-a[1]).map(([badge, count]) => {
                    const pct = count/total*100;
                    return (
                      <div key={badge} style={{ display:'flex', alignItems:'center', gap:10 }}>
                        <span style={{ fontSize:'.72rem', color:'var(--t2)', width:160, flexShrink:0 }}>
                          {BADGE_LABELS[badge] || badge}
                        </span>
                        <div style={{ flex:1, background:'var(--s3)', borderRadius:4, height:8, overflow:'hidden' }}>
                          <div style={{
                            height:'100%', borderRadius:4,
                            width:`${Math.min(100, pct*5)}%`,
                            background: BADGE_COLOR[badge] || 'var(--p)',
                          }}/>
                        </div>
                        <span style={{ fontSize:'.7rem', color:'var(--t4)', width:80, textAlign:'right' }}>
                          {count} ({pct.toFixed(1)}%)
                        </span>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div style={{ color:'var(--t4)', fontSize:'.78rem' }}>Sin datos de badges aún.</div>
              )}
            </div>
          </>
        )}

        {/* ── TAB CLUSTERS ─────────────────────────────────────────── */}
        {tab === 'clusters' && (
          <>
            <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
              <div className="detail-section-title">🔥 Clusters en tendencia</div>
              <div style={{ fontSize:'.68rem', color:'var(--t4)', marginBottom:10 }}>
                Grupos de productos similares que el mercado tiene más oferta. Hacé click para buscar.
              </div>
              <div style={{ display:'flex', flexWrap:'wrap', gap:6 }}>
                {clusters.map(cl => (
                  <button key={cl.cluster} onClick={() => onClusterClick(cl.label)}
                    style={{
                      padding:'6px 14px', borderRadius:20, background:'var(--s2)',
                      border:'1px solid var(--bd)', cursor:'pointer', transition:'all .15s',
                      display:'flex', alignItems:'center', gap:6, fontSize:'.78rem',
                      color:'var(--t2)',
                    }}
                    onMouseOver={e=>{ e.currentTarget.style.borderColor='var(--p)'; e.currentTarget.style.color='var(--p2)'; }}
                    onMouseOut={e=>{ e.currentTarget.style.borderColor='var(--bd)'; e.currentTarget.style.color='var(--t2)'; }}
                  >
                    {cl.label}
                    <span style={{ background:'var(--p)', color:'#fff', borderRadius:20,
                                   padding:'1px 7px', fontSize:'.65rem', fontWeight:700 }}>
                      {cl.size}
                    </span>
                  </button>
                ))}
                {clusters.length === 0 && (
                  <div style={{ color:'var(--t4)', fontSize:'.78rem' }}>Sin clusters aún.</div>
                )}
              </div>
            </div>

            {/* Categorías más activas */}
            <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
              <div className="detail-section-title">🏷 Categorías más activas</div>
              <div style={{ display:'flex', flexDirection:'column', gap:6 }}>
                {cats.slice(0,15).map((cat, i) => {
                  const maxN = cats[0]?.count || 1;
                  const pct  = Math.round(cat.count / maxN * 100);
                  return (
                    <div key={cat.categoria} style={{ display:'flex', alignItems:'center', gap:10 }}>
                      <span style={{
                        fontSize:'.72rem', color:'var(--t2)', width:130, flexShrink:0,
                        overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                      }}>{cat.categoria}</span>
                      <div style={{ flex:1, background:'var(--s3)', borderRadius:4, height:8, overflow:'hidden' }}>
                        <div style={{
                          height:'100%', borderRadius:4, width:`${pct}%`,
                          background:`linear-gradient(90deg, var(--p), var(--p2))`,
                        }}/>
                      </div>
                      <span style={{ fontSize:'.7rem', color:'var(--t4)', width:120, textAlign:'right' }}>
                        {cat.count} · ${fmt(cat.medPrecio || cat.avgPrecio)}
                      </span>
                    </div>
                  );
                })}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
