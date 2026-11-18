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

const productos = n => `${n.toLocaleString('es-AR')} ${n === 1 ? 'producto' : 'productos'}`;

const VIEW_MODE_KEY = 'picks:viewMode';

// Storage can throw (blocked or full); an unknown stored value falls back to the carousel.
function leerModo() {
  try {
    return localStorage.getItem(VIEW_MODE_KEY) === 'list' ? 'list' : 'carousel';
  } catch {
    return 'carousel';
  }
}

// Category detail body: header (title, count · mediana, tagline), back control and the products. Rendered by
// PicksPanel for /picks/<rubro>/<categoria>.
// The view shows EVERY product of the category, best first — the curated highlights followed by keyset pages (the
// backend's opaque `nextCursor`, never an offset) loaded as a sentinel scrolls into view — either as one carousel or
// as one list, never both. The mode is a per-viewer preference kept in localStorage, like /favoritos. `pickFilters` must be the same
// rubro/gymrat/genero the summary `cat` was fetched with, so the pages describe the same set.
// `subChips` (optional) lists sub-categories to filter by. The sub is sent to the backend (`subCategoria`), which filters
// the whole ranking before paging; with a sub active the curated highlights are not shown and the header counts that sub.
// `migas` (optional) are the breadcrumb segments before the category, `{ label, onClick }` each: Picks › Rubro › Categoría.
// The bar holding them and the back control is sticky, so the way out stays reachable while the list scrolls.
// `sub` / `onSubChange` make the chip selection controlled (PicksPanel keeps it in the URL); without them the view keeps it itself.
export default function CategoryPicksView({ cat, onBack, onProductClick, pickFilters, subChips, migas, sub: subControlado, onSubChange }) {
  const [subLocal, setSubLocal] = useState('');
  const controlado = onSubChange !== undefined;
  const sub = controlado ? (subControlado ?? '') : subLocal;
  const setSub = controlado ? onSubChange : setSubLocal;
  const [viewMode, setViewMode] = useState(leerModo);
  const elegirModo = modo => {
    setViewMode(modo);
    try { localStorage.setItem(VIEW_MODE_KEY, modo); } catch { /* still works for this session */ }
  };

  // One list per (category, filters, sub): the server filters by sub, so lists of different subs never mix. State
  // carrying a different key than the current one reads as empty, which also drops a cursor issued for another sub.
  const filtros = pickFilters || { rubro: '', gymrat: false, genero: '' };
  const filtrosKey = `${filtros.rubro}|${filtros.gymrat}|${filtros.genero}`;
  const listaKey = `${cat.categoria}|${filtrosKey}|${sub}`;
  const [lista, setLista] = useState({ key: listaKey, extra: [], cursor: null, fin: false, total: null });
  const vigente = lista.key === listaKey ? lista : { key: listaKey, extra: [], cursor: null, fin: false, total: null };
  const { extra, cursor, fin, total } = vigente;
  const cargando = useRef(null);
  // State, not a ref: the sentinel moves between carousel items as pages arrive, and the observer must follow it.
  const [sentinel, setSentinel] = useState(null);

  useEffect(() => { setSubLocal(''); }, [cat.categoria, filtrosKey]);

  const cargarMas = useCallback(async () => {
    if (cargando.current === listaKey) return;
    cargando.current = listaKey;
    try {
      const page = await fetchMejoresPicks(cat.categoria, { ...filtros, subCategoria: sub, cursor });
      setLista(prev => {
        const base = prev.key === listaKey ? prev : { key: listaKey, extra: [], cursor: null, fin: false, total: null };
        // With a sub active the curated highlights (cat.picks, unfiltered) are not shown, so only the pages dedupe.
        const vistos = new Set([...(sub ? [] : cat.picks || []), ...base.extra].map(p => p.url));
        const nextCursor = page?.nextCursor || null;
        return {
          key: listaKey,
          extra: [...base.extra, ...(page?.picks || []).filter(p => !vistos.has(p.url))],
          cursor: nextCursor,
          fin: !nextCursor,
          total: typeof page?.total === 'number' ? page.total : base.total,
        };
      });
    } catch {
      // A failed page leaves the sentinel armed; scrolling again retries it.
    } finally {
      if (cargando.current === listaKey) cargando.current = null;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listaKey, cursor]);

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

  // With a sub active the list is exactly what the server returned for it (its best products come first anyway).
  const todos = sub ? extra : [...(cat.picks || []), ...extra];
  const firstPick = todos[0];
  const centinela = fin ? null : <div ref={setSentinel} className="picks-sentinel" />;
  const modoBtn = (modo, etiqueta, Icono) => (
    <button type="button" className={`picks-view-btn${viewMode === modo ? ' active' : ''}`}
      aria-pressed={viewMode === modo} aria-label={etiqueta} title={etiqueta} onClick={() => elegirModo(modo)}>
      <Icono size={18} aria-hidden="true" />
    </button>
  );

  return (
    <div className="picks-catdetail">
      <nav className="picks-crumbbar" aria-label="Ubicación">
        <button onClick={onBack} className="picks-back-btn">
          ← Volver
        </button>
        <ol className="picks-crumbs">
          {(migas || []).map(m => (
            <li key={m.label}>
              <button type="button" className="picks-crumb" onClick={m.onClick}>{m.label}</button>
            </li>
          ))}
          <li aria-current="page" className="picks-crumb-actual">{cat.categoria}</li>
        </ol>
      </nav>

      <h2 className="picks-catdetail-title">
        {cat.categoria}
      </h2>
      <p className="picks-catdetail-meta">
        {productos(sub && total !== null ? total : (cat.count || 0))} · mediana ${fmt(cat.mediana)}
      </p>
      {firstPick && (
        <p className="picks-catdetail-tagline">
          "{tagline(cat.categoria, firstPick, cat.mediana)}"
        </p>
      )}

      <div className="picks-catdetail-toolbar">
        {subChips?.length > 0 && (
          <fieldset className="picks-subchips" aria-label="Subcategoría">
            {['', ...subChips].map(chip => {
              const valor = chip.value ?? chip;
              return (
                <button key={valor || 'todos'} type="button" onClick={() => setSub(valor)}
                  className={`picks-rubro-btn${sub === valor ? ' active' : ''}`}
                >{valor ? (chip.label ?? chip) : 'Todos'}</button>
              );
            })}
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
