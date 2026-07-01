import { forwardRef, useEffect, useRef, useState } from 'react';

const ORDEN_OPTS = [
  { v:'precio_asc',  l:'↑ Precio' },
  { v:'precio_desc', l:'↓ Precio' },
  { v:'nombre_asc',  l:'A→Z'      },
  { v:'desc_pct',    l:'% Oferta' },
  { v:'composite',   l:'ML Score' },
];

// forwardRef: AppLayout's useStickyFilterBar hook observes this root node's
// height via ResizeObserver (--catalogo-hero-h) so the Catálogo filter bar
// can stick right below it. The `.search-hero` class (styles.css) makes this
// itself a sticky chrome layer (top: var(--sticky-offset)), stacked with
// topbar/tab-bar — Catálogo-only, since SearchHero only renders there.
const SearchHero = forwardRef(function SearchHero({
  busq, view, orden, total, onBusq, onView, onOrden,
}, ref) {
  const [val, setVal] = useState(busq || '');
  const timerRef = useRef(null);

  useEffect(() => { setVal(busq || ''); }, [busq]);

  function handleInput(e) {
    setVal(e.target.value);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => onBusq(e.target.value), 380);
  }

  return (
    <div ref={ref} className="search-hero">
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
    </div>
  );
});

export default SearchHero;
