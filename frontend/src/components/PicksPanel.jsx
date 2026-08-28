import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { fetchMejores, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';
import { CategoryCard } from './ui/category-card';
import { RubroCard } from './ui/rubro-card';
import CategoryPicksView, { tagline } from './CategoryPicksView';
import CategoryPicksCarousel from './CategoryPicksCarousel';
import { RUBROS } from '../lib/rubros';


const INITIAL_BATCH = 9;
const BATCH_STEP     = 9;

// Rubro entry step: every real rubro of the vocabulary (RUBROS minus its
// neutral '' = Todos, which stays reachable from the tab bar inside the
// gallery). HSL triples, not finished colors — see ui/rubro-card.jsx.
const RUBRO_THEME = {
  indumentaria: '14 62% 47%',
  tecnologia:   '222 40% 30%',
  suplementos:  '147 46% 33%',
  oficina:      '36 60% 30%',
};
const RUBROS_ENTRADA = RUBROS.filter(r => r.key);

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

/** Cover image + counters a rubro card shows, derived from its own picks. */
function resumenRubro(cats) {
  const lista = cats || [];
  const conImagen = lista.find(c => c.imgCat || c.picks?.[0]?.img);
  return {
    img: conImagen ? (conImagen.imgCat || conImagen.picks[0].img) : '',
    categorias: lista.length,
    productos: lista.reduce((acc, c) => acc + (c.count || 0), 0),
  };
}

// ─── Rubro carousel: the /picks entry, one step before the full gallery ──────
function RubroCarousel({ byRubro, cargando, onSelect }) {
  return (
    <div className="picks-immersive">
      <div className="picks-header">
        <div className="picks-title">🏆 Mejor picks</div>
        <div className="picks-subtitle">Elegí un rubro para ver todas sus categorías</div>
      </div>

      <div className="picks-main">
        <CategoryPicksCarousel
          title="Rubros"
          subtitle={cargando ? 'Cargando picks...' : 'El mejor de cada categoría según precio/calidad'}
          items={RUBROS_ENTRADA}
          className="picks-rubro-carousel"
          renderItem={r => {
            const { img, categorias, productos } = resumenRubro(byRubro[r.key]);
            return (
              <RubroCard
                imageUrl={img}
                icon={r.icon}
                title={r.label}
                themeColor={RUBRO_THEME[r.key]}
                stats={categorias
                  ? `${categorias} categorías · ${productos.toLocaleString('es-AR')} productos`
                  : (cargando ? 'Cargando...' : 'Sin picks todavía')}
                onClick={() => onSelect(r.key)}
              />
            );
          }}
        />
      </div>
    </div>
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
  // rubro === null is the carousel step; a string (including '' = Todos) is
  // the gallery for that rubro. Cats are cached per rubro so stepping back and
  // forth between the carousel and a gallery costs no request.
  const [byRubro, setByRubro] = useState({});
  const [rubro,   setRubro]   = useState(null);
  const [loading, setLoading] = useState(false);
  const [resumenListo, setResumenListo] = useState(false);
  const [selCat,  setSelCat]  = useState(null);
  const [busq,    setBusq]    = useState('');

  // Entry step needs every rubro at once (cover image + counters per card).
  useEffect(() => {
    let cancelled = false;
    Promise.all(RUBROS_ENTRADA.map(r =>
      fetchMejores(r.key).then(d => [r.key, Array.isArray(d) ? d : []])
    )).then(pares => {
      if (cancelled) return;
      setByRubro(prev => ({ ...Object.fromEntries(pares), ...prev }));
      setResumenListo(true);
    });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (rubro === null || byRubro[rubro]) return;
    let cancelled = false;
    setLoading(true);
    fetchMejores(rubro).then(d => {
      if (cancelled) return;
      setByRubro(prev => ({ ...prev, [rubro]: Array.isArray(d) ? d : [] }));
      setLoading(false);
    });
    return () => { cancelled = true; };
  }, [rubro, byRubro]);

  const abrirRubro = useCallback(key => {
    setSelCat(null);
    setBusq('');
    setRubro(key);
  }, []);

  if (selCat) return (
    <div className="picks-scroll">
      <CategoryPicksView cat={selCat} onBack={() => setSelCat(null)}
                          onProductClick={onProductClick}/>
    </div>
  );

  if (rubro === null) return (
    <div className="picks-panel">
      <div className="picks-scroll">
        <RubroCarousel byRubro={byRubro} cargando={!resumenListo} onSelect={abrirRubro} />
      </div>
    </div>
  );

  const cats = byRubro[rubro] || [];

  return (
    <div className="picks-panel">
      {/* Rubro tabs — stays outside the immersive theme so it reads as
          dashboard chrome, not part of the editorial gallery surface */}
      <div className="picks-rubro-bar">
        <button onClick={() => setRubro(null)} className="picks-rubro-back"
          aria-label="Volver a los rubros">← Rubros</button>
        {RUBROS.map(r => (
          <button key={r.key} onClick={() => abrirRubro(r.key)}
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
