import { useState, useEffect, useMemo } from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { Sheet, SheetOverlay, SheetTitle } from './ui/sheet';
import { cn, sortByCountDesc } from '@/lib/utils';
import { BADGE_LABELS } from '../api';
import { SEG_COLORS, BADGE_COLORS, SEMANTIC } from '../lib/colors';

const SEGMENTOS = [
  { k:'budget',   l:'💚 Budget',  c: SEG_COLORS.budget },
  { k:'standard', l:'◉ Standard', c: SEG_COLORS.standard },
  { k:'premium',  l:'⭐ Premium', c: SEG_COLORS.premium },
  { k:'luxury',   l:'👑 Luxury',  c: SEG_COLORS.luxury },
];

// ─── Collapsible section ──────────────────────────────────────────────────────
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

// ─── Pill button ─────────────────────────────────────────────────────────────
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

// ─── Sidebar ─────────────────────────────────────────────────────────────────
export default function Sidebar({
  facets = {}, meta = {}, filters, onFilter, onToggleCat, onToggleSubcat, onToggleTalle, onReset,
  open = false, onClose, onOpen,
}) {
  const badges        = facets.badges        || {};
  const generos       = facets.generos       || {};
  const cats          = facets.categorias    || {};
  const marcas        = facets.marcas        || {};
  const gymratCount   = facets.gymratCount   || 0;
  const packCount     = facets.packCount     || 0;
  const subCategorias = facets.subCategorias || {};

  const topMarcas = useMemo(() => sortByCountDesc(marcas).slice(0, 20), [marcas]);

  // Controlled local strings for the price inputs so external resets (Limpiar)
  // visibly clear them — uncontrolled defaultValue would not react to prop changes.
  const [precioMinStr, setPrecioMinStr] = useState(filters.precioMin ?? '');
  const [precioMaxStr, setPrecioMaxStr] = useState(filters.precioMax ?? '');
  useEffect(() => { setPrecioMinStr(filters.precioMin ?? ''); }, [filters.precioMin]);
  useEffect(() => { setPrecioMaxStr(filters.precioMax ?? ''); }, [filters.precioMax]);

  // Empty string means "blur with an invalid/cleared value" -> clear the filter.
  // Clamp to >= 0: a price floor/ceiling can't be negative.
  const commitPrecio = (v) => {
    if (v === '') return undefined;
    const n = Number(v);
    return Number.isNaN(n) ? undefined : Math.max(0, n);
  };

  const activeCount = [filters.badge, filters.segment, filters.genero, filters.marca].filter(Boolean).length
    + (filters.categorias?.length || 0)
    + (filters.gymrat ? 1 : 0)
    + (filters.pack ? 1 : 0)
    + (filters.precioMin != null || filters.precioMax != null ? 1 : 0)
    + (filters.subCategoria?.length || 0);

  // Agrupar categorías semánticamente
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
            active={filters.categorias?.includes(cat)}
            onClick={() => onToggleCat(cat)} />
        ))}
      </div>
    );
  };

  // ─── Rail: precio + género + segmento — always reachable with zero extra
  // clicks (Spec Req 4). Rendered TWICE: once inline (desktop persistent
  // rail, hidden on mobile via `.filter-rail`/CSS) and once inside the Sheet
  // content below (mobile — the Sheet is the only surface on small widths,
  // so the rail controls must also exist there). Same handlers, same state —
  // no filter logic duplicated, only the markup placement differs.
  const RailControls = () => (
    <div className="flex flex-col gap-3">
      {/* Precio */}
      <div>
        <div className="mb-1.5 text-eyebrow text-t4">Precio</div>
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
      </div>

      {/* Género */}
      {Object.keys(generos).length > 0 && (
        <div>
          <div className="mb-1.5 text-eyebrow text-t4">Género</div>
          <div className="flex flex-wrap gap-1">
            {Object.entries(generos).map(([g, n]) => (
              <button
                key={g}
                onClick={() => onFilter({ genero: filters.genero === g ? '' : g })}
                className={cn(
                  'border-b-[1.5px] border-transparent px-0.5 py-0.5 text-[.72rem] transition-colors',
                  filters.genero === g
                    ? 'border-primary font-bold text-primary2'
                    : 'text-t4 hover:text-t2'
                )}
              >
                {g.charAt(0).toUpperCase() + g.slice(1)}
                <span className="ml-1 text-[.6rem] opacity-55">{n}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Segmento */}
      <div>
        <div className="mb-1.5 text-eyebrow text-t4">Segmento</div>
        <div className="flex flex-wrap gap-1">
          {SEGMENTOS.map(({ k, l, c }) => (
            <button
              key={k}
              onClick={() => onFilter({ segment: filters.segment === k ? '' : k })}
              className="border-b-[1.5px] px-0.5 py-0.5 text-[.72rem] transition-colors"
              style={{
                borderColor: filters.segment === k ? c : 'transparent',
                color: filters.segment === k ? c : 'var(--t4)',
                fontWeight: filters.segment === k ? 700 : 400,
              }}
            >
              {l}
            </button>
          ))}
        </div>
      </div>
    </div>
  );

  const content = (
    <div className="flex h-full flex-col overflow-y-auto overflow-x-hidden">
      {/* Header */}
      <div className="sticky top-0 z-[5] flex items-center justify-between border-b border-s3 bg-s1 px-3 py-2.5">
        <span className="text-[.7rem] font-bold text-t3">
          Filtros
          {activeCount > 0 && (
            <span className="ml-1 rounded-full bg-primary px-1.5 py-0 text-[.58rem] text-white">
              {activeCount}
            </span>
          )}
        </span>
        {activeCount > 0 && (
          <button onClick={onReset} className="bg-transparent p-0 text-[.65rem] text-danger">
            ✕ Limpiar
          </button>
        )}
      </div>

      {/* Rail controls also live here so mobile (Sheet-only) keeps every
          filter reachable — desktop hides this duplicate via .sidebar-sheet-rail
          CSS since the persistent <Rail/> already covers it inline. */}
      <div className="sidebar-sheet-rail border-b border-s3 px-3 py-2.5">
        <RailControls />
      </div>

      {/* ML Badge */}
      {Object.keys(badges).length > 0 && (
        <Section title="🏷 Precio ML">
          {Object.entries(BADGE_LABELS).map(([k, lbl]) => {
            if (!badges[k]) return null;
            return (
              <Pill key={k} label={lbl} count={badges[k]}
                color={BADGE_COLORS[k]}
                active={filters.badge === k}
                onClick={() => onFilter({ badge: filters.badge === k ? '' : k })} />
            );
          })}
        </Section>
      )}

      {/* Marca */}
      {topMarcas.length > 0 && (
        <Section title="🏷 Marca" defaultOpen={true}>
          {topMarcas.map(([marca, n]) => (
            <Pill key={marca} label={marca} count={n}
              active={filters.marca === marca}
              onClick={() => onFilter({ marca: filters.marca === marca ? '' : marca })} />
          ))}
        </Section>
      )}

      {/* Categorías agrupadas */}
      {Object.keys(cats).length > 0 && (
        <Section title="📂 Categoría" count={filters.categorias?.length || 0} defaultOpen={true}>
          <CatGroup title="Calzado"    items={calzado} />
          <CatGroup title="Superior"   items={superior} />
          <CatGroup title="Inferior"   items={inferior} />
          <CatGroup title="Tech"       items={tech} />
          <CatGroup title="Nutrición"  items={suppl} />
          <CatGroup title="Accesorios" items={accesorio} />
          {otros.length > 0 && <CatGroup title="Otros" items={otros.slice(0, 8)} />}
        </Section>
      )}

      {/* Gymrat / Modo GYM */}
      {gymratCount > 0 && (
        <Section title="🏋️ Modo GYM">
          <Pill
            label="Ropa gym"
            count={gymratCount}
            active={filters.gymrat}
            color={SEMANTIC.gym}
            onClick={() => onFilter({ gymrat: !filters.gymrat })}
          />
          {filters.gymrat && filters.gymSubcats && Object.keys(filters.gymSubcats).length > 0 && (
            <div className="gym-subcats">
              {Object.entries(filters.gymSubcats)
                .sort((a, b) => b[1] - a[1])
                .map(([subcat, count]) => (
                  <button
                    key={subcat}
                    className={`subcat-chip${filters.gymSubcatFiltro === subcat ? ' active' : ''}`}
                    onClick={() => onFilter({
                      gymSubcatFiltro: filters.gymSubcatFiltro === subcat ? null : subcat
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
            active={filters.pack}
            color={SEMANTIC.pack}
            onClick={() => onFilter({ pack: !filters.pack })}
          />
        </Section>
      )}

      {/* Actividad (subcategory activity/sport dimension) */}
      {Object.keys(subCategorias).length > 0 && (
        <Section title="🎯 Actividad" count={filters.subCategoria?.length || 0} defaultOpen={false}>
          {Object.entries(subCategorias).map(([sc, n]) => (
            <Pill key={sc} label={sc} count={n}
              active={filters.subCategoria?.includes(sc)}
              onClick={() => onToggleSubcat(sc)} />
          ))}
        </Section>
      )}

      {/* Empty state */}
      {Object.keys(badges).length === 0 && Object.keys(cats).length === 0 && (
        <div className="px-3.5 py-6 text-center text-[.75rem] text-t4">
          Los filtros se cargan después del primer scraping.
        </div>
      )}
    </div>
  );

  // Radix Dialog gives free focus-trap/ESC/ARIA when `open` is true (mobile/tablet
  // drawer). `forceMount` keeps the Content permanently in the DOM so desktop can
  // render it as a static in-flow column via the `.sidebar` CSS class.
  // `modal={open}` is required alongside forceMount: Radix's modal Dialog content
  // applies `aria-hidden` to the rest of the app on mount via an effect that only
  // cleans up on unmount, not when `open` flips back to false. With forceMount the
  // content never unmounts, so a plain `modal` (default true) would hide the whole
  // app behind the sidebar permanently, even on desktop where the drawer is never
  // actually opened. Tying `modal` to `open` makes Radix swap between its modal and
  // non-modal Content variants as the drawer opens/closes, so aria-hidden/focus-trap
  // only apply while the drawer is genuinely open and are reverted when it closes.
  // Content is intentionally NOT wrapped in DialogPrimitive.Portal: portaling to
  // document.body would pull it out of the `.layout` flex flow that positions it
  // beside `.content` on desktop. Only the overlay backdrop is portaled (mobile-only,
  // already unmounted by Radix when `open` is false).
  //
  // Hybrid filter pattern (Spec Req 4-5): the persistent <Rail/> below is a
  // SEPARATE, ALWAYS-VISIBLE-ON-DESKTOP block (precio/género/segmento) that
  // sits next to `.content` in the `.layout` flex flow, independent of the
  // Sheet's open/closed state — interacting with it never requires opening
  // the Sheet first. The existing Sheet (categoría/marca/ML badge/GYM/packs)
  // is reused as-is for both mobile (full drawer, rail duplicated inside via
  // `.sidebar-sheet-rail` for reachability) and desktop ("Filtros" trigger
  // opens the same Sheet content over the rail). No filter logic is
  // duplicated — only RailControls' markup is rendered in two places.
  return (
    <>
      <div className="filter-rail">
        <div className="filter-rail-header">
          <span className="text-eyebrow text-t3">
            Filtros
            {activeCount > 0 && (
              <span className="ml-1 rounded-full bg-primary px-1.5 py-0 text-[.58rem] text-white">
                {activeCount}
              </span>
            )}
          </span>
          {activeCount > 0 && (
            <button onClick={onReset} className="bg-transparent p-0 text-[.65rem] text-danger">
              ✕ Limpiar
            </button>
          )}
        </div>
        <RailControls />
        <button onClick={onOpen} className="filter-rail-trigger">
          ⚙ Más filtros
          {(Object.keys(badges).length > 0 || topMarcas.length > 0 || Object.keys(cats).length > 0) && (
            <span className="opacity-55">
              {' '}(marca, categoría, ML, GYM, packs)
            </span>
          )}
        </button>
      </div>

      <Sheet open={open} modal={open} onOpenChange={next => { if (!next) onClose?.(); }}>
        <DialogPrimitive.Portal>
          <SheetOverlay />
        </DialogPrimitive.Portal>
        <DialogPrimitive.Content
          forceMount
          onEscapeKeyDown={onClose}
          onPointerDownOutside={onClose}
          className={cn('sidebar', open && 'open')}
        >
          <SheetTitle className="sr-only">Filtros</SheetTitle>
          {content}
        </DialogPrimitive.Content>
      </Sheet>
    </>
  );
}
