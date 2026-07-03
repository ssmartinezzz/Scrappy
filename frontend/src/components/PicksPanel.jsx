import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { fetchMejores, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';
import { ProductCard } from './ui/product-card';
import CategoryPicksView, { tagline } from './CategoryPicksView';

const RUBROS = [
  { k:'',             icon:'🛍', l:'Todos'        },
  { k:'indumentaria', icon:'👕', l:'Indumentaria' },
  { k:'tecnologia',   icon:'💻', l:'Tecnología'   },
  { k:'suplementos',  icon:'💊', l:'Suplementos'  },
];

const INITIAL_BATCH = 9;
const BATCH_STEP     = 9;

// ─── Banner de categoría (shadcn-style ProductCard — image top, content below) ─
function CategoryBanner({ cat, onClick, cardRef }) {
  const pick1 = cat.picks?.[0];
  const img   = cat.imgCat || pick1?.img || '';

  return (
    <ProductCard
      ref={cardRef}
      data-cat={cat.categoria}
      imageUrl={img}
      title={cat.categoria}
      subtitle={tagline(cat.categoria, pick1, cat.mediana)}
      count={cat.count || 0}
      onClick={() => onClick(cat)}
    >
      {pick1 && (
        <p className="mt-2 font-mono text-sm font-extrabold" style={{ color: SEMANTIC.positive }}>
          desde ${fmt(pick1.precio)}
          {pick1.esPack && (
            <span className="card-price-unit"> · ${fmt(pick1.precioUnitario)} c/u · x{pick1.cantidadUnidades}</span>
          )}
        </p>
      )}
    </ProductCard>
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
function PicksGallery({ cats, busq, onSelectCat }) {
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
        <div className="picks-title">🏆 Mejor picks</div>
        <div className="picks-subtitle">El mejor de cada categoría según precio/calidad</div>
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
    <div className="picks-scroll">
      <CategoryPicksView cat={selCat} onBack={() => setSelCat(null)}
                          onProductClick={onProductClick}/>
    </div>
  );

  return (
    <div className="picks-panel">
      {/* Rubro tabs — stays outside the immersive theme so it reads as
          dashboard chrome, not part of the editorial gallery surface */}
      <div className="picks-rubro-bar">
        {RUBROS.map(r => (
          <button key={r.k} onClick={() => setRubro(r.k)}
            className={`picks-rubro-btn${rubro===r.k ? ' active' : ''}`}
          >{r.icon} {r.l}</button>
        ))}
        <CategorySearchBar value={busq} onChange={setBusq} />
      </div>

      <div className="picks-scroll">
        {loading && (
          <div className="picks-state-msg">
            Calculando mejores picks...
          </div>
        )}
        {!loading && cats.length === 0 && (
          <div className="picks-state-msg">
            Sin datos. Ejecutá un scraping primero.
          </div>
        )}
        {!loading && cats.length > 0 && (
          <PicksGallery cats={cats} busq={busq} onSelectCat={setSelCat} />
        )}
      </div>
    </div>
  );
}
