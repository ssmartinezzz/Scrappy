import { useEffect, useRef, useState } from 'react';

const ORDEN_OPTS = [
  { v:'precio_asc',  l:'↑ Precio' },
  { v:'precio_desc', l:'↓ Precio' },
  { v:'nombre_asc',  l:'A→Z'      },
  { v:'desc_pct',    l:'% Oferta' },
  { v:'composite',   l:'ML Score' },
];

export default function SearchHero({
  busq, view, orden, total, topMarcas,
  marca: marcaFiltro, onBusq, onView, onOrden, onMarca,
}) {
  const [val, setVal] = useState(busq || '');
  const timerRef = useRef(null);

  useEffect(() => { setVal(busq || ''); }, [busq]);

  function handleInput(e) {
    setVal(e.target.value);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => onBusq(e.target.value), 380);
  }

  return (
    <div style={{ padding:'.65rem 1.25rem .4rem', background:'var(--s1)',
                  borderBottom:'1px solid var(--s3)' }}>
      {/* Row 1: search + sort + view */}
      <div style={{ display:'flex', gap:8, alignItems:'center' }}>
        <div style={{ position:'relative', flex:1 }}>
          <input
            value={val} onChange={handleInput}
            placeholder={`Buscar entre ${total > 0 ? total.toLocaleString('es-AR')+' productos' : 'productos'}...`}
            style={{
              width:'100%', padding:'7px 12px 7px 32px', borderRadius:8,
              border:'1.5px solid var(--bd2)', background:'var(--s2)',
              color:'var(--t1)', font:'.82rem var(--font)', outline:'none',
              boxSizing:'border-box',
            }}/>
          <span style={{ position:'absolute', left:10, top:'50%', transform:'translateY(-50%)',
                         fontSize:'.8rem', opacity:.4 }}>🔍</span>
        </div>

        <select value={orden} onChange={e => onOrden(e.target.value)}
          style={{
            padding:'7px 8px', borderRadius:8, border:'1.5px solid var(--bd2)',
            background:'var(--s2)', color:'var(--t3)', font:'.75rem var(--font)',
            flexShrink:0,
          }}>
          {ORDEN_OPTS.map(o => (
            <option key={o.v} value={o.v}>{o.l}</option>
          ))}
        </select>

        <button onClick={() => onView(view==='grid'?'list':'grid')}
          style={{ padding:'7px 10px', borderRadius:8, border:'1.5px solid var(--bd2)',
                   background:'var(--s2)', color:'var(--t4)', cursor:'pointer', fontSize:'.78rem' }}>
          {view === 'grid' ? '≡' : '⊞'}
        </button>
      </div>

      {/* Row 2: Marcas como breadcrumb chips */}
      {topMarcas && topMarcas.length > 0 && (
        <div style={{
          display:'flex', gap:4, marginTop:6, overflowX:'auto',
          scrollbarWidth:'none', flexWrap:'nowrap', paddingBottom:2,
        }}>
          {marcaFiltro && (
            <button onClick={() => onMarca('')}
              style={{
                padding:'2px 8px', borderRadius:12, flexShrink:0, fontSize:'.65rem',
                border:'1px solid var(--r)', background:'rgba(232,67,147,.12)',
                color:'var(--r)', cursor:'pointer', fontWeight:700,
              }}>
              ✕ {marcaFiltro}
            </button>
          )}
          {topMarcas.slice(0, 22).map(([marca, n]) => {
            const active = marcaFiltro === marca;
            if (active) return null; // already shown as X chip
            return (
              <button key={marca} onClick={() => onMarca(marca)}
                style={{
                  padding:'2px 9px', borderRadius:12, flexShrink:0, fontSize:'.66rem',
                  border:`1px solid ${active?'var(--p)':'var(--bd)'}`,
                  background: active ? 'rgba(163,113,247,.15)' : 'transparent',
                  color: active ? 'var(--p2)' : 'var(--t4)',
                  cursor:'pointer', whiteSpace:'nowrap', transition:'all .1s',
                }}>
                {marca} <span style={{opacity:.55}}>{n}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
