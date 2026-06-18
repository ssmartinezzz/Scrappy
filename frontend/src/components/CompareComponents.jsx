// CompareBar.jsx
import { fmt } from '../api';

export function CompareBar({ items, onRemove, onClear, onCompare }) {
  return (
    <div className="compare-bar">
      <span className="compare-bar-title">⚖ Comparar</span>
      <div className="compare-items">
        {items.map(p => (
          <div key={p.url} className="compare-chip">
            <span title={p.nombre}>{p.nombre}</span>
            <button onClick={() => onRemove(p)}>✕</button>
          </div>
        ))}
      </div>
      <button className="btn-primary" style={{ padding: '7px 14px', fontSize: '.78rem' }}
              onClick={onCompare} disabled={items.length < 2}>
        Ver comparación
      </button>
      <button className="btn-sm btn-ghost" onClick={onClear}>Limpiar</button>
    </div>
  );
}

// CompareModal.jsx
export function CompareModal({ items, onClose }) {
  const minPrecio = Math.min(...items.map(p => p.precio));
  const rows = [
    { label: 'Imagen',       fn: p => p.img ? <img src={p.img} alt={p.nombre} style={{ width:'100%', aspectRatio:'1', objectFit:'cover' }} /> : null },
    { label: 'Nombre',       fn: p => <strong>{p.nombre}</strong> },
    { label: 'Marca',        fn: p => p.marca || p.sitio || '—' },
    { label: 'Precio',       fn: p => `ARS $${fmt(p.precio)}`, hl: p => p.precio === minPrecio },
    { label: 'Precio orig',  fn: p => p.precioOrig || '—' },
    { label: 'Categoría',    fn: p => p.categoria || '—' },
    { label: 'Género',       fn: p => p.genero || '—' },
    { label: 'Talles',       fn: p => (p.talles||[]).join(', ') || '—' },
    { label: 'Badge ML',     fn: p => p.ml?.badge || '—' },
    { label: 'Tienda',       fn: p => p.sitio },
    { label: 'Link',         fn: p => p.url ? <a href={p.url} target="_blank" rel="noopener noreferrer" style={{ color:'var(--p2)' }}>Abrir ↗</a> : '—' },
  ];
  return (
    <div className="compare-modal-backdrop" onClick={onClose}>
      <div className="compare-modal-inner" onClick={e => e.stopPropagation()}>
        <div className="compare-modal-header">
          <h2 style={{ fontSize:'1rem', fontWeight:700 }}>⚖ Comparación</h2>
          <button className="detail-close" onClick={onClose}>✕</button>
        </div>
        <div style={{ overflowX:'auto' }}>
          <div style={{ display:'grid', gridTemplateColumns:`repeat(${items.length}, 1fr)` }}>
            {rows.map(row => (
              items.map((p, pi) => (
                <div key={`${row.label}-${pi}`}
                     className={`compare-cell ${row.hl?.(p) ? 'highlight' : ''}`}>
                  {typeof row.fn(p) === 'object' ? row.fn(p) : String(row.fn(p))}
                </div>
              )).concat(
                <div key={`lbl-${row.label}`}
                     className="compare-cell label"
                     style={{ gridColumn: `1/${items.length+1}` }}>
                  {row.label}
                </div>
              )
            )).reverse()}
          </div>
        </div>
      </div>
    </div>
  );
}
