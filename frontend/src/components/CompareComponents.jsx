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

// `hl` gets the cheapest price so the row table needs no per-render closure.
const ROWS = [
  { label: 'Imagen',       fn: p => p.img ? <img src={p.img} alt={p.nombre} style={{ width:'100%', aspectRatio:'1', objectFit:'cover' }} /> : null },
  { label: 'Nombre',       fn: p => <strong>{p.nombre}</strong> },
  { label: 'Marca',        fn: p => p.marca || p.sitio || '—' },
  { label: 'Precio',       fn: p => `ARS $${fmt(p.precio)}`, hl: (p, minPrecio) => p.precio === minPrecio },
  { label: 'Precio orig',  fn: p => p.precioOrig != null ? `ARS $${fmt(p.precioOrig)}` : '—' },
  { label: 'Categoría',    fn: p => p.categoria || '—' },
  { label: 'Género',       fn: p => p.genero || '—' },
  { label: 'Talles',       fn: p => (p.talles||[]).join(', ') || '—' },
  { label: 'Badge ML',     fn: p => p.ml?.badge || '—' },
  { label: 'Tienda',       fn: p => p.sitio },
  { label: 'Link',         fn: p => p.url ? <a href={p.url} target="_blank" rel="noopener noreferrer" style={{ color:'var(--p2)' }}>Abrir ↗</a> : '—' },
];

// CompareModal.jsx
export function CompareModal({ items, onClose }) {
  const minPrecio = Math.min(...items.map(p => p.precio));
  return (
    <div className="compare-modal-backdrop">
      {/* Clicking outside the panel closes it; a real button, so it is reachable without a mouse too. */}
      <button type="button" className="compare-modal-scrim" aria-label="Cerrar comparación" onClick={onClose} />
      <div className="compare-modal-inner">
        <div className="compare-modal-header">
          <h2 style={{ fontSize:'1rem', fontWeight:700 }}>⚖ Comparación</h2>
          <button className="detail-close" onClick={onClose}>✕</button>
        </div>
        <div style={{ overflowX:'auto' }}>
          <div style={{ display:'grid', gridTemplateColumns:`repeat(${items.length}, minmax(150px, 1fr))` }}>
            {ROWS.map(row => [
              <div key={`lbl-${row.label}`}
                   className="compare-cell label"
                   style={{ gridColumn: `1/${items.length+1}` }}>
                {row.label}
              </div>,
              ...items.map(p => (
                <div key={`${row.label}-${p.url}`}
                     className={`compare-cell ${row.hl?.(p, minPrecio) ? 'highlight' : ''}`}>
                  {typeof row.fn(p) === 'object' ? row.fn(p) : String(row.fn(p))}
                </div>
              )),
            ])}
          </div>
        </div>
      </div>
    </div>
  );
}
