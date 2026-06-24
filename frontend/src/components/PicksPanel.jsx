import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { fetchMejores, fmt, BADGE_LABELS } from '../api';
import { TIPO_META, SEMANTIC } from '../lib/colors';

const RUBROS = [
  { k:'',             icon:'🛍', l:'Todos'        },
  { k:'indumentaria', icon:'👕', l:'Indumentaria' },
  { k:'tecnologia',   icon:'💻', l:'Tecnología'   },
  { k:'suplementos',  icon:'💊', l:'Suplementos'  },
];

const INITIAL_BATCH = 9;
const BATCH_STEP     = 9;

// Genera tagline a partir de datos estadísticos
function tagline(cat, pick, mediana) {
  if (!pick) return '';
  const badge = pick.badge;
  const pctil = pick.pctil;
  const pct = mediana > 0 ? ((mediana - pick.precio) / mediana * 100).toFixed(0) : null;

  if (badge === 'precio_historico_bajo') return 'Nunca estuvo tan barato — mínimo histórico';
  if (badge === 'oferta_real')           return 'Descuento verificado estadísticamente';
  if (pct && pct >= 25)  return `Un ${pct}% más barato que la media de ${cat}`;
  if (pct && pct >= 10)  return `Por debajo de la media — buena relación precio/calidad`;
  if (pctil && pctil <= 15) return `Percentil ${pctil}° — entre los más accesibles`;
  if (pick.segment === 'premium') return 'La opción premium más accesible ahora mismo';
  return `El mejor precio/calidad en ${cat} en este momento`;
}

// ─── Imagen con fallback editorial (mirrors ProductCard.jsx onError pattern) ─
function CardImage({ img, alt }) {
  return (
    <>
      {img
        ? <img className="picks-card-img" src={img} alt={alt} loading="lazy"
               onError={e => { e.target.style.display = 'none'; e.target.nextSibling?.classList.remove('hidden'); }} />
        : null
      }
      <div className={`picks-card-placeholder ${img ? 'hidden' : ''}`}>
        <span>🛍</span>
      </div>
    </>
  );
}

// ─── Banner de categoría (única variante — full-width, mismo tamaño) ────────
function CategoryBanner({ cat, onClick, cardRef }) {
  const pick1 = cat.picks?.[0];
  const img   = cat.imgCat || pick1?.img || '';

  return (
    <div ref={cardRef} data-cat={cat.categoria}
      className="picks-card" onClick={() => onClick(cat)}>
      <CardImage img={img} alt={cat.categoria} />
      <div className="picks-card-overlay"/>
      <div className="picks-card-count">{(cat.count||0).toLocaleString('es-AR')}</div>
      <div className="picks-card-info">
        <div className="picks-card-name">{cat.categoria}</div>
        {pick1 && (
          <div className="picks-card-tagline">{tagline(cat.categoria, pick1, cat.mediana)}</div>
        )}
        {pick1 && (
          <div className="picks-card-price" style={{ color: SEMANTIC.positive }}>
            desde ${fmt(pick1.precio)}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Search bar (sticky, live filter) ────────────────────────────────────────
function CategorySearchBar({ value, onChange }) {
  return (
    <input
      className="picks-search"
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder="Buscar categoría..."
      aria-label="Buscar categoría"
    />
  );
}

// ─── Scroll-spy index ─────────────────────────────────────────────────────────
function CategoryIndex({ cats, activeCat, onJump }) {
  if (!cats.length) return null;
  return (
    <nav className="picks-index" aria-label="Índice de categorías">
      {cats.map(cat => (
        <button
          key={cat.categoria}
          type="button"
          className={`picks-index-item ${activeCat === cat.categoria ? 'active' : ''}`}
          onClick={() => onJump(cat.categoria)}
        >
          {cat.categoria}
        </button>
      ))}
    </nav>
  );
}

// ─── Gallery: hero + grid + search + scroll-spy, con reveal progresivo ──────
function PicksGallery({ cats, busq, onSearch, onSelectCat }) {
  const [visibleCount, setVisibleCount] = useState(INITIAL_BATCH);
  const [activeCat, setActiveCat]       = useState(null);

  const cardRefs   = useRef(new Map());
  const sentinelRef = useRef(null);

  const filtered = useMemo(() => (
    busq
      ? cats.filter(c => c.categoria.toLowerCase().includes(busq.toLowerCase()))
      : cats
  ), [cats, busq]);

  // Reset reveal cursor whenever the filtered set changes (new search query)
  useEffect(() => {
    setVisibleCount(INITIAL_BATCH);
  }, [filtered]);

  const visible  = filtered.slice(0, visibleCount);

  function setCardRef(catKey, el) {
    if (el) cardRefs.current.set(catKey, el);
    else cardRefs.current.delete(catKey);
  }

  // Sentinel observer — grows visibleCount, no-ops once everything is revealed
  useEffect(() => {
    if (visibleCount >= filtered.length) return;
    const el = sentinelRef.current;
    if (!el) return;

    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting) {
          setVisibleCount(v => Math.min(v + BATCH_STEP, filtered.length));
        }
      },
      { rootMargin: '600px', threshold: 0 }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [visibleCount, filtered.length]);

  // Scroll-spy observer — single shared observer over all currently-rendered cards
  useEffect(() => {
    const entries = Array.from(cardRefs.current.entries());
    if (!entries.length) return;

    const observer = new IntersectionObserver(
      (observedEntries) => {
        let best = null;
        for (const entry of observedEntries) {
          if (entry.isIntersecting && (!best || entry.intersectionRatio > best.intersectionRatio)) {
            best = entry;
          }
        }
        if (best) setActiveCat(best.target.dataset.cat);
      },
      { rootMargin: '-10% 0px -60% 0px', threshold: [0, .25, .5, .75, 1] }
    );

    entries.forEach(([, el]) => observer.observe(el));
    return () => observer.disconnect();
  }, [visible.length, filtered]);

  function handleJump(catKey) {
    const el = cardRefs.current.get(catKey);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  return (
    <div className="picks-immersive">
      <div className="picks-header">
        <div>
          <div className="picks-title">🏆 Mejor picks</div>
          <div className="picks-subtitle">El mejor de cada categoría según precio/calidad</div>
        </div>
        <CategorySearchBar value={busq} onChange={onSearch} />
      </div>

      <div className="picks-body">
        <div className="picks-main">
          {filtered.length === 0 ? (
            <div className="picks-empty">
              Sin resultados para "{busq}". Probá otra búsqueda.
            </div>
          ) : (
            <>
              <div className="picks-grid">
                {visible.map(cat => (
                  <CategoryBanner
                    key={cat.categoria}
                    cat={cat}
                    onClick={onSelectCat}
                    cardRef={el => setCardRef(cat.categoria, el)}
                  />
                ))}
              </div>
              {visibleCount < filtered.length && (
                <div ref={sentinelRef} className="picks-sentinel" />
              )}
            </>
          )}
        </div>

        <CategoryIndex cats={filtered} activeCat={activeCat} onJump={handleJump} />
      </div>
    </div>
  );
}

// ─── Vista de picks de una categoría ─────────────────────────────────────────
function CatDetail({ cat, onBack, onProductClick }) {
  return (
    <div style={{ padding:'1rem 1.25rem', maxWidth:680 }}>
      <button onClick={onBack} style={{
        background:'none', border:'none', cursor:'pointer',
        color:'var(--p2)', fontSize:'.8rem', marginBottom:12, padding:0,
        display:'flex', alignItems:'center', gap:5,
      }}>
        ← Volver
      </button>

      <h2 style={{ fontSize:'1.1rem', fontWeight:900, color:'var(--t1)', marginBottom:2 }}>
        {cat.categoria}
      </h2>
      <p style={{ fontSize:'.72rem', color:'var(--t4)', marginBottom:4 }}>
        {(cat.count||0).toLocaleString('es-AR')} productos · mediana ${fmt(cat.mediana)}
      </p>
      <p style={{ fontSize:'.78rem', color:'var(--p2)', marginBottom:'1.2rem', fontStyle:'italic' }}>
        "{tagline(cat.categoria, cat.picks?.[0], cat.mediana)}"
      </p>

      <div style={{ display:'flex', flexDirection:'column', gap:10 }}>
        {(cat.picks||[]).map((pick, i) => {
          const m = TIPO_META[pick.tipo] || TIPO_META.valor;
          const pctBajoMedia = cat.mediana > 0 && pick.precio > 0
            ? ((cat.mediana - pick.precio) / cat.mediana * 100).toFixed(0)
            : null;
          return (
            <div key={i}
              onClick={() => onProductClick(pick)}
              style={{
                display:'flex', gap:12, alignItems:'center',
                background:'var(--s2)', borderRadius:10, padding:'.75rem',
                border:`1.5px solid ${m.color}33`, cursor:'pointer',
                transition:'all .15s',
              }}
              onMouseOver={e => e.currentTarget.style.borderColor = m.color}
              onMouseOut={e => e.currentTarget.style.borderColor = `${m.color}33`}>

              {/* Rank icon */}
              <div style={{ fontSize:'1.5rem', flexShrink:0 }}>{m.icon}</div>

              {/* Image */}
              {pick.img && (
                <img src={pick.img} alt={pick.nombre} width="60" height="60"
                  style={{ objectFit:'cover', borderRadius:8, flexShrink:0 }}
                  onError={e => e.target.style.display='none'}/>
              )}

              {/* Info */}
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{ fontSize:'.6rem', fontWeight:700, color: m.color,
                               textTransform:'uppercase', letterSpacing:'.08em' }}>
                  {m.label}
                </div>
                <div style={{
                  fontSize:'.8rem', fontWeight:600, color:'var(--t1)', marginTop:2,
                  overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                }}>{pick.nombre}</div>
                <div style={{ fontSize:'.65rem', color:'var(--t4)', marginTop:2 }}>
                  {pick.sitio} · {pick.marca || ''}
                  {pick.badge && BADGE_LABELS[pick.badge] && (
                    <span style={{ color:'var(--p2)', marginLeft:5 }}>
                      · {BADGE_LABELS[pick.badge]}
                    </span>
                  )}
                </div>
                {pctBajoMedia && pctBajoMedia > 5 && (
                  <div style={{ fontSize:'.65rem', color: SEMANTIC.positive, marginTop:2 }}>
                    {pctBajoMedia}% por debajo de la mediana
                  </div>
                )}
              </div>

              {/* Price */}
              <div style={{ textAlign:'right', flexShrink:0 }}>
                <div style={{ fontSize:'.92rem', fontWeight:800, color:'var(--t1)' }}>
                  ${fmt(pick.precio)}
                </div>
                {pick.scoreP > 0 && (
                  <div style={{ fontSize:'.6rem', color:'var(--t4)' }}>
                    score {pick.scoreP}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── PicksPanel ───────────────────────────────────────────────────────────────
export default function PicksPanel({ onProductClick }) {
  const [cats,    setCats]    = useState([]);
  const [rubro,   setRubro]   = useState('');
  const [loading, setLoading] = useState(false);
  const [selCat,  setSelCat]  = useState(null); // selected category for detail view
  const [busq,    setBusq]    = useState('');

  const load = useCallback(async (r) => {
    setLoading(true); setSelCat(null);
    const data = await fetchMejores(r);
    setCats(Array.isArray(data) ? data : []);
    setLoading(false);
  }, []);

  useEffect(() => { load(rubro); }, [rubro]);

  if (selCat) return (
    <div style={{ flex:1, overflowY:'auto' }}>
      <CatDetail cat={selCat} onBack={() => setSelCat(null)}
                 onProductClick={onProductClick}/>
    </div>
  );

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Rubro tabs — stays outside the immersive theme so it reads as
          dashboard chrome, not part of the editorial gallery surface */}
      <div style={{
        padding:'.5rem 1.25rem', background:'var(--s1)',
        borderBottom:'1px solid var(--bd)',
        display:'flex', gap:5, flexWrap:'wrap',
      }}>
        {RUBROS.map(r => (
          <button key={r.k} onClick={() => setRubro(r.k)} style={{
            padding:'4px 10px', borderRadius:16, border:'none',
            cursor:'pointer', fontSize:'.72rem', fontWeight:700,
            background: rubro===r.k ? 'var(--p)' : 'var(--s2)',
            color: rubro===r.k ? '#fff' : 'var(--t4)',
          }}>{r.icon} {r.l}</button>
        ))}
      </div>

      <div style={{ flex:1, overflowY:'auto' }}>
        {loading && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Calculando mejores picks...
          </div>
        )}
        {!loading && cats.length === 0 && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Sin datos. Ejecutá un scraping primero.
          </div>
        )}
        {!loading && cats.length > 0 && (
          <PicksGallery cats={cats} busq={busq} onSearch={setBusq} onSelectCat={setSelCat} />
        )}
      </div>
    </div>
  );
}
