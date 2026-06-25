import { useState } from 'react';
import BuySignal from './BuySignal';
import { rescrapeFavoritos, fmt } from '../api';
import { SEMANTIC } from '../lib/colors';

// ─── SavedOutfitCard ──────────────────────────────────────────────────────────
function SavedOutfitCard({ outfit, onDelete, onRename }) {
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState(outfit.nombre || 'Outfit');

  function startEdit() {
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
      {/* Name + delete */}
      <div style={{ display:'flex', alignItems:'center', gap:8 }}>
        {editing ? (
          <input
            autoFocus
            value={editName}
            onChange={e => setEditName(e.target.value)}
            onBlur={confirmEdit}
            onKeyDown={handleKeyDown}
            style={{
              flex:1, fontSize:'.82rem', fontWeight:700, color:'var(--t1)',
              background:'var(--s3)', border:'1px solid var(--p2)',
              borderRadius:4, padding:'2px 8px',
            }}
          />
        ) : (
          <div
            onClick={startEdit}
            title="Clic para renombrar"
            style={{
              flex:1, fontSize:'.82rem', fontWeight:700, color:'var(--t1)',
              cursor:'pointer', overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
            }}>
            {outfit.nombre || 'Outfit'}
          </div>
        )}
        <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--t2)', whiteSpace:'nowrap' }}>
          ${fmt(outfit.totalEstimado)}
        </div>
        <button
          onClick={() => onDelete?.(outfit.id)}
          title="Eliminar outfit"
          style={{
            background:'none', border:'none', cursor:'pointer',
            fontSize:'.8rem', color:'var(--t4)', padding:'2px 4px',
          }}>✕</button>
      </div>

      {/* Slot thumbnails */}
      {slots.length > 0 && (
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
  savedOutfits, onDeleteSavedOutfit, onRenameSavedOutfit,
}) {
  const items = favoritos || [];
  const outfits = savedOutfits || [];

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

        <button
          onClick={handleRefresh}
          disabled={scrapeStatus === 'RUNNING'}
          style={{
            marginLeft:'auto', padding:'5px 12px', borderRadius:16, border:'none',
            cursor: scrapeStatus === 'RUNNING' ? 'default' : 'pointer',
            fontSize:'.72rem', fontWeight:700,
            background: scrapeStatus === 'RUNNING' ? 'var(--s2)' : 'var(--p)',
            color: scrapeStatus === 'RUNNING' ? 'var(--t4)' : '#fff',
            opacity: scrapeStatus === 'RUNNING' ? .6 : 1,
          }}>
          {scrapeStatus === 'RUNNING' ? 'Actualizando...' : '↻ Actualizar favoritos'}
        </button>
      </div>

      {/* List */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>

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
                />
              ))}
            </div>
          </div>
        )}

        {/* Individual favorited products */}
        {items.length === 0 && outfits.length === 0 && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Todavía no marcaste productos como favoritos.
            Usá el botón ☆ en cada producto del catálogo.
          </div>
        )}

        {items.length > 0 && (
          <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
            {items.map(f => (
              <div key={f.url}
                onClick={() => onOpenDetail?.(f)}
                style={{
                  display:'flex', flexDirection:'column', gap:6,
                  background:'var(--s2)', borderRadius:10, padding:'.75rem',
                  border:'1.5px solid var(--bd)', cursor:'pointer',
                  transition:'border-color .15s',
                }}
                onMouseOver={e => e.currentTarget.style.borderColor = 'var(--p2)'}
                onMouseOut={e => e.currentTarget.style.borderColor = 'var(--bd)'}>

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
                </div>

                <div style={{ fontSize:'.65rem', color:'var(--t4)' }}>
                  {f.sitio}
                </div>

                {!f.descontinuado && <BuySignal url={f.url}/>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
