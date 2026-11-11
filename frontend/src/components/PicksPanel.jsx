import { useEffect, useState, useCallback, useRef, useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { fetchMejores, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';
import { CategoryCard } from './ui/category-card';
import { RubroCard } from './ui/rubro-card';
import CategoryPicksView, { tagline } from './CategoryPicksView';
import CategoryPicksCarousel from './CategoryPicksCarousel';
import { RUBROS } from '../lib/rubros';
import { PERFUMERIA_PROXIMAMENTE, PERFUME_SUBCATEGORIAS } from '../lib/perfumeria';
import { canonicalFromSlug, slugify } from '../lib/cat';


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
  construccion: '20 18% 38%',
  hogar:        '340 35% 42%',
  deporte:      '203 62% 34%',
  juguetes:     '45 78% 38%',
  libros:       '262 38% 40%',
  perfumeria:   '322 38% 42%',
  gymrat:       '0 68% 38%',
};
const RUBROS_ENTRADA = RUBROS.filter(r => r.key);

// Gymrat is a boolean filter, not a rubro: this key only indexes
// the local cache/state and is never sent as the `rubro` param.
const GYMRAT = 'gymrat';
const GYMRAT_ENTRADA = { key: GYMRAT, icon: '🏋', label: 'Gymrat' };
const ENTRADAS = [...RUBROS_ENTRADA, GYMRAT_ENTRADA];

const GENEROS = [{ key: 'hombre', label: 'Hombre' }, { key: 'mujer', label: 'Mujer' }];

// Gymrat's entry summary is every gender (key 'gymrat'); its gallery is one gender (key 'gymrat:hombre').
const claveCache = (rubro, genero) => (rubro === GYMRAT && genero ? `${GYMRAT}:${genero}` : rubro);

// The URL is the panel's state: /picks · /picks/<rubro> · /picks/<rubro>/<categoria> · /picks/gymrat/<genero>[/<categoria>].
// The neutral rubro '' is spelled `todos`. A first segment that is no rubro is an old /picks/<categoria> link.
const TODOS = 'todos';
const RUBRO_KEYS_ENTRADA = new Set(RUBROS_ENTRADA.map(r => r.key));

function leerRuta(resto) {
  const [primero, segundo, tercero] = (resto || '').split('/').filter(Boolean);
  if (!primero) return { rubro: null };
  if (primero === TODOS) return { rubro: '', catSlug: segundo };
  if (primero === GYMRAT) {
    if (GENEROS.some(g => g.key === segundo)) return { rubro: GYMRAT, genero: segundo, catSlug: tercero };
    return { rubro: GYMRAT, catSlug: segundo };
  }
  if (RUBRO_KEYS_ENTRADA.has(primero)) return { rubro: primero, catSlug: segundo };
  return { rubro: undefined, legado: primero };
}

function rutaDe(rubro, genero, categoria) {
  const partes = ['/picks', rubro === '' ? TODOS : rubro];
  if (rubro === GYMRAT && (genero || categoria)) partes.push(genero || GENEROS[0].key);
  if (categoria) partes.push(slugify(categoria));
  return partes.join('/');
}

const cargarPicks = (key, genero) => {
  if (key !== GYMRAT) return fetchMejores(key);
  return fetchMejores('', genero ? { gymrat: true, genero } : { gymrat: true });
};

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
        <div className="picks-subtitle">Elegí un rubro para ver todas sus categorías, o Gymrat para todo lo de gym</div>
      </div>

      <div className="picks-main">
        <CategoryPicksCarousel
          title="Rubros"
          subtitle={cargando ? 'Cargando picks...' : 'El mejor de cada categoría según precio/calidad'}
          items={ENTRADAS}
          className="picks-rubro-carousel"
          renderItem={r => {
            const { img, categorias, productos } = resumenRubro(byRubro[r.key]);
            let picksStats = `${categorias} categorías · ${productos.toLocaleString('es-AR')} productos`;
            if (!categorias) picksStats = cargando ? 'Cargando...' : 'Sin picks todavía';
            return (
              <RubroCard
                imageUrl={img}
                icon={r.icon}
                title={r.label}
                themeColor={RUBRO_THEME[r.key]}
                stats={picksStats}
                onClick={() => onSelect(r.key)}
              />
            );
          }}
        />
      </div>
    </div>
  );
}

// Greyed placeholder for a category that does not exist yet (see PERFUMERIA_PROXIMAMENTE). A div, not a button:
// nothing to open, nothing to focus.
function ProximamenteCard({ card }) {
  return (
    <div className="picks-proximamente" data-proximamente aria-disabled="true">
      <span className="picks-proximamente-icon" aria-hidden="true">{card.icon}</span>
      <span className="picks-proximamente-title">{card.label}</span>
      <span className="picks-proximamente-badge">Próximamente</span>
    </div>
  );
}

// ─── Gallery: hero + grid + search, con reveal progresivo ────────────────────
function PicksGallery({ cats, busq, onSelectCat, proximamente = [], subtitulo = 'El mejor de cada categoría según precio/calidad' }) {
  const [visibleCount, setVisibleCount] = useState(INITIAL_BATCH);
  const sentinelRef = useRef(null);

  const filtered = useMemo(() => (
    (busq
      ? cats.filter(c => c.categoria.toLowerCase().includes(busq.toLowerCase()))
      : [...cats]
    ).sort((a, b) => a.categoria.localeCompare(b.categoria, 'es', { sensitivity: 'base' }))
  ), [cats, busq]);

  // Reset reveal cursor whenever the filtered set changes (new search query)
  useEffect(() => {
    setVisibleCount(INITIAL_BATCH);
  }, [filtered]);

  const visible = filtered.slice(0, visibleCount);
  const proximos = busq
    ? proximamente.filter(c => c.label.toLowerCase().includes(busq.toLowerCase()))
    : proximamente;

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
        <div className="picks-subtitle">{subtitulo}</div>
      </div>

      <div className="picks-main">
        {filtered.length === 0 && proximos.length === 0 ? (
          <div className="picks-empty">
            Sin resultados para "{busq}". Probá otra búsqueda.
          </div>
        ) : (
          <>
            <div className="picks-grid">
              {visible.map(cat => (
                <CategoryBanner key={cat.categoria} cat={cat} onClick={onSelectCat} />
              ))}
              {visibleCount >= filtered.length && proximos.map(c => (
                <ProximamenteCard key={c.key} card={c} />
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

// ─── Data: every rubro summary + the cats of the open rubro, cached per key ──
function usePicksPorRubro(clave, rubro, genero) {
  const [byRubro, setByRubro] = useState({});
  const [resumenListo, setResumenListo] = useState(false);
  const pedidos = useRef(new Set());

  // Entry step needs every rubro at once (cover image + counters per card).
  useEffect(() => {
    let cancelled = false;
    Promise.all(ENTRADAS.map(r =>
      cargarPicks(r.key).then(d => [r.key, Array.isArray(d) ? d : []])
    )).then(pares => {
      if (cancelled) return;
      setByRubro(prev => ({ ...Object.fromEntries(pares), ...prev }));
      setResumenListo(true);
    }).catch(() => {
      // Unreachable backend: the entry cards stay in their loading state; a reload retries.
    });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (clave === null || byRubro[clave] || pedidos.current.has(clave)) return;
    pedidos.current.add(clave);
    cargarPicks(rubro, rubro === GYMRAT ? genero : '').then(d => {
      setByRubro(prev => ({ ...prev, [clave]: Array.isArray(d) ? d : [] }));
    }).catch(() => {
      // Unreachable backend: keep the loading state for this rubro; a reload retries.
    }).finally(() => pedidos.current.delete(clave));
  }, [clave, rubro, genero, byRubro]);

  return { byRubro, resumenListo };
}

// An old /picks/<categoria> link: find the rubro that owns it and move to its real path.
function useRedireccionLegado(legado, resumenListo, byRubro) {
  const navigate = useNavigate();
  const destino = useMemo(() => {
    if (!legado || !resumenListo) return null;
    const rubroDueno = RUBROS_ENTRADA.find(r => canonicalFromSlug(legado, byRubro[r.key]));
    return rubroDueno ? rutaDe(rubroDueno.key, '', canonicalFromSlug(legado, byRubro[rubroDueno.key]).categoria) : null;
  }, [legado, resumenListo, byRubro]);
  useEffect(() => {
    if (destino) navigate(destino, { replace: true });
  }, [destino, navigate]);
  return destino;
}

const pickFiltersDe = (rubro, genero) => (rubro === GYMRAT
  ? { rubro: '', gymrat: true, genero }
  : { rubro: rubro || '', gymrat: false, genero: '' });

// /picks/<rubro>/<categoria>: the detail, or a loading / not-found message while the slug cannot be resolved.
function DetalleCategoria({ cat, buscando, rubro, genero, onBack, onProductClick }) {
  if (cat) return (
    <div className="picks-scroll">
      <CategoryPicksView cat={cat} onBack={onBack}
                          onProductClick={onProductClick}
                          subChips={rubro === 'perfumeria' && cat.categoria === 'Perfume' ? PERFUME_SUBCATEGORIAS : undefined}
                          pickFilters={pickFiltersDe(rubro, genero)}/>
    </div>
  );
  if (buscando) return (
    <div className="picks-scroll">
      <div className="picks-state-msg">Cargando picks...</div>
    </div>
  );
  return (
    <div className="picks-scroll">
      <div className="picks-catdetail">
        <button onClick={onBack} className="picks-back-btn">← Volver</button>
        <div className="picks-state-msg">
          No encontramos esta categoría. Puede que ya no exista o que el enlace esté desactualizado.
        </div>
      </div>
    </div>
  );
}

// ─── PicksPanel ───────────────────────────────────────────────────────────────
export default function PicksPanel({ onProductClick }) {
  // rubro === null is the carousel step; a string (including '' = Todos) is the gallery for that rubro, and a
  // category slug on top opens its detail. All of it comes from the URL. Cats are cached per rubro so stepping
  // back and forth costs no request — App.jsx mounts this under ONE `picks/*` route so the cache survives.
  const { '*': resto } = useParams();
  const navigate = useNavigate();
  const { rubro, genero: generoRuta, catSlug, legado } = leerRuta(resto);
  const genero = generoRuta || GENEROS[0].key;
  const [busq, setBusq] = useState('');

  const clave = rubro == null ? null : claveCache(rubro, genero);
  const { byRubro, resumenListo } = usePicksPorRubro(clave, rubro, genero);
  const destinoLegado = useRedireccionLegado(legado, resumenListo, byRubro);

  const abrirRubro = useCallback(key => {
    setBusq('');
    navigate(rutaDe(key));
  }, [navigate]);

  const cats = clave === null ? [] : byRubro[clave];
  const loading = clave !== null && !cats;

  if (rubro === undefined) return (
    <DetalleCategoria buscando={!resumenListo || Boolean(destinoLegado)}
                      onBack={() => navigate('/picks')} onProductClick={onProductClick}/>
  );

  if (catSlug) return (
    <DetalleCategoria cat={canonicalFromSlug(catSlug, cats)} buscando={loading} rubro={rubro} genero={genero}
                      onBack={() => navigate(rutaDe(rubro, generoRuta))} onProductClick={onProductClick}/>
  );

  if (rubro === null) return (
    <div className="picks-panel">
      <div className="picks-scroll">
        <RubroCarousel byRubro={byRubro} cargando={!resumenListo} onSelect={abrirRubro} />
      </div>
    </div>
  );

  return (
    <div className="picks-panel">
      {/* Rubro tabs — stays outside the immersive theme so it reads as
          dashboard chrome, not part of the editorial gallery surface */}
      <div className="picks-rubro-bar">
        <button onClick={() => navigate('/picks')} className="picks-rubro-back"
          aria-label="Volver a los rubros">← Rubros</button>
        {RUBROS.map(r => (
          <button key={r.key} onClick={() => abrirRubro(r.key)}
            className={`picks-rubro-btn${rubro===r.key ? ' active' : ''}`}
          >{r.icon} {r.label}</button>
        ))}
        <button onClick={() => abrirRubro(GYMRAT)}
          className={`picks-rubro-btn${rubro===GYMRAT ? ' active' : ''}`}
        >{GYMRAT_ENTRADA.icon} {GYMRAT_ENTRADA.label}</button>
        {rubro === GYMRAT && (
          <div className="picks-genero" role="group" aria-label="Género">
            {GENEROS.map(g => (
              <button key={g.key} onClick={() => navigate(rutaDe(GYMRAT, g.key))}
                className={`picks-rubro-btn${genero===g.key ? ' active' : ''}`}
              >{g.label}</button>
            ))}
          </div>
        )}
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
          <PicksGallery cats={cats} busq={busq} onSelectCat={c => navigate(rutaDe(rubro, generoRuta, c.categoria))}
            proximamente={rubro === 'perfumeria' ? PERFUMERIA_PROXIMAMENTE : []}
            subtitulo={rubro === GYMRAT ? 'Lo mejor de las marcas de gym' : undefined} />
        )}
      </div>
    </div>
  );
}
