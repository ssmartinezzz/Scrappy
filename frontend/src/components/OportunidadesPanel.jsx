import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchTendencias, fmt } from '../api';
import { BADGE_META, SEMANTIC } from '../lib/colors';

// ─── Top deals preview (capped) ───────────────────────────────────────────
// Renamed from TrendsPanel's TopDeals to the new badge keys. This stays a
// capped preview (design "Out of Scope: topProductos preview cap ... not
// raised") — the full listing lives behind the /analisis/oportunidades/:badge
// drill-down (spec "Drill-Down Full Paginated Listing").
const PREVIEW_BADGES = ['all_time_low', 'verified_deal', 'below_market'];

const TopDeals = ({ productos, onProductClick }) => {
  if (!productos?.length) return null;
  const byBadge = Object.fromEntries(
    PREVIEW_BADGES.map(b => [b, productos.filter(p =>
      (p.badges || [p.badge]).includes(b))]));
  const sections = Object.entries(byBadge).filter(([, ps]) => ps.length > 0);
  if (!sections.length) return null;

  return (
    <div>
      <div className="detail-section-title">🛍 Mejores oportunidades</div>
      {sections.map(([badge, ps]) => {
        const meta = BADGE_META[badge];
        return (
          <div key={badge} style={{ marginBottom:12 }}>
            <div style={{
              fontSize:'.68rem', color: meta?.color || 'var(--t4)',
              fontWeight:700, marginBottom:6,
            }}>
              {meta?.label || badge}
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
                                  color: meta?.color || SEMANTIC.positive, marginTop:4 }}>
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
        );
      })}
    </div>
  );
};

// ─── OportunidadesPanel ───────────────────────────────────────────────────
// Routed at /analisis/oportunidades (spec "/analisis/* Routed Tabs"). Each
// badge row is clickable and navigates to the full paginated drill-down
// (/analisis/oportunidades/:badge). Negative badges (above_market,
// fake_discount) render with warning styling instead of being hidden (spec
// "Negative Badges Visible as Warnings").
export default function OportunidadesPanel({ onProductClick }) {
  const [res, setRes] = useState(null);
  const navigate = useNavigate();

  useEffect(() => { fetchTendencias().then(setRes); }, []);

  if (res === null) return (
    <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
      Cargando…
    </div>
  );

  if (res.state !== 'ok' || !res.data) return (
    <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
      Ejecutá un scraping para ver las oportunidades del catálogo.
    </div>
  );

  const data    = res.data;
  const badges  = data.badgeCounts || {};
  const total   = data.totalProductos || 1;
  const tops    = data.topProductos || [];

  const entries = Object.entries(badges).sort((a,b) => b[1]-a[1]);

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:'1.5rem' }}>
      <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
        <TopDeals productos={tops} onProductClick={onProductClick}/>
        {tops.length === 0 && (
          <div style={{ color:'var(--t4)', fontSize:'.8rem', textAlign:'center', padding:'2rem' }}>
            Las oportunidades aparecen después del primer scraping con ML.
          </div>
        )}
      </div>

      <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
        <div className="detail-section-title">📊 Distribución de badges</div>
        {entries.length > 0 ? (
          <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
            {entries.map(([badge, count]) => {
              const meta = BADGE_META[badge];
              const pct  = count/total*100;
              const isWarning = meta?.tier === 'warning';
              return (
                <button key={badge}
                  onClick={() => navigate(`/analisis/oportunidades/${badge}`)}
                  style={{
                    display:'flex', alignItems:'center', gap:10, background:'none',
                    border:'none', padding:0, cursor:'pointer', textAlign:'left',
                    opacity: isWarning ? 0.85 : 1,
                  }}
                >
                  <span style={{ fontSize:'.72rem', color:'var(--t2)', width:180, flexShrink:0 }}>
                    {meta?.label || badge}
                  </span>
                  <div style={{ flex:1, background:'var(--s3)', borderRadius:4, height:8, overflow:'hidden' }}>
                    <div style={{
                      height:'100%', borderRadius:4,
                      width:`${Math.min(100, pct*5)}%`,
                      background: meta?.color || 'var(--p)',
                    }}/>
                  </div>
                  <span style={{ fontSize:'.7rem', color:'var(--t4)', width:80, textAlign:'right' }}>
                    {count} ({pct.toFixed(1)}%)
                  </span>
                </button>
              );
            })}
          </div>
        ) : (
          <div style={{ color:'var(--t4)', fontSize:'.78rem' }}>Sin datos de badges aún.</div>
        )}
      </div>
    </div>
  );
}
