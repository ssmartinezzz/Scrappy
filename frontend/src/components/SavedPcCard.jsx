import { useState } from 'react';
import { fmt } from '../api';

const SLOT_LABELS = {
  mother: 'Motherboard',
  cpu: 'CPU',
  ram: 'RAM',
  gabinete: 'Gabinete',
  fuente: 'Fuente',
  gpu: 'Placa de video',
  almacenamiento: 'Almacenamiento',
};

function slotLabel(slot) {
  return SLOT_LABELS[slot] ?? slot;
}

export default function SavedPcCard({ pc, onDelete, onRename }) {
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState(pc.nombre || 'PC');

  function startEdit(e) {
    e.stopPropagation();
    setEditName(pc.nombre || 'PC');
    setEditing(true);
  }

  function confirmEdit() {
    const name = editName.trim();
    if (name && name !== pc.nombre) {
      onRename?.(pc.id, name);
    }
    setEditing(false);
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') confirmEdit();
    if (e.key === 'Escape') setEditing(false);
  }

  const picks = pc.picks || [];

  return (
    <div style={{
      background:'var(--s2)', borderRadius:10, padding:'.75rem',
      border:'1.5px solid var(--bd)',
      display:'flex', flexDirection:'column', gap:8,
    }}>
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
            onDoubleClick={startEdit}
            title="Doble clic para renombrar"
            style={{
              flex:1, fontSize:'.82rem', fontWeight:700, color:'var(--t1)',
              overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
            }}>
            {pc.nombre || 'PC'}
          </div>
        )}
        <div style={{ fontSize:'.75rem', fontWeight:700, color:'var(--t2)', whiteSpace:'nowrap' }}>
          ${fmt(pc.totalEstimado)}
        </div>
        <button
          onClick={() => onDelete?.(pc.id)}
          title="Eliminar PC"
          style={{
            background:'none', border:'none', cursor:'pointer',
            fontSize:'.8rem', color:'var(--t4)', padding:'2px 4px',
          }}>✕</button>
      </div>

      {picks.length > 0 && (
        <div style={{ display:'flex', flexDirection:'column', gap:8 }}>
          {picks.map((p, i) => (
            <div key={i} style={{
              display:'flex', gap:10, alignItems:'center',
              background:'var(--s1)', borderRadius:8, padding:'.5rem .65rem',
              border:'1px solid var(--bd)',
            }}>
              <span style={{
                fontSize:'.62rem', fontWeight:700, color:'#fff', background:'var(--p)',
                borderRadius:999, padding:'2px 8px', textTransform:'uppercase',
                letterSpacing:'.04em', whiteSpace:'nowrap', flexShrink:0,
              }}>{slotLabel(p.slot)}</span>
              <div style={{
                flex:1, minWidth:0, fontSize:'.78rem', fontWeight:600, color:'var(--t1)',
                overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
              }}>{p.nombre || '—'}</div>
              <div style={{ display:'flex', alignItems:'baseline', gap:6, flexShrink:0 }}>
                <span style={{ fontSize:'.75rem', fontWeight:700, color:'var(--p2)' }}>
                  ${fmt(p.precio)}
                </span>
                {p.precioActual != null && p.precioActual !== p.precio && (
                  <span style={{ fontSize:'.68rem', color:'var(--t4)' }}>
                    hoy ${fmt(p.precioActual)}
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
