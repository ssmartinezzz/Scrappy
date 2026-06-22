import { useState } from 'react';
import { BADGE_LABELS } from '../api';

const SEGMENTOS = [
  { k:'budget',   l:'💚 Budget',  c:'#00b894' },
  { k:'standard', l:'◉ Standard', c:'var(--p)' },
  { k:'premium',  l:'⭐ Premium', c:'#f0a500' },
  { k:'luxury',   l:'👑 Luxury',  c:'#e84393' },
];

const BADGE_COLORS = {
  precio_historico_bajo:'#f0a500', precio_bajo:'#3fb950',
  oferta_real:'#a371f7', tendencia:'#fd6400',
  precio_bajando:'#3fb950', precio_alto:'#e84393', descuento_cosmetico:'var(--t4)',
};

// ─── Collapsible section ──────────────────────────────────────────────────────
function Section({ title, count, children, defaultOpen = true }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div style={{ borderBottom:'1px solid var(--s3)' }}>
      <button onClick={() => setOpen(!open)} style={{
        width:'100%', display:'flex', justifyContent:'space-between', alignItems:'center',
        padding:'.55rem .85rem', background:'none', border:'none', cursor:'pointer',
        fontSize:'.63rem', fontWeight:700, color:'var(--t4)',
        textTransform:'uppercase', letterSpacing:'.1em',
      }}>
        <span>
          {title}
          {count > 0 && <span style={{ color:'var(--p)', marginLeft:5 }}>{count}</span>}
        </span>
        <span style={{ fontSize:'.65rem', opacity:.5,
                       transform: open ? 'rotate(180deg)' : 'none', transition:'transform .15s' }}>
          ▾
        </span>
      </button>
      {open && (
        <div style={{ padding:'0 .6rem .65rem' }}>{children}</div>
      )}
    </div>
  );
}

// ─── Pill button ─────────────────────────────────────────────────────────────
function Pill({ label, count, active, color, onClick }) {
  return (
    <button onClick={onClick} style={{
      padding:'3px 9px', borderRadius:16, margin:'2px',
      border:`1.5px solid ${active ? (color || 'var(--p)') : 'var(--s3)'}`,
      background: active ? `${color || 'var(--p)'}18` : 'transparent',
      color: active ? (color || 'var(--p2)') : 'var(--t4)',
      fontSize:'.68rem', fontWeight: active ? 700 : 400,
      cursor:'pointer', transition:'all .1s',
      display:'inline-flex', alignItems:'center', gap:3,
    }}>
      {label}
      {count !== undefined && (
        <span style={{ opacity:.55, fontSize:'.6rem' }}>{count}</span>
      )}
    </button>
  );
}

// ─── Sidebar ─────────────────────────────────────────────────────────────────
export default function Sidebar({
  facets = {}, filters, onFilter, onToggleCat, onToggleTalle, onReset,
  open = false, onClose,
}) {
  const badges      = facets.badges     || {};
  const generos     = facets.generos    || {};
  const cats        = facets.categorias || {};
  const gymratCount = facets.gymratCount || 0;
  const packCount   = facets.packCount   || 0;

  const activeCount = [filters.badge, filters.segment, filters.genero].filter(Boolean).length
    + (filters.categorias?.length || 0)
    + (filters.gymrat ? 1 : 0)
    + (filters.pack ? 1 : 0);

  // Agrupar categorías semánticamente
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

  const CatGroup = ({ title, items }) => {
    if (!items.length) return null;
    return (
      <div style={{ marginBottom:4 }}>
        <div style={{ fontSize:'.58rem', color:'var(--t4)', margin:'4px 0 2px',
                      textTransform:'uppercase', letterSpacing:'.1em' }}>
          {title}
        </div>
        {items.slice(0, 12).map(([cat, n]) => (
          <Pill key={cat} label={cat} count={n}
            active={filters.categorias?.includes(cat)}
            onClick={() => onToggleCat(cat)} />
        ))}
      </div>
    );
  };

  return (
    <div>
      {/* Mobile overlay backdrop */}
      {open && (
        <div
          onClick={onClose}
          style={{
            position:'fixed', inset:0, background:'rgba(0,0,0,.5)', zIndex:299,
          }}
        />
      )}

      {/* Sidebar panel */}
      <div className={`sidebar${open ? ' open' : ''}`}
           style={{ overflowY:'auto', overflowX:'hidden' }}>

        {/* Header */}
        <div style={{
          padding:'.6rem .85rem', borderBottom:'1px solid var(--s3)',
          display:'flex', justifyContent:'space-between', alignItems:'center',
          position:'sticky', top:0, background:'var(--s1)', zIndex:5,
        }}>
          <span style={{ fontSize:'.7rem', fontWeight:700, color:'var(--t3)' }}>
            Filtros
            {activeCount > 0 && (
              <span style={{
                background:'var(--p)', color:'#fff', borderRadius:20,
                padding:'0px 7px', fontSize:'.58rem', marginLeft:5,
              }}>
                {activeCount}
              </span>
            )}
          </span>
          {activeCount > 0 && (
            <button onClick={onReset} style={{
              background:'none', border:'none', color:'var(--r)',
              fontSize:'.65rem', cursor:'pointer', padding:0,
            }}>
              ✕ Limpiar
            </button>
          )}
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

        {/* Segmento */}
        <Section title="💎 Segmento" defaultOpen={false}>
          {SEGMENTOS.map(({ k, l, c }) => (
            <Pill key={k} label={l} active={filters.segment === k} color={c}
              onClick={() => onFilter({ segment: filters.segment === k ? '' : k })} />
          ))}
        </Section>

        {/* Género */}
        {Object.keys(generos).length > 0 && (
          <Section title="👤 Género" defaultOpen={false}>
            {Object.entries(generos).map(([g, n]) => (
              <Pill key={g}
                label={g.charAt(0).toUpperCase() + g.slice(1)}
                count={n}
                active={filters.genero === g}
                onClick={() => onFilter({ genero: filters.genero === g ? '' : g })} />
            ))}
          </Section>
        )}

        {/* Categorías agrupadas */}
        {Object.keys(cats).length > 0 && (
          <Section title="📂 Categoría" count={filters.categorias?.length || 0}>
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
              color="#84cc16"
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
              color="#a371f7"
              onClick={() => onFilter({ pack: !filters.pack })}
            />
          </Section>
        )}

        {/* Empty state */}
        {Object.keys(badges).length === 0 && Object.keys(cats).length === 0 && (
          <div style={{
            padding:'1.5rem .9rem', fontSize:'.75rem',
            color:'var(--t4)', textAlign:'center',
          }}>
            Los filtros se cargan después del primer scraping.
          </div>
        )}
      </div>
    </div>
  );
}
