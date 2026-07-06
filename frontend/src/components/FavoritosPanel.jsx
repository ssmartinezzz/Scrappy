import { useMemo, useState } from 'react';
import { LayoutGrid, List } from 'lucide-react';
import BuySignal from './BuySignal';
import { TiltCarousel } from './ui/tilt-carousel';
import { rescrapeFavoritos, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';

const VIEW_MODE_KEY = 'favoritos:viewMode';

// ─── SavedOutfitCard ──────────────────────────────────────────────────────────
function SavedOutfitCard({ outfit, onDelete, onRename, onOpenDetail }) {
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState(outfit.nombre || 'Outfit');
  const [expanded, setExpanded] = useState(false);

  function startEdit(e) {
    e.stopPropagation();
    setEditName(outfit.nombre || 'Outfit');
    setEditing(true);
  }

  function confirmEdit() {
    const name = editName.trim();
    if (name && name !== outfit.nombre) {
      onRename?.(outfit.id, name);
    }
    setEditing(false);
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') confirmEdit();
    if (e.key === 'Escape') setEditing(false);
  }

  const slots = outfit.slots || [];
  const dateStr = outfit.createdAt
    ? new Date(outfit.createdAt.replace(' ', 'T')).toLocaleDateString('es-AR', {
        day:'2-digit', month:'2-digit', year:'numeric',
      })
    : '';

  return (
    <div style={{
      background:'var(--s2)', borderRadius:10, padding:'.75rem',
      border:'1.5px solid var(--bd)',
      display:'flex', flexDirection:'column', gap:8,
    }}>
      {/* Name + expand toggle + price + delete */}
      <div
        onClick={() => !editing && setExpanded(p => !p)}
        style={{ display:'flex', alignItems:'center', gap:8, cursor: editing ? 'default' : 'pointer' }}>
        {editing ? (
          <input
            autoFocus
            value={editName}
            onChange={e => setEditName(e.target.value)}
            onBlur={confirmEdit}
            onKeyDown={handleKeyDown}
            onClick={e => e.stopPropagation()}
            style={{
              flex:1, fontSize:'.82rem', fontWeight:700, color:'var(--t1)',
              background:'var(--s3)', border:'1px solid var(--p2)',
              borderRadius:4, padding:'2px 8px',
            }}
          />
        ) : (
          <div
            onDoubleClick={startEdit}
            title="Doble clic para renombrar · clic para expandir"
            style={{
              flex:1, fontSize:'.82rem', fontWeight:700, color:'var(--t1)',
              overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
            }}>
            {outfit.nombre || 'Outfit'}
          </div>
        )}
        <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--t2)', whiteSpace:'nowrap' }}>
          ${fmt(outfit.totalEstimado)}
        </div>
        <span style={{ fontSize:'.65rem', color:'var(--t4)' }}>{expanded ? '▲' : '▼'}</span>
        <button
          onClick={e => { e.stopPropagation(); onDelete?.(outfit.id); }}
          title="Eliminar outfit"
          style={{
            background:'none', border:'none', cursor:'pointer',
            fontSize:'.8rem', color:'var(--t4)', padding:'2px 4px',
          }}>✕</button>
      </div>

      {/* Collapsed: small thumbnails */}
      {!expanded && slots.length > 0 && (
        <div style={{ display:'flex', gap:6, flexWrap:'wrap' }}>
          {slots.map((s, i) => s.img ? (
            <img
              key={i}
              src={s.img}
              alt={s.nombre}
              loading="lazy"
              title={s.nombre}
              style={{ width:48, height:48, objectFit:'cover', borderRadius:6,
                       border:'1px solid var(--bd)' }}
              onError={e => { e.target.style.display = 'none'; }}
            />
          ) : null)}
        </div>
      )}

      {/* Expanded: full slot list */}
      {expanded && slots.length > 0 && (
        <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
          {slots.map((s, i) => (
            <div key={i} style={{
              display:'flex', gap:10, alignItems:'center',
              background:'var(--s1)', borderRadius:8, padding:'.5rem .65rem',
              border:'1px solid var(--bd)',
            }}>
              {s.img && (
                <img
                  src={s.img}
                  alt={s.nombre}
                  loading="lazy"
                  style={{ width:64, height:64, objectFit:'cover', borderRadius:6,
                           flexShrink:0, border:'1px solid var(--bd)' }}
                  onError={e => { e.target.style.display = 'none'; }}
                />
              )}
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{
                  fontSize:'.78rem', fontWeight:600, color:'var(--t1)',
                  overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                }}>{s.nombre || '—'}</div>
                <div style={{ fontSize:'.7rem', color:'var(--t3)' }}>{s.sitio}</div>
                {s.precio > 0 && (
                  <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--p2)', marginTop:2 }}>
                    ${fmt(s.precio)}
                  </div>
                )}
              </div>
              {s.url && (
                <button
                  type="button"
                  onClick={e => { e.stopPropagation(); onOpenDetail?.(s); }}
                  style={{
                    fontSize:'.65rem', color:'var(--p)', fontWeight:600,
                    background:'none', border:'none', cursor:'pointer',
                    whiteSpace:'nowrap', flexShrink:0, padding:'12px 10px',
                    minHeight:44, minWidth:44,
                  }}>
                  Ver detalle →
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Date */}
      {dateStr && (
        <div style={{ fontSize:'.65rem', color:'var(--t4)' }}>{dateStr}</div>
      )}
    </div>
  );
}

// ─── FavoritosPanel ───────────────────────────────────────────────────────────
export default function FavoritosPanel({
  favoritos, scrapeStatus, onOpenDetail, onStartPolling, onRefreshFavoritos, onSetScraping,
  savedOutfits, onDeleteSavedOutfit, onRenameSavedOutfit, onDeleteFavorito,
}) {
  const items = favoritos || [];
  const outfits = savedOutfits || [];
  const isEmpty = items.length === 0 && outfits.length === 0;

  // View-mode toggle (design ADR-4): view-local state, NOT lifted to the
  // AppLayout reducer — no other component consumes it. Lazy-initialised
  // from localStorage; default 'carousel' (spec: "First-ever visit").
  const [viewMode, setViewMode] = useState(() => {
    if (typeof window === 'undefined') return 'carousel';
    return localStorage.getItem(VIEW_MODE_KEY) || 'carousel';
  });
  function handleSetViewMode(mode) {
    setViewMode(mode);
    if (typeof window !== 'undefined') localStorage.setItem(VIEW_MODE_KEY, mode);
  }

  // Inline outfit expand (design ADR-3 — NOT a nested modal): activating an
  // outfit slide toggles which saved outfit's member strip renders below the
  // carousel; activating a member opens its DetailPanel.
  const [expandedOutfitId, setExpandedOutfitId] = useState(null);
  const expandedOutfit = outfits.find(o => o.id === expandedOutfitId) || null;

  // Discriminated slide model (design ADR-2): one slide per favorited
  // product and per saved outfit. Product activation opens DetailPanel
  // directly; outfit activation toggles the inline member strip.
  const slides = useMemo(() => {
    const productSlides = items.map(f => ({
      kind: 'product',
      id: f.url,
      title: f.nombre || f.url,
      cta: 'Ver detalle',
      image: f.img,
      descontinuado: f.descontinuado,
      onActivate: () => onOpenDetail?.(f),
    }));
    const outfitSlides = outfits.map(o => ({
      kind: 'outfit',
      id: `outfit-${o.id}`,
      title: o.nombre || 'Outfit',
      cta: 'Ver outfit',
      members: o.slots || [],
      onActivate: () => setExpandedOutfitId(prev => (prev === o.id ? null : o.id)),
    }));
    return [...productSlides, ...outfitSlides];
  }, [items, outfits, onOpenDetail]);

  async function handleRefresh() {
    const ok = await rescrapeFavoritos();
    if (ok) {
      onSetScraping?.();
      onStartPolling?.(() => onRefreshFavoritos?.());
    }
  }

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Header */}
      <div style={{
        padding:'.65rem 1.25rem', background:'var(--s1)',
        borderBottom:'1px solid var(--bd)',
        display:'flex', flexWrap:'wrap', gap:8, alignItems:'center',
        position:'sticky', top:0, zIndex:10,
      }}>
        <div>
          <div style={{ fontSize:'.85rem', fontWeight:800, color:'var(--t1)' }}>
            ⭐ Favoritos
          </div>
          <div style={{ fontSize:'.65rem', color:'var(--t4)' }}>
            {items.length} producto{items.length === 1 ? '' : 's'} guardado{items.length === 1 ? '' : 's'}
          </div>
        </div>

        {!isEmpty && (
          <div role="group" aria-label="Modo de vista de favoritos" style={{ display:'flex', gap:8, marginLeft:'auto' }}>
            <button
              type="button"
              className="favoritos-toggle-btn"
              aria-pressed={viewMode === 'carousel'}
              aria-label="Vista carrusel"
              title="Vista carrusel"
              onClick={() => handleSetViewMode('carousel')}
              style={{
                width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center',
                borderRadius:8, border:'1.5px solid var(--bd2)', cursor:'pointer',
                background: viewMode === 'carousel' ? 'var(--p)' : 'var(--s2)',
                color: viewMode === 'carousel' ? '#fff' : 'var(--t3)',
              }}>
              <LayoutGrid size={18} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="favoritos-toggle-btn"
              aria-pressed={viewMode === 'list'}
              aria-label="Vista lista"
              title="Vista lista"
              onClick={() => handleSetViewMode('list')}
              style={{
                width:44, height:44, display:'flex', alignItems:'center', justifyContent:'center',
                borderRadius:8, border:'1.5px solid var(--bd2)', cursor:'pointer',
                background: viewMode === 'list' ? 'var(--p)' : 'var(--s2)',
                color: viewMode === 'list' ? '#fff' : 'var(--t3)',
              }}>
              <List size={18} aria-hidden="true" />
            </button>
          </div>
        )}

        <button
          onClick={handleRefresh}
          disabled={scrapeStatus === 'RUNNING'}
          style={{
            marginLeft: isEmpty ? 'auto' : 0, padding:'5px 12px', borderRadius:16, border:'none',
            cursor: scrapeStatus === 'RUNNING' ? 'default' : 'pointer',
            fontSize:'.72rem', fontWeight:700,
            background: scrapeStatus === 'RUNNING' ? 'var(--s2)' : 'var(--p)',
            color: scrapeStatus === 'RUNNING' ? 'var(--t4)' : '#fff',
            opacity: scrapeStatus === 'RUNNING' ? .6 : 1,
          }}>
          {scrapeStatus === 'RUNNING' ? 'Actualizando...' : '↻ Actualizar favoritos'}
        </button>
      </div>

      {/* Body */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>

        {isEmpty && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Todavía no marcaste productos como favoritos.
            Usá el botón ☆ en cada producto del catálogo.
          </div>
        )}

        {!isEmpty && viewMode === 'carousel' && (
          <>
            <TiltCarousel slides={slides} />

            {expandedOutfit && (
              <div style={{
                marginTop:20, display:'flex', flexDirection:'column', gap:8,
                maxWidth:480, marginLeft:'auto', marginRight:'auto',
              }}>
                <div style={{ fontSize:'.72rem', fontWeight:700, color:'var(--t3)' }}>
                  Prendas de "{expandedOutfit.nombre || 'Outfit'}"
                </div>
                {(expandedOutfit.slots || []).map((m, i) => (
                  <button
                    key={i}
                    type="button"
                    className="favoritos-outfit-member"
                    onClick={() => onOpenDetail?.(m)}
                    style={{
                      display:'flex', alignItems:'center', gap:10, minHeight:44,
                      background:'var(--s2)', border:'1px solid var(--bd)', borderRadius:8,
                      padding:'.5rem .65rem', cursor:'pointer', textAlign:'left',
                    }}>
                    {m.img && (
                      <img
                        src={m.img}
                        alt={m.nombre}
                        loading="lazy"
                        style={{ width:44, height:44, objectFit:'cover', borderRadius:6,
                                 flexShrink:0, border:'1px solid var(--bd)' }}
                        onError={e => { e.target.style.display = 'none'; }}
                      />
                    )}
                    <div style={{ flex:1, minWidth:0 }}>
                      <div style={{
                        fontSize:'.78rem', fontWeight:600, color:'var(--t1)',
                        overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                      }}>{m.nombre || '—'}</div>
                      <div style={{ fontSize:'.7rem', color:'var(--t3)' }}>{m.sitio}</div>
                    </div>
                    {m.precio > 0 && (
                      <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--p2)', flexShrink:0 }}>
                        ${fmt(m.precio)}
                      </div>
                    )}
                  </button>
                ))}
              </div>
            )}
          </>
        )}

        {!isEmpty && viewMode === 'list' && (
        <>
        {/* Saved outfits section — shown only when at least one exists */}
        {outfits.length > 0 && (
          <div style={{ marginBottom:'1.5rem' }}>
            <div style={{ fontSize:'.78rem', fontWeight:800, color:'var(--t1)', marginBottom:8 }}>
              👕 Outfits guardados
            </div>
            <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
              {outfits.map(o => (
                <SavedOutfitCard
                  key={o.id}
                  outfit={o}
                  onDelete={onDeleteSavedOutfit}
                  onRename={onRenameSavedOutfit}
                  onOpenDetail={onOpenDetail}
                />
              ))}
            </div>
          </div>
        )}

        {/* Individual favorited products */}
        {items.length > 0 && (
          <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
            {items.map(f => (
              <div key={f.url}
                onClick={() => onOpenDetail?.(f)}
                style={{
                  display:'flex', gap:10, alignItems:'flex-start',
                  background:'var(--s2)', borderRadius:10, padding:'.75rem',
                  border:'1.5px solid var(--bd)', cursor:'pointer',
                  transition:'border-color .15s',
                }}
                onMouseOver={e => e.currentTarget.style.borderColor = 'var(--p2)'}
                onMouseOut={e => e.currentTarget.style.borderColor = 'var(--bd)'}>

                {f.img && (
                  <img
                    src={f.img}
                    alt={f.nombre}
                    loading="lazy"
                    style={{ width:64, height:64, objectFit:'cover', borderRadius:8,
                             flexShrink:0, border:'1px solid var(--bd)' }}
                    onError={e => { e.target.style.display = 'none'; }}
                  />
                )}

                <div style={{ flex:1, minWidth:0, display:'flex', flexDirection:'column', gap:4 }}>
                  <div style={{ display:'flex', alignItems:'center', gap:8 }}>
                    <div style={{
                      fontSize:'.8rem', fontWeight:600, color:'var(--t1)', flex:1,
                      overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                    }}>{f.nombre || f.url}</div>

                    {f.descontinuado && (
                      <span style={{
                        fontSize:'.6rem', fontWeight:700, color: SEMANTIC.negative,
                        background: `color-mix(in srgb, ${SEMANTIC.negative} 12%, transparent)`, padding:'2px 8px', borderRadius:12,
                        whiteSpace:'nowrap',
                      }}>Descontinuado</span>
                    )}
                    <button
                      onClick={e => { e.stopPropagation(); onDeleteFavorito?.(f.url); }}
                      title="Quitar de favoritos"
                      style={{
                        background:'var(--s3)', border:'1px solid var(--bd)',
                        borderRadius:6, cursor:'pointer',
                        fontSize:'.72rem', color:'var(--t2)',
                        padding:'2px 7px', flexShrink:0, lineHeight:1.4,
                      }}>✕</button>
                  </div>

                  <div style={{ display:'flex', gap:8, alignItems:'center' }}>
                    <span style={{ fontSize:'.65rem', color:'var(--t4)' }}>{f.sitio}</span>
                    {f.precio > 0 && (
                      <span style={{ fontSize:'.72rem', fontWeight:700, color:'var(--p2)' }}>
                        ${fmt(f.precio)}
                      </span>
                    )}
                  </div>

                  {!f.descontinuado && <BuySignal url={f.url}/>}
                </div>
              </div>
            ))}
          </div>
        )}
        </>
        )}
      </div>
    </div>
  );
}
