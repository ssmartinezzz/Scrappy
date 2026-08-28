import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { fetchMejores, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';
import { CategoryCard } from './ui/category-card';
import CategoryPicksView, { tagline } from './CategoryPicksView';
import { RUBROS } from '../lib/rubros';


const INITIAL_BATCH = 9;
const BATCH_STEP     = 9;

// ─── Banner de categoría (shadcn-style CategoryCard — image top, content below) ─
function CategoryBanner({ cat, onClick }) {
  const pick1 = cat.picks?.[0];
  const img   = cat.imgCat || pick1?.img || '';

  return (
    <CategoryCard
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
    </CategoryCard>
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

// ─── Gallery: hero + grid + search, con reveal progresivo ────────────────────
function PicksGallery({ cats, busq, onSelectCat }) {
  const [visibleCount, setVisibleCount] = useState(INITIAL_BATCH);
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

  const visible = filtered.slice(0, visibleCount);

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

  return (
    <div className="picks-immersive">
      <div className="picks-header">
        <div className="picks-title">🏆 Mejor picks</div>
        <div className="picks-subtitle">El mejor de cada categoría según precio/calidad</div>
      </div>

      <div className="picks-main">
        {filtered.length === 0 ? (
          <div className="picks-empty">
            Sin resultados para "{busq}". Probá otra búsqueda.
          </div>
        ) : (
          <>
            <div className="picks-grid">
              {visible.map(cat => (
                <CategoryBanner key={cat.categoria} cat={cat} onClick={onSelectCat} />
              ))}
            </div>
            {visibleCount < filtered.length && (
              <div ref={sentinelRef} className="picks-sentinel" />
            )}
          </>
        )}
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
          <button key={r.key} onClick={() => setRubro(r.key)}
            className={`picks-rubro-btn${rubro===r.key ? ' active' : ''}`}
          >{r.icon} {r.label}</button>
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
