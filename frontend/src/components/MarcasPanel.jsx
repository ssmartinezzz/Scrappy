import { useEffect, useState, useCallback } from 'react';
import { fetchMarcasBrowser, fetchData, fmt, BADGE_LABELS } from '../api';
import { SEMANTIC } from '../lib/colors';

const RUBROS = [
  { k:'',             icon:'🛍', l:'Todas'       },
  { k:'indumentaria', icon:'👕', l:'Indumentaria'},
  { k:'tecnologia',   icon:'💻', l:'Tecnología'  },
  { k:'suplementos',  icon:'💊', l:'Suplementos' },
];

const SORT_OPTS = [
  { v:'count',       l:'Más productos' },
  { v:'precio_asc',  l:'Precio ↑'      },
  { v:'precio_desc', l:'Precio ↓'      },
];

// ─── Tarjeta de marca (estilo actress card) ──────────────────────────────────
function MarcaCard({ marca, onClick }) {
  const [hovered, setHovered] = useState(false);
  const img = marca.img || marca.bestPick?.img || '';

  return (
    <div
      onClick={() => onClick(marca)}
      onMouseOver={() => setHovered(true)}
      onMouseOut={() => setHovered(false)}
      style={{
        position:'relative', cursor:'pointer', borderRadius:10, overflow:'hidden',
        aspectRatio:'2/3',   // retrato estilo actress card
        background:'var(--s2)', border:`1px solid ${hovered?'var(--p)':'var(--bd)'}`,
        transition:'border-color .12s, transform .15s, box-shadow .15s',
        transform: hovered ? 'translateY(-4px)' : 'none',
        boxShadow: hovered ? '0 14px 40px rgba(0,0,0,.65)' : 'none',
      }}>

      {/* Background image */}
      {img ? (
        <img src={img} alt={marca.marca} loading="lazy"
          style={{ width:'100%', height:'100%', objectFit:'cover', display:'block' }}
          onError={e => { e.target.style.display='none'; }}/>
      ) : (
        <div style={{ width:'100%', height:'100%', display:'flex',
                      alignItems:'center', justifyContent:'center',
                      fontSize:'2.5rem', background:'var(--s3)' }}>
          {marca.rubro === 'tecnologia' ? '💻' : marca.rubro === 'suplementos' ? '💊' : '👕'}
        </div>
      )}

      {/* Gradient */}
      <div style={{
        position:'absolute', inset:0,
        background: hovered
          ? 'linear-gradient(to top, rgba(0,0,0,.92) 55%, rgba(0,0,0,.15) 80%)'
          : 'linear-gradient(to top, rgba(0,0,0,.85) 45%, transparent 70%)',
        transition:'all .2s',
      }}/>

      {/* Count badge */}
      <div style={{
        position:'absolute', top:8, right:8,
        background:'rgba(0,0,0,.65)', color:'var(--t2)',
        fontSize:'.6rem', fontWeight:700, padding:'2px 7px', borderRadius:12,
      }}>
        {marca.count}
      </div>

      {/* Brand info */}
      <div style={{ position:'absolute', bottom:0, left:0, right:0, padding:'10px 10px 9px' }}>
        <div style={{ fontSize:'.88rem', fontWeight:900, color:'#fff',
                      letterSpacing:'-.01em', lineHeight:1.2 }}>
          {marca.marca}
        </div>
        {hovered && (
          <>
            <div style={{ fontSize:'.6rem', color:'rgba(255,255,255,.65)', marginTop:3 }}>
              {marca.topCats || '—'}
            </div>
            <div style={{ display:'flex', gap:8, marginTop:5, alignItems:'baseline' }}>
              <span style={{ fontSize:'.72rem', fontWeight:800, color: SEMANTIC.positive }}>
                desde ${fmt(marca.precioMin)}
              </span>
              {marca.precioMax > marca.precioMin && (
                <span style={{ fontSize:'.6rem', color:'rgba(255,255,255,.45)' }}>
                  hasta ${fmt(marca.precioMax)}
                </span>
              )}
            </div>
          </>
        )}
        {!hovered && (
          <div style={{ fontSize:'.68rem', color:'rgba(255,255,255,.6)', marginTop:2 }}>
            med. ${fmt(marca.mediana)}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Vista de productos de una marca ─────────────────────────────────────────
function MarcaDetail({ marca, onBack, onProductClick }) {
  const [prods,   setProds]   = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetchData({ marca: marca.marca, size: 48, page: 1, orden:'precio_asc' })
      .then(d => { setProds(d?.productos || []); setLoading(false); });
  }, [marca.marca]);

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Header */}
      <div style={{
        padding:'.75rem 1.25rem', background:'var(--s1)',
        borderBottom:'1px solid var(--bd)',
        display:'flex', gap:12, alignItems:'center',
        position:'sticky', top:0, zIndex:10,
      }}>
        <button onClick={onBack} style={{
          background:'none', border:'none', cursor:'pointer',
          color:'var(--p2)', fontSize:'.85rem', padding:0, flexShrink:0,
        }}>← Marcas</button>

        {marca.img && (
          <img src={marca.img} alt="" width="42" height="42"
            style={{ borderRadius:8, objectFit:'cover' }}
            onError={e => e.target.style.display='none'}/>
        )}
        <div>
          <div style={{ fontSize:'1rem', fontWeight:900, color:'var(--t1)' }}>
            {marca.marca}
          </div>
          <div style={{ fontSize:'.68rem', color:'var(--t4)' }}>
            {marca.count} productos · {marca.topCats} · med. ${fmt(marca.mediana)}
          </div>
        </div>
      </div>

      {/* Product grid */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {loading && (
          <div style={{ textAlign:'center', color:'var(--t4)', padding:'2rem' }}>
            Cargando productos de {marca.marca}...
          </div>
        )}
        <div style={{
          display:'grid', gap:10,
          gridTemplateColumns:'repeat(auto-fill, minmax(160px, 1fr))',
        }}>
          {prods.map((p, i) => (
            <div key={p.url || i}
              onClick={() => onProductClick(p)}
              style={{
                background:'var(--s2)', borderRadius:10, overflow:'hidden',
                border:'1px solid var(--bd)', cursor:'pointer',
                transition:'border-color .12s',
              }}
              onMouseOver={e => e.currentTarget.style.borderColor='var(--p)'}
              onMouseOut={e => e.currentTarget.style.borderColor='var(--bd)'}>
              {p.img ? (
                <img src={p.img} alt={p.nombre} loading="lazy"
                  style={{ width:'100%', aspectRatio:'1', objectFit:'cover' }}
                  onError={e => e.target.style.display='none'}/>
              ) : (
                <div style={{ width:'100%', aspectRatio:'1', background:'var(--s3)',
                               display:'flex', alignItems:'center', justifyContent:'center' }}>
                  🛍
                </div>
              )}
              <div style={{ padding:'.55rem .65rem' }}>
                <div style={{
                  fontSize:'.72rem', fontWeight:600, color:'var(--t1)',
                  overflow:'hidden', display:'-webkit-box',
                  WebkitLineClamp:2, WebkitBoxOrient:'vertical', lineHeight:1.35,
                }}>
                  {p.nombre}
                </div>
                <div style={{ fontSize:'.78rem', fontWeight:800, color:'var(--t1)', marginTop:4 }}>
                  ${fmt(p.precio)}
                </div>
                {p.ml?.badge && BADGE_LABELS[p.ml.badge] && (
                  <div style={{ fontSize:'.58rem', color:'var(--p2)', marginTop:2 }}>
                    {BADGE_LABELS[p.ml.badge]}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ─── MarcasPanel ─────────────────────────────────────────────────────────────
export default function MarcasPanel({ onProductClick }) {
  const [marcas,  setMarcas]  = useState([]);
  const [loading, setLoading] = useState(false);
  const [rubro,   setRubro]   = useState('');
  const [sort,    setSort]    = useState('count');
  const [q,       setQ]       = useState('');
  const [selMarca, setSelMarca] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    const data = await fetchMarcasBrowser({ rubro, sort, q });
    setMarcas(Array.isArray(data) ? data : []);
    setLoading(false);
  }, [rubro, sort, q]);

  useEffect(() => { load(); }, [rubro, sort]);

  // Debounce search
  useEffect(() => {
    const t = setTimeout(() => load(), 350);
    return () => clearTimeout(t);
  }, [q]);

  if (selMarca) return (
    <MarcaDetail
      marca={selMarca}
      onBack={() => setSelMarca(null)}
      onProductClick={onProductClick}/>
  );

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
          <div style={{ fontSize:'.88rem', fontWeight:800, color:'var(--t1)' }}>
            🏷 Marcas
          </div>
          <div style={{ fontSize:'.62rem', color:'var(--t4)' }}>
            {marcas.length} marcas · clickeá para ver sus productos
          </div>
        </div>

        {/* Rubro filter */}
        <div style={{ display:'flex', gap:4 }}>
          {RUBROS.map(r => (
            <button key={r.k} onClick={() => setRubro(r.k)} style={{
              padding:'4px 9px', borderRadius:16, border:'none',
              cursor:'pointer', fontSize:'.7rem', fontWeight:700,
              background: rubro===r.k ? 'var(--p)' : 'var(--s2)',
              color: rubro===r.k ? '#fff' : 'var(--t4)',
            }}>{r.icon}</button>
          ))}
        </div>

        {/* Sort */}
        <select value={sort} onChange={e => setSort(e.target.value)}
          style={{
            padding:'5px 8px', borderRadius:8, border:'1.5px solid var(--bd2)',
            background:'var(--s2)', color:'var(--t3)', font:'.72rem var(--font)',
          }}>
          {SORT_OPTS.map(o => (
            <option key={o.v} value={o.v}>{o.l}</option>
          ))}
        </select>

        {/* Search */}
        <input value={q} onChange={e => setQ(e.target.value)}
          placeholder="Buscar marca..."
          style={{
            padding:'5px 10px', borderRadius:8, border:'1.5px solid var(--bd2)',
            background:'var(--s3)', color:'var(--t1)', font:'.75rem var(--font)',
            outline:'none', width:130, marginLeft:'auto',
          }}/>
      </div>

      {/* Grilla de marcas */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {loading && (
          <div style={{ textAlign:'center', color:'var(--t4)', padding:'3rem' }}>
            Cargando marcas...
          </div>
        )}
        {!loading && marcas.length === 0 && (
          <div style={{ textAlign:'center', color:'var(--t4)', padding:'3rem' }}>
            Sin datos. Ejecutá un scraping primero.
          </div>
        )}
        {!loading && marcas.length > 0 && (
          <div style={{
            display:'grid', gap:10,
            gridTemplateColumns:'repeat(auto-fill, minmax(140px, 1fr))',
          }}>
            {marcas.map(m => (
              <MarcaCard key={m.marca} marca={m} onClick={setSelMarca}/>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
