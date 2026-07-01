import { useEffect, useMemo, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { Sheet, SheetContent, SheetTitle } from './ui/sheet';
import { cn, sortByCountDesc } from '@/lib/utils';
import { BADGE_LABELS } from '../api';
import { SEG_COLORS, BADGE_COLORS, SEMANTIC } from '../lib/colors';

const SEGMENTOS = [
  { k:'budget',   l:'💚 Budget',  c: SEG_COLORS.budget },
  { k:'standard', l:'◉ Standard', c: SEG_COLORS.standard },
  { k:'premium',  l:'⭐ Premium', c: SEG_COLORS.premium },
  { k:'luxury',   l:'👑 Luxury',  c: SEG_COLORS.luxury },
];

// ─── Collapsible section (moved from Sidebar.jsx) ─────────────────────────────
function Section({ title, count, children, defaultOpen = true }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="border-b border-s3">
      <button
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between bg-transparent px-3 py-2 text-[.63rem] font-bold uppercase tracking-[.1em] text-t4"
      >
        <span>
          {title}
          {count > 0 && <span className="ml-1 text-primary">{count}</span>}
        </span>
        <span
          className={cn('text-[.65rem] opacity-50 transition-transform', open && 'rotate-180')}
        >
          ▾
        </span>
      </button>
      {open && <div className="px-2.5 pb-2.5">{children}</div>}
    </div>
  );
}

// ─── Pill button (moved from Sidebar.jsx) ──────────────────────────────────────
function Pill({ label, count, active, color, onClick, size }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'm-0.5 inline-flex items-center gap-1 rounded-full border-[1.5px] transition-colors',
        size === 'lg' ? 'px-3 py-1 text-[.78rem]' : 'px-2.5 py-0.5 text-[.68rem]'
      )}
      style={{
        borderColor: active ? (color || 'var(--p)') : 'var(--s3)',
        background: active ? `color-mix(in srgb, ${color || 'var(--p)'} 15%, transparent)` : 'transparent',
        color: active ? (color || 'var(--p2)') : 'var(--t4)',
        fontWeight: active ? 700 : 400,
      }}
    >
      {label}
      {count !== undefined && (
        <span className={cn('opacity-55', size === 'lg' ? 'text-[.66rem]' : 'text-[.6rem]')}>{count}</span>
      )}
    </button>
  );
}

// ─── CatalogoFilterBar ──────────────────────────────────────────────────────────
// Catálogo-scoped sticky filter sub-bar. Renders ONLY inside CatalogoRoute
// (see AppLayout.jsx) — never mounted globally. Categoría/Marca/Género are
// always-visible primary controls; everything else lives in a shared "más
// filtros" Sheet panel (Talle, Badge ML, Segmento, Gymrat, Packs, Actividad).
// Consumes AppLayout's filter state/dispatch via useOutletContext — no props
// needed besides `hidden` (scroll-direction visibility, computed by the
// caller's useStickyFilterBar so the hero-height ResizeObserver can share a
// single wrapping ref around <SearchHero/>).
export default function CatalogoFilterBar({ hidden }) {
  const { S, setFilter, set, dispatch } = useOutletContext();

  const facets       = S.facets || {};
  const meta         = S.meta || {};
  const badges       = facets.badges        || {};
  const generos      = facets.generos       || {};
  const cats         = facets.categorias    || {};
  const marcas       = facets.marcas        || {};
  const talles       = facets.talles        || {};
  const gymratCount  = facets.gymratCount   || 0;
  const packCount    = facets.packCount     || 0;
  const subCategorias = facets.subCategorias || {};

  const topMarcas = useMemo(() => sortByCountDesc(marcas).slice(0, 20), [marcas]);

  // activePanel drives the ONE shared Sheet (Spec/Design: one bar, one Sheet).
  const [activePanel, setActivePanel] = useState(null); // 'cat' | 'marca' | 'mas' | null
  const closePanel = () => setActivePanel(null);

  const onFilter = (payload) => {
    // gymSubcatFiltro is client-side only — do not reset pagination
    if ('gymSubcatFiltro' in payload) set(payload);
    else setFilter(payload);
  };
  const onToggleCat    = v => dispatch({ type:'TOGGLE_CAT', v });
  const onToggleSubcat = v => dispatch({ type:'TOGGLE_SUBCAT', v });
  const onToggleTalle  = v => dispatch({ type:'TOGGLE_TALLE', v });
  const onToggleMarca  = v => dispatch({ type:'TOGGLE_MARCA', v });
  const onReset = () => { dispatch({ type:'RESET_FILTERS' }); closePanel(); };

  // Controlled local strings for the price inputs so external resets (Limpiar)
  // visibly clear them — uncontrolled defaultValue would not react to prop changes.
  const [precioMinStr, setPrecioMinStr] = useState(S.precioMin ?? '');
  const [precioMaxStr, setPrecioMaxStr] = useState(S.precioMax ?? '');
  useEffect(() => { setPrecioMinStr(S.precioMin ?? ''); }, [S.precioMin]);
  useEffect(() => { setPrecioMaxStr(S.precioMax ?? ''); }, [S.precioMax]);

  // Empty string means "blur with an invalid/cleared value" -> clear the filter.
  // Clamp to >= 0: a price floor/ceiling can't be negative.
  const commitPrecio = (v) => {
    if (v === '') return undefined;
    const n = Number(v);
    return Number.isNaN(n) ? undefined : Math.max(0, n);
  };

  const catCount   = S.categorias?.length || 0;
  const marcaCount = S.marca?.length || 0;

  // Secondary (overflow panel) active-filter count — drives the "más filtros N" badge.
  const secondaryCount = (S.talles?.length || 0)
    + (S.badge ? 1 : 0)
    + (S.segment ? 1 : 0)
    + (S.gymrat ? 1 : 0)
    + (S.pack ? 1 : 0)
    + (S.subCategoria?.length || 0)
    + (S.precioMin != null || S.precioMax != null ? 1 : 0);

  const activeCount = catCount + marcaCount + secondaryCount + (S.genero ? 1 : 0);

  // Agrupar categorías semánticamente (moved from Sidebar.jsx, unchanged logic)
  const { calzado, superior, inferior, tech, suppl, accesorio, otros } = useMemo(() => {
    const grp = (regex) => Object.entries(cats).filter(([k]) => regex.test(k));
    const calzado   = grp(/zapatilla|bota|ojota/i);
    const superior  = grp(/remera|buzo|sweater|campera|musculosa|camisa|puffer/i);
    const inferior  = grp(/jean|jogging|pantalón|short|calza|pollera/i);
    const tech      = grp(/gpu|cpu|ram|ssd|monitor|teclado|mouse|notebook|gabinete|madre|placa|auricular|fuente/i);
    const suppl     = grp(/suplemento|alimento|proteína/i);
    const accesorio = grp(/mochila|gorra|medias|cinturón|bolso/i);
    const shownKeys = new Set(
      [...calzado, ...superior, ...inferior, ...tech, ...suppl, ...accesorio].map(([k]) => k)
    );
    const otros = Object.entries(cats).filter(([k]) => !shownKeys.has(k));
    return { calzado, superior, inferior, tech, suppl, accesorio, otros };
  }, [cats]);

  const CatGroup = ({ title, items }) => {
    if (!items.length) return null;
    return (
      <div className="cat-group">
        <div className="cat-group-title">{title}</div>
        {items.slice(0, 12).map(([cat, n]) => (
          <Pill key={cat} label={cat} count={n} size="lg"
            active={S.categorias?.includes(cat)}
            onClick={() => onToggleCat(cat)} />
        ))}
      </div>
    );
  };

  const panelTitle = { cat: 'Categoría', marca: 'Marca', mas: 'Más filtros' }[activePanel] || 'Filtros';

  return (
    <>
      <div className={cn('catalogo-filter-bar', hidden && 'catalogo-filter-bar--hidden')}>
        {Object.keys(cats).length > 0 && (
          <button type="button" className="cfb-trigger" onClick={() => setActivePanel('cat')}>
            📂 Categoría
            {catCount > 0 && <span className="cfb-count">{catCount}</span>}
          </button>
        )}

        {topMarcas.length > 0 && (
          <button type="button" className="cfb-trigger" onClick={() => setActivePanel('marca')}>
            🏷 Marca
            {marcaCount > 0 && <span className="cfb-count">{marcaCount}</span>}
          </button>
        )}

        {Object.keys(generos).length > 0 && (
          <div className="cfb-genero-group">
            {Object.entries(generos).map(([g, n]) => (
              <button
                key={g}
                type="button"
                onClick={() => onFilter({ genero: S.genero === g ? '' : g })}
                className={cn('cfb-genero-pill', S.genero === g && 'active')}
              >
                {g.charAt(0).toUpperCase() + g.slice(1)}
                <span className="cfb-genero-count">{n}</span>
              </button>
            ))}
          </div>
        )}

        <button type="button" className="cfb-trigger cfb-mas" onClick={() => setActivePanel('mas')}>
          ⚙ Más filtros
          {secondaryCount > 0 && <span className="cfb-count">{secondaryCount}</span>}
        </button>

        {activeCount > 0 && (
          <button type="button" className="cfb-clear" onClick={onReset}>
            ✕ Limpiar
          </button>
        )}
      </div>

      <Sheet open={!!activePanel} onOpenChange={next => { if (!next) closePanel(); }}>
        <SheetContent side="right" className="cfb-sheet">
          <SheetTitle className="sr-only">{panelTitle}</SheetTitle>

          <div className="cfb-sheet-header">
            <span className="cfb-sheet-title">{panelTitle}</span>
            <button type="button" onClick={closePanel} className="cfb-sheet-close">✕</button>
          </div>

          <div className="cfb-sheet-body">
            {activePanel === 'cat' && (
              <>
                <CatGroup title="Calzado"    items={calzado} />
                <CatGroup title="Superior"   items={superior} />
                <CatGroup title="Inferior"   items={inferior} />
                <CatGroup title="Tech"       items={tech} />
                <CatGroup title="Nutrición"  items={suppl} />
                <CatGroup title="Accesorios" items={accesorio} />
                {otros.length > 0 && <CatGroup title="Otros" items={otros.slice(0, 8)} />}
              </>
            )}

            {activePanel === 'marca' && (
              <div className="cfb-pill-list">
                {topMarcas.map(([marca, n]) => (
                  <Pill key={marca} label={marca} count={n}
                    active={S.marca?.includes(marca)}
                    onClick={() => onToggleMarca(marca)} />
                ))}
              </div>
            )}

            {activePanel === 'mas' && (
              <>
                {/* Precio */}
                <Section title="Precio">
                  <div className="flex items-center gap-1.5">
                    <input
                      type="number"
                      inputMode="numeric"
                      className="price-input"
                      placeholder={meta.rangMin != null ? String(meta.rangMin) : 'Min'}
                      value={precioMinStr}
                      onChange={e => setPrecioMinStr(e.target.value)}
                      onBlur={e => onFilter({ precioMin: commitPrecio(e.target.value) })}
                    />
                    <span className="price-sep">–</span>
                    <input
                      type="number"
                      inputMode="numeric"
                      className="price-input"
                      placeholder={meta.rangMax != null ? String(meta.rangMax) : 'Max'}
                      value={precioMaxStr}
                      onChange={e => setPrecioMaxStr(e.target.value)}
                      onBlur={e => onFilter({ precioMax: commitPrecio(e.target.value) })}
                    />
                  </div>
                </Section>

                {/* Talle */}
                {Object.keys(talles).length > 0 && (
                  <Section title="Talle" count={S.talles?.length || 0}>
                    {Object.entries(talles).map(([t, n]) => (
                      <Pill key={t} label={t} count={n}
                        active={S.talles?.includes(t)}
                        onClick={() => onToggleTalle(t)} />
                    ))}
                  </Section>
                )}

                {/* ML Badge */}
                {Object.keys(badges).length > 0 && (
                  <Section title="🏷 Precio ML">
                    {Object.entries(BADGE_LABELS).map(([k, lbl]) => {
                      if (!badges[k]) return null;
                      return (
                        <Pill key={k} label={lbl} count={badges[k]}
                          color={BADGE_COLORS[k]}
                          active={S.badge === k}
                          onClick={() => onFilter({ badge: S.badge === k ? '' : k })} />
                      );
                    })}
                  </Section>
                )}

                {/* Segmento */}
                <Section title="Segmento">
                  <div className="flex flex-wrap gap-1">
                    {SEGMENTOS.map(({ k, l, c }) => (
                      <button
                        key={k}
                        type="button"
                        onClick={() => onFilter({ segment: S.segment === k ? '' : k })}
                        className="border-b-[1.5px] px-0.5 py-0.5 text-[.72rem] transition-colors"
                        style={{
                          borderColor: S.segment === k ? c : 'transparent',
                          color: S.segment === k ? c : 'var(--t4)',
                          fontWeight: S.segment === k ? 700 : 400,
                        }}
                      >
                        {l}
                      </button>
                    ))}
                  </div>
                </Section>

                {/* Gymrat / Modo GYM */}
                {gymratCount > 0 && (
                  <Section title="🏋️ Modo GYM">
                    <Pill
                      label="Ropa gym"
                      count={gymratCount}
                      active={S.gymrat}
                      color={SEMANTIC.gym}
                      onClick={() => onFilter({ gymrat: !S.gymrat })}
                    />
                    {S.gymrat && S.gymSubcats && Object.keys(S.gymSubcats).length > 0 && (
                      <div className="gym-subcats">
                        {Object.entries(S.gymSubcats)
                          .sort((a, b) => b[1] - a[1])
                          .map(([subcat, count]) => (
                            <button
                              key={subcat}
                              type="button"
                              className={`subcat-chip${S.gymSubcatFiltro === subcat ? ' active' : ''}`}
                              onClick={() => onFilter({
                                gymSubcatFiltro: S.gymSubcatFiltro === subcat ? null : subcat
                              })}
                            >
                              {subcat} <small>({count})</small>
                            </button>
                          ))
                        }
                      </div>
                    )}
                  </Section>
                )}

                {/* Packs / Combos */}
                {packCount > 0 && (
                  <Section title="📦 Packs">
                    <Pill
                      label="Packs / combos"
                      count={packCount}
                      active={S.pack}
                      color={SEMANTIC.pack}
                      onClick={() => onFilter({ pack: !S.pack })}
                    />
                  </Section>
                )}

                {/* Actividad (subcategory activity/sport dimension) */}
                {Object.keys(subCategorias).length > 0 && (
                  <Section title="🎯 Actividad" count={S.subCategoria?.length || 0} defaultOpen={false}>
                    {Object.entries(subCategorias).map(([sc, n]) => (
                      <Pill key={sc} label={sc} count={n}
                        active={S.subCategoria?.includes(sc)}
                        onClick={() => onToggleSubcat(sc)} />
                    ))}
                  </Section>
                )}

                {Object.keys(badges).length === 0 && Object.keys(cats).length === 0 && (
                  <div className="px-3.5 py-6 text-center text-[.75rem] text-t4">
                    Los filtros se cargan después del primer scraping.
                  </div>
                )}
              </>
            )}
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
