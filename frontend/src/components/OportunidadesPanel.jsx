import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { fetchData, fmt } from '../api';
import { BADGE_META, SEMANTIC } from '../lib/colors';
import { EXCLUIR_CATEGORIAS, rubroDeQuery, rutaOportunidades } from '../lib/oportunidades';
import { RUBROS } from '../lib/rubros';

// ─── Top deals preview (capped) ───────────────────────────────────────────
// A capped preview: the full listing lives behind the /analisis/oportunidades/:badge
// drill-down. Each badge is its own /api/data read, ranked by real discount and scoped to the
// selected rubro, so the preview shows what the drill-down would show first.
//
// below_market also ranks by desc_pct: the only other badge-relevant order, `composite`/`ml_score`,
// sorts DESC over a score where 0 is the cheapest (ml_score DESC lists the LEAST cheap first),
// so no existing order means "cheapest against the market".
const PREVIEW_BADGES = ['all_time_low', 'verified_deal', 'below_market'];
const PREVIEW_ORDEN = { all_time_low: 'desc_pct', verified_deal: 'desc_pct', below_market: 'desc_pct' };
const PREVIEW_SIZE = 8;

const TopDeals = ({ grupos, onProductClick }) => {
  const sections = PREVIEW_BADGES.map(b => [b, grupos[b] || []]).filter(([, ps]) => ps.length > 0);
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
              {ps.map((p, i) => (
                <button key={p.url || i} type="button" className="btn-reset" onClick={() => onProductClick(p)}
                     style={{
                       flex:'0 0 140px', background:'var(--s2)', borderRadius:8,
                       border:'1px solid var(--bd)', cursor:'pointer',
                       transition:'border-color .15s', overflow:'hidden',
                     }}
                     onMouseOver={e=>e.currentTarget.style.borderColor='var(--p)'}
                     onFocus={e=>e.currentTarget.style.borderColor='var(--p)'}
                     onMouseOut={e=>e.currentTarget.style.borderColor='var(--bd)'}
                     onBlur={e=>e.currentTarget.style.borderColor='var(--bd)'}
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
                </button>
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
  const [searchParams, setSearchParams] = useSearchParams();
  const rubro = rubroDeQuery(searchParams);
  const navigate = useNavigate();

  // One read per preview badge (size 8) gives its products AND its count; the other badges
  // and the no-badge base only need `meta.total`, hence size 1. Every read carries the same
  // rubro + exclusion, so a bar and the list behind it agree.
  useEffect(() => {
    let vigente = true;
    setRes(null);
    const alcance = { rubro, excluirCategoria: EXCLUIR_CATEGORIAS };
    const keys = Object.keys(BADGE_META);
    const leer = async () => {
      const [base, ...porBadge] = await Promise.all([
        fetchData({ ...alcance, size: 1 }),
        ...keys.map(badge => PREVIEW_BADGES.includes(badge)
          ? fetchData({ badge, ...alcance, orden: PREVIEW_ORDEN[badge], size: PREVIEW_SIZE })
          : fetchData({ badge, ...alcance, size: 1 })),
      ]);
      if (!base || porBadge.some(r => !r)) return { error: true };
      const grupos = {};
      const counts = {};
      keys.forEach((badge, i) => {
        counts[badge] = porBadge[i].meta?.total || 0;
        if (PREVIEW_BADGES.includes(badge)) grupos[badge] = porBadge[i].productos || [];
      });
      return { total: base.meta?.total || 0, grupos, counts };
    };
    leer().catch(() => ({ error: true })).then(r => { if (vigente) setRes(r); });
    return () => { vigente = false; };
  }, [rubro]);

  const elegirRubro = key =>
    setSearchParams(key ? { rubro: key } : {}, { replace: true });

  const chips = (
    <div role="group" aria-label="Rubro" style={{ display:'flex', flexWrap:'wrap', gap:6 }}>
      {RUBROS.map(r => (
        <button key={r.key} type="button" onClick={() => elegirRubro(r.key)}
          aria-pressed={rubro === r.key}
          className={`picks-rubro-btn${rubro === r.key ? ' active' : ''}`}
        >{r.icon} {r.label}</button>
      ))}
    </div>
  );

  let cuerpo;
  if (res === null) {
    cuerpo = (
      <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        Cargando…
      </div>
    );
  } else if (res.error) {
    cuerpo = (
      <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        No se pudieron cargar las oportunidades. Reintentá en unos segundos.
      </div>
    );
  } else if (res.total === 0) {
    cuerpo = (
      <div style={{ color: SEMANTIC.warn, textAlign:'center', padding:'3rem', fontSize:'.9rem' }}>
        Sin productos en este rubro. Ejecutá un scraping para ver las oportunidades del catálogo.
      </div>
    );
  } else {
    const entries = Object.entries(res.counts).filter(([, n]) => n > 0).sort((a,b) => b[1]-a[1]);
    const hayPreview = PREVIEW_BADGES.some(b => res.grupos[b]?.length);
    cuerpo = (
      <>
        <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
          <TopDeals grupos={res.grupos} onProductClick={onProductClick}/>
          {!hayPreview && (
            <div style={{ color:'var(--t4)', fontSize:'.8rem', textAlign:'center', padding:'2rem' }}>
              Sin oportunidades en este rubro todavía.
            </div>
          )}
        </div>

        <div style={{ background:'var(--s1)', border:'1px solid var(--bd)', borderRadius:12, padding:'1rem' }}>
          <div className="detail-section-title">📊 Distribución de badges</div>
          {entries.length > 0 ? (
            <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
              {entries.map(([badge, count]) => {
                const meta = BADGE_META[badge];
                const pct  = count/res.total*100;
                const isWarning = meta?.tier === 'warning';
                return (
                  <button key={badge}
                    onClick={() => navigate(rutaOportunidades(rubro, badge))}
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
      </>
    );
  }

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:'1.5rem' }}>
      {chips}
      {cuerpo}
    </div>
  );
}
