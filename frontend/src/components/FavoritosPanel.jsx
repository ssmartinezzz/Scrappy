import { useMemo, useState } from 'react';
import { LayoutGrid, List, ShoppingBag } from 'lucide-react';
import BuySignal from './BuySignal';
import SavedOutfitCard from './SavedOutfitCard';
import SavedPcCard from './SavedPcCard';
import { TiltCarousel } from './ui/tilt-carousel';
import { ImageWithFallback } from './ui/image-with-fallback';
import { fmt } from '../api';
import { SEMANTIC } from '../lib/colors';

// Shared fallback icon for every legacy-img spot below (spec: missing image
// -> placeholder, never a hidden/broken <img>) — same ShoppingBag treatment
// as ui/category-card.jsx / ui/outfit-collage.jsx.
function ImgFallbackIcon({ size = 20 }) {
  return <ShoppingBag aria-hidden="true" size={size} className="text-t4" strokeWidth={1.5} />;
}

const VIEW_MODE_KEY = 'favoritos:viewMode';

// ─── FavoritosPanel ───────────────────────────────────────────────────────────
export default function FavoritosPanel({
  favoritos, onOpenDetail, onDeleteFavorito,
  savedOutfits = [], savedPcs = [],
  onDeleteSavedOutfit, onRenameSavedOutfit,
  onDeleteSavedPc, onRenameSavedPc,
}) {
  const items = favoritos || [];
  const isEmpty = items.length === 0;

  // View-mode toggle (design ADR-4): view-local state, NOT lifted to the
  // AppLayout reducer — no other component consumes it. Lazy-initialised
  // from localStorage; default 'carousel' (spec: "First-ever visit").
  // Reads/writes are guarded: storage can throw (SecurityError when blocked,
  // QuotaExceededError when full) and this runs inside a useState
  // initializer during render — an uncaught throw here would take down the
  // whole /favoritos route, not just the toggle. Any unrecognized stored
  // value (corrupted, hand-edited, a retired/future mode) is whitelisted
  // back to 'carousel' rather than rendered as-is, which would match
  // neither the 'carousel' nor 'list' branch below and leave the body blank.
  const [viewMode, setViewMode] = useState(() => {
    if (typeof window === 'undefined') return 'carousel';
    try {
      return localStorage.getItem(VIEW_MODE_KEY) === 'list' ? 'list' : 'carousel';
    } catch {
      return 'carousel';
    }
  });
  function handleSetViewMode(mode) {
    setViewMode(mode);
    if (typeof window === 'undefined') return;
    try {
      localStorage.setItem(VIEW_MODE_KEY, mode);
    } catch {
      // Storage blocked/full — the toggle still works for this session,
      // it just won't persist across reloads.
    }
  }

  // Una sola tira abierta a la vez, identificada por colección + id: abrir una
  // PC cierra el outfit que estuviera abierto. Dos estados separados dejarían
  // dos tiras apiladas debajo del mismo carrusel.
  const [expandido, setExpandido] = useState(null); // { coleccion:'outfit'|'pc', id } | null
  const miembrosId = (coleccion, id) => `favoritos-${coleccion}-miembros-${id}`;
  const toggle = (coleccion, id) => setExpandido(prev =>
    prev && prev.coleccion === coleccion && prev.id === id ? null : { coleccion, id });

  // Slide model (design ADR-2 + nav-guardados-armadores D6). `kind:'outfit'`
  // es el slide de COLECCIÓN del carrusel —un collage de sus miembros más una
  // tira expandible—, no algo propio de la ropa: una PC guardada lo usa tal
  // cual, que es lo que "iguales a los outfits" quiere decir. Sus `picks`
  // ya traen `{nombre, img, sitio, precio}`, exactamente la forma que
  // `OutfitCollage` y la tira consumen, así que no hace falta adaptar nada.
  const slides = useMemo(() => {
    const productos = items.map(f => ({
      kind: 'product',
      id: f.url,
      title: f.nombre || f.url,
      cta: 'Ver detalle',
      image: f.img,
      descontinuado: f.descontinuado,
      onActivate: () => onOpenDetail?.(f),
    }));
    const coleccion = (lista, nombreColeccion, miembrosDe, etiqueta) => lista.map(x => ({
      kind: 'outfit',
      id: `${nombreColeccion}-${x.id}`,
      title: x.nombre || etiqueta,
      cta: `Ver ${etiqueta.toLowerCase()}`,
      members: miembrosDe(x),
      expanded: expandido?.coleccion === nombreColeccion && expandido?.id === x.id,
      controlsId: miembrosId(nombreColeccion, x.id),
      onActivate: () => toggle(nombreColeccion, x.id),
    }));
    return [
      ...productos,
      ...coleccion(savedOutfits, 'outfit', o => o.slots || [], 'Outfit'),
      ...coleccion(savedPcs, 'pc', p => p.picks || [], 'PC'),
    ];
  }, [items, savedOutfits, savedPcs, onOpenDetail, expandido]);

  const abierto = expandido && (expandido.coleccion === 'outfit'
    ? savedOutfits.find(o => o.id === expandido.id)
    : savedPcs.find(p => p.id === expandido.id));
  const miembrosAbiertos = !abierto ? []
    : (expandido.coleccion === 'outfit' ? abierto.slots : abierto.picks) || [];
  const hayAlgo = items.length > 0 || savedOutfits.length > 0 || savedPcs.length > 0;

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

        {hayAlgo && (
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
      </div>

      {/* Body */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>

        {/* Sin nada guardado nombramos las TRES cosas que se pueden guardar:
            un solo mensaje sobre productos escondería que los outfits y las
            PCs también viven acá. */}
        {!hayAlgo && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', lineHeight:1.9 }}>
            <div>Todavía no marcaste productos como favoritos. Usá el botón ☆ en cada producto del catálogo.</div>
            <div>Todavía no guardaste ningún outfit. Armá uno en Armadores → Outfits.</div>
            <div>Todavía no guardaste ninguna PC. Armá una en Armadores → PCs.</div>
          </div>
        )}

        {hayAlgo && viewMode === 'carousel' && (
          <>
            <TiltCarousel slides={slides} />

            {abierto && (
              <div
                id={miembrosId(expandido.coleccion, abierto.id)}
                role="region"
                aria-label={`Componentes de ${abierto.nombre || 'Guardado'}`}
                style={{
                  marginTop:20, display:'flex', flexDirection:'column', gap:8,
                  maxWidth:480, marginLeft:'auto', marginRight:'auto',
                }}>
                <div style={{ fontSize:'.72rem', fontWeight:700, color:'var(--t3)' }}>
                  {expandido.coleccion === 'outfit' ? 'Prendas' : 'Componentes'} de "{abierto.nombre || 'Guardado'}"
                </div>
                {miembrosAbiertos.map((m, i) => (
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
                    <ImageWithFallback
                      src={m.img}
                      alt={m.nombre}
                      loading="lazy"
                      className="h-11 w-11 flex-shrink-0 rounded-md border border-border object-cover"
                      fallbackClassName="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-md border border-border bg-s3"
                      fallback={<ImgFallbackIcon size={18} />}
                    />
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

        {/* La lista es donde viven renombrar y eliminar: el carrusel no tiene
            dónde ponerlos sin taparle el collage al slide. Las tres secciones
            se muestran siempre que haya algo, porque son colecciones
            independientes que comparten pantalla. */}
        {hayAlgo && viewMode === 'list' && (
        <>
        {savedOutfits.length > 0 && (
          <section style={{ marginBottom:'1.5rem' }}>
            <h2 style={{ fontSize:'.85rem', fontWeight:800, color:'var(--t1)', marginBottom:12 }}>
              👕 Outfits guardados
            </h2>
            <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
              {savedOutfits.map(o => (
                <SavedOutfitCard
                  key={o.id}
                  outfit={o}
                  onDelete={onDeleteSavedOutfit}
                  onRename={onRenameSavedOutfit}
                  onOpenDetail={onOpenDetail}
                />
              ))}
            </div>
          </section>
        )}

        {savedPcs.length > 0 && (
          <section style={{ marginBottom:'1.5rem' }}>
            <h2 style={{ fontSize:'.85rem', fontWeight:800, color:'var(--t1)', marginBottom:12 }}>
              🖥 PCs guardadas
            </h2>
            <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
              {savedPcs.map(p => (
                <SavedPcCard
                  key={p.id}
                  pc={p}
                  onDelete={onDeleteSavedPc}
                  onRename={onRenameSavedPc}
                />
              ))}
            </div>
          </section>
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

                <ImageWithFallback
                  src={f.img}
                  alt={f.nombre}
                  loading="lazy"
                  className="h-16 w-16 flex-shrink-0 rounded-lg border border-border object-cover"
                  fallbackClassName="flex h-16 w-16 flex-shrink-0 items-center justify-center rounded-lg border border-border bg-s3"
                  fallback={<ImgFallbackIcon size={24} />}
                />

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
