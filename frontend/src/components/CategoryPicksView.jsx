import { useCallback, useEffect, useRef, useState } from 'react';
import { LayoutGrid, List } from 'lucide-react';
import { fetchMejoresPicks, fmt } from '../api';
import PickCard from './PickCard';
import CategoryPicksCarousel from './CategoryPicksCarousel';

// Generates tagline from statistical data. Shared by CategoryBanner (in
// PicksPanel's PicksGallery) and this component's header — single source of
// truth, was previously duplicated ad hoc inside PicksPanel.jsx.
export function tagline(cat, pick, mediana) {
  if (!pick) return '';
  const badge = pick.badge;
  const pctil = pick.pctil;
  const unitVal = pick.precioUnitario ?? pick.precio;
  const pct = mediana > 0 ? ((mediana - unitVal) / mediana * 100).toFixed(0) : null;

  if (badge === 'all_time_low')  return 'Nunca estuvo tan barato — mínimo histórico';
  if (badge === 'verified_deal') return 'Descuento verificado estadísticamente';
  if (pct && pct >= 25)  return `Un ${pct}% más barato que la media de ${cat}`;
  if (pct && pct >= 10)  return `Por debajo de la media — buena relación precio/calidad`;
  if (pctil && pctil <= 15) return `Percentil ${pctil}° — entre los más accesibles`;
  if (pick.segment === 'premium') return 'La opción premium más accesible ahora mismo';
  return `El mejor precio/calidad en ${cat} en este momento`;
}

const VIEW_MODE_KEY = 'picks:viewMode';

// Storage can throw (blocked or full); an unknown stored value falls back to the carousel.
function leerModo() {
  try {
    return localStorage.getItem(VIEW_MODE_KEY) === 'list' ? 'list' : 'carousel';
  } catch {
    return 'carousel';
  }
}

// Shared detail body: header (title, count · mediana, tagline), back control,
// and the products. Consumed by BOTH PicksPanel's in-panel CatDetail
// and the routed /picks/:categoria page (CategoryPicksPage) so both entries
// render one identical body (design doc ADR-2).
// The view shows EVERY product of the category, best first — the curated highlights followed by keyset pages (the
// backend's opaque `nextCursor`, never an offset) loaded as a sentinel scrolls into view — either as one carousel or
// as one list, never both. The mode is a per-viewer preference kept in localStorage, like /favoritos. `pickFilters` must be the same
// rubro/gymrat/genero the summary `cat` was fetched with, so the pages describe the same set.
// `subChips` (optional) lists sub-categories to filter by; picks carry `subCategoria` when the product has one.
export default function CategoryPicksView({ cat, onBack, onProductClick, pickFilters, subChips }) {
  const [sub, setSub] = useState('');
  const delFiltro = lista => (sub ? lista.filter(p => p.subCategoria === sub) : lista);
  const firstPick = delFiltro(cat.picks || [])[0];
  const [viewMode, setViewMode] = useState(leerModo);
  const elegirModo = modo => {
    setViewMode(modo);
    try { localStorage.setItem(VIEW_MODE_KEY, modo); } catch { /* still works for this session */ }
  };

  const [extra, setExtra] = useState([]);
  const [cursor, setCursor] = useState(null);
  const [fin, setFin] = useState(false);
  const cargando = useRef(false);
  // State, not a ref: the sentinel moves between carousel items as pages arrive, and the observer must follow it.
  const [sentinel, setSentinel] = useState(null);
  const filtros = pickFilters || { rubro: '', gymrat: false, genero: '' };
  const filtrosKey = `${filtros.rubro}|${filtros.gymrat}|${filtros.genero}`;

  useEffect(() => {
    setExtra([]); setCursor(null); setFin(false); cargando.current = false; setSub('');
  }, [cat.categoria, filtrosKey]);

  const cargarMas = useCallback(async () => {
    if (cargando.current) return;
    cargando.current = true;
    try {
      const page = await fetchMejoresPicks(cat.categoria, { ...filtros, cursor });
      setExtra(prev => {
        const vistos = new Set([...(cat.picks || []), ...prev].map(p => p.url));
        return [...prev, ...(page?.picks || []).filter(p => !vistos.has(p.url))];
      });
      setCursor(page?.nextCursor || null);
      if (!page?.nextCursor) setFin(true);
    } catch {
      // A failed page leaves the sentinel armed; scrolling again retries it.
    } finally {
      cargando.current = false;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cat.categoria, filtrosKey, cursor]);

  useEffect(() => {
    if (fin) return;
    const el = sentinel;
    if (!el) return;
    const observer = new IntersectionObserver(
      entries => { if (entries[0].isIntersecting) cargarMas(); },
      { rootMargin: '600px', threshold: 0 }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [fin, cargarMas, sentinel]);

  const todos = delFiltro([...(cat.picks || []), ...extra]);
  const centinela = fin ? null : <div ref={setSentinel} className="picks-sentinel" />;
  const modoBtn = (modo, etiqueta, Icono) => (
    <button type="button" className={`picks-view-btn${viewMode === modo ? ' active' : ''}`}
      aria-pressed={viewMode === modo} aria-label={etiqueta} title={etiqueta} onClick={() => elegirModo(modo)}>
      <Icono size={18} aria-hidden="true" />
    </button>
  );

  return (
    <div className="picks-catdetail">
      <button onClick={onBack} className="picks-back-btn">
        ← Volver
      </button>

      <h2 className="picks-catdetail-title">
        {cat.categoria}
      </h2>
      <p className="picks-catdetail-meta">
        {(cat.count||0).toLocaleString('es-AR')} productos · mediana ${fmt(cat.mediana)}
      </p>
      {firstPick && (
        <p className="picks-catdetail-tagline">
          "{tagline(cat.categoria, firstPick, cat.mediana)}"
        </p>
      )}

      <div className="picks-catdetail-toolbar">
        {subChips?.length > 0 && (
          <fieldset className="picks-subchips" aria-label="Subcategoría">
            {['', ...subChips].map(s => (
              <button key={s || 'todos'} type="button" onClick={() => setSub(s)}
                className={`picks-rubro-btn${sub === s ? ' active' : ''}`}
              >{s || 'Todos'}</button>
            ))}
          </fieldset>
        )}
        <fieldset className="picks-view-toggle" aria-label="Modo de vista">
          {modoBtn('carousel', 'Vista carrusel', LayoutGrid)}
          {modoBtn('list', 'Vista lista', List)}
        </fieldset>
      </div>

      {todos.length === 0 && fin && (
        <div className="picks-empty">
          {sub ? 'Sin productos en esta subcategoría todavía.' : 'Todavía no hay productos para esta categoría.'}
        </div>
      )}

      {todos.length > 0 && viewMode === 'carousel' && (
        <CategoryPicksCarousel
          title={cat.categoria}
          items={todos}
          trailing={centinela}
          renderItem={pick => (
            <PickCard pick={pick} mediana={cat.mediana} onClick={onProductClick}/>
          )}
        />
      )}

      {todos.length > 0 && viewMode === 'list' && (
        <div className="picks-grid">
          {todos.map(pick => (
            <PickCard key={pick.url} pick={pick} mediana={cat.mediana} onClick={onProductClick}/>
          ))}
        </div>
      )}
      {(todos.length === 0 || viewMode === 'list') && centinela}
    </div>
  );
}
