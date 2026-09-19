import { useState } from 'react';
import { ShoppingBag } from 'lucide-react';
import { formatFecha } from '../lib/fechas';
import { ImageWithFallback } from './ui/image-with-fallback';
import { fmt } from '../api';

// Same ShoppingBag treatment as ui/category-card.jsx / ui/outfit-collage.jsx /
// FavoritosPanel.jsx: missing image -> placeholder icon, never a hidden <img>.
function ImgFallbackIcon({ size = 20 }) {
  return <ShoppingBag aria-hidden="true" size={size} className="text-t4" strokeWidth={1.5} />;
}

export default function SavedOutfitCard({ outfit, onDelete, onRename, onOpenDetail }) {
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
  const dateStr = outfit.createdAt ? formatFecha(outfit.createdAt) : '';

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

      {/* Collapsed: small thumbnails (ImageWithFallback: missing/broken image
          -> placeholder icon, never a silently-skipped or hidden <img>) */}
      {!expanded && slots.length > 0 && (
        <div style={{ display:'flex', gap:6, flexWrap:'wrap' }}>
          {slots.map((s, i) => (
            <ImageWithFallback
              key={i}
              src={s.img}
              alt={s.nombre}
              loading="lazy"
              title={s.nombre}
              className="h-12 w-12 rounded-md border border-border object-cover"
              fallbackClassName="flex h-12 w-12 items-center justify-center rounded-md border border-border bg-s3"
              fallback={<ImgFallbackIcon size={18} />}
            />
          ))}
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
              <ImageWithFallback
                src={s.img}
                alt={s.nombre}
                loading="lazy"
                className="h-16 w-16 flex-shrink-0 rounded-md border border-border object-cover"
                fallbackClassName="flex h-16 w-16 flex-shrink-0 items-center justify-center rounded-md border border-border bg-s3"
                fallback={<ImgFallbackIcon size={22} />}
              />
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
