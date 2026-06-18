import { useEffect, useState, useCallback } from 'react';
import { fetchMejores, fmt, BADGE_LABELS } from '../api';

const RUBROS = [
  { k:'',             icon:'🛍', l:'Todos'        },
  { k:'indumentaria', icon:'👕', l:'Indumentaria' },
  { k:'tecnologia',   icon:'💻', l:'Tecnología'   },
  { k:'suplementos',  icon:'💊', l:'Suplementos'  },
];

const TIPO_META = {
  valor:   { icon:'🥇', color:'#3fb950', label:'Mejor precio/calidad' },
  premium: { icon:'💎', color:'#f0a500', label:'Premium accesible'    },
  histLow: { icon:'🏆', color:'#a371f7', label:'Mínimo histórico'     },
  oferta:  { icon:'🔥', color:'#fd6400', label:'Oferta real'           },
};

// Genera tagline a partir de datos estadísticos
function tagline(cat, pick, mediana) {
  if (!pick) return '';
  const badge = pick.badge;
  const pctil = pick.pctil;
  const pct = mediana > 0 ? ((mediana - pick.precio) / mediana * 100).toFixed(0) : null;
  
  if (badge === 'precio_historico_bajo') return 'Nunca estuvo tan barato — mínimo histórico';
  if (badge === 'oferta_real')           return 'Descuento verificado estadísticamente';
  if (pct && pct >= 25)  return `Un ${pct}% más barato que la media de ${cat}`;
  if (pct && pct >= 10)  return `Por debajo de la media — buena relación precio/calidad`;
  if (pctil && pctil <= 15) return `Percentil ${pctil}° — entre los más accesibles`;
  if (pick.segment === 'premium') return 'La opción premium más accesible ahora mismo';
  return `El mejor precio/calidad en ${cat} en este momento`;
}

// ─── Tarjeta de categoría (grilla) ───────────────────────────────────────────
function CatCard({ cat, onClick }) {
  const pick1 = cat.picks?.[0];
  const img   = cat.imgCat || pick1?.img || '';

  return (
    <div onClick={() => onClick(cat)}
      style={{
        position:'relative', borderRadius:12, overflow:'hidden',
        aspectRatio:'3/4', cursor:'pointer',
        background:'var(--s2)', border:'1px solid var(--bd)',
        transition:'transform .15s, box-shadow .15s',
      }}
      onMouseOver={e => {
        e.currentTarget.style.transform = 'translateY(-3px)';
        e.currentTarget.style.boxShadow = '0 12px 36px rgba(0,0,0,.6)';
      }}
      onMouseOut={e => {
        e.currentTarget.style.transform = 'none';
        e.currentTarget.style.boxShadow = 'none';
      }}>

      {/* Product image bg */}
      {img ? (
        <img src={img} alt={cat.categoria} loading="lazy"
          style={{ width:'100%', height:'100%', objectFit:'cover', display:'block' }}
          onError={e => e.target.style.display='none'}/>
      ) : (
        <div style={{ width:'100%', height:'100%', display:'flex', alignItems:'center',
                      justifyContent:'center', fontSize:'2rem' }}>🛍</div>
      )}

      {/* Gradient overlay */}
      <div style={{
        position:'absolute', inset:0,
        background:'linear-gradient(to top, rgba(0,0,0,.88) 45%, transparent 75%)',
      }}/>

      {/* Count badge */}
      <div style={{
        position:'absolute', top:8, right:8,
        background:'rgba(0,0,0,.6)', color:'var(--t3)',
        fontSize:'.58rem', fontWeight:700, padding:'2px 7px', borderRadius:12,
      }}>
        {(cat.count||0).toLocaleString('es-AR')}
      </div>

      {/* Category name + tagline */}
      <div style={{ position:'absolute', bottom:0, left:0, right:0, padding:'10px 10px 8px' }}>
        <div style={{ fontSize:'.82rem', fontWeight:800, color:'#fff', lineHeight:1.2 }}>
          {cat.categoria}
        </div>
        {pick1 && (
          <div style={{
            fontSize:'.6rem', color:'rgba(255,255,255,.7)', marginTop:3,
            lineHeight:1.35, display:'-webkit-box',
            WebkitLineClamp:2, WebkitBoxOrient:'vertical', overflow:'hidden',
          }}>
            {tagline(cat.categoria, pick1, cat.mediana)}
          </div>
        )}
        {/* Best price */}
        {pick1 && (
          <div style={{ fontSize:'.75rem', fontWeight:800, color:'#3fb950', marginTop:4 }}>
            desde ${fmt(pick1.precio)}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Vista de picks de una categoría ─────────────────────────────────────────
function CatDetail({ cat, onBack, onProductClick }) {
  return (
    <div style={{ padding:'1rem 1.25rem', maxWidth:680 }}>
      <button onClick={onBack} style={{
        background:'none', border:'none', cursor:'pointer',
        color:'var(--p2)', fontSize:'.8rem', marginBottom:12, padding:0,
        display:'flex', alignItems:'center', gap:5,
      }}>
        ← Volver
      </button>

      <h2 style={{ fontSize:'1.1rem', fontWeight:900, color:'var(--t1)', marginBottom:2 }}>
        {cat.categoria}
      </h2>
      <p style={{ fontSize:'.72rem', color:'var(--t4)', marginBottom:4 }}>
        {(cat.count||0).toLocaleString('es-AR')} productos · mediana ${fmt(cat.mediana)}
      </p>
      <p style={{ fontSize:'.78rem', color:'var(--p2)', marginBottom:'1.2rem', fontStyle:'italic' }}>
        "{tagline(cat.categoria, cat.picks?.[0], cat.mediana)}"
      </p>

      <div style={{ display:'flex', flexDirection:'column', gap:10 }}>
        {(cat.picks||[]).map((pick, i) => {
          const m = TIPO_META[pick.tipo] || TIPO_META.valor;
          const pctBajoMedia = cat.mediana > 0 && pick.precio > 0
            ? ((cat.mediana - pick.precio) / cat.mediana * 100).toFixed(0)
            : null;
          return (
            <div key={i}
              onClick={() => onProductClick(pick)}
              style={{
                display:'flex', gap:12, alignItems:'center',
                background:'var(--s2)', borderRadius:10, padding:'.75rem',
                border:`1.5px solid ${m.color}33`, cursor:'pointer',
                transition:'all .15s',
              }}
              onMouseOver={e => e.currentTarget.style.borderColor = m.color}
              onMouseOut={e => e.currentTarget.style.borderColor = `${m.color}33`}>

              {/* Rank icon */}
              <div style={{ fontSize:'1.5rem', flexShrink:0 }}>{m.icon}</div>

              {/* Image */}
              {pick.img && (
                <img src={pick.img} alt={pick.nombre} width="60" height="60"
                  style={{ objectFit:'cover', borderRadius:8, flexShrink:0 }}
                  onError={e => e.target.style.display='none'}/>
              )}

              {/* Info */}
              <div style={{ flex:1, minWidth:0 }}>
                <div style={{ fontSize:'.6rem', fontWeight:700, color: m.color,
                               textTransform:'uppercase', letterSpacing:'.08em' }}>
                  {m.label}
                </div>
                <div style={{
                  fontSize:'.8rem', fontWeight:600, color:'var(--t1)', marginTop:2,
                  overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                }}>{pick.nombre}</div>
                <div style={{ fontSize:'.65rem', color:'var(--t4)', marginTop:2 }}>
                  {pick.sitio} · {pick.marca || ''}
                  {pick.badge && BADGE_LABELS[pick.badge] && (
                    <span style={{ color:'var(--p2)', marginLeft:5 }}>
                      · {BADGE_LABELS[pick.badge]}
                    </span>
                  )}
                </div>
                {pctBajoMedia && pctBajoMedia > 5 && (
                  <div style={{ fontSize:'.65rem', color:'#3fb950', marginTop:2 }}>
                    {pctBajoMedia}% por debajo de la mediana
                  </div>
                )}
              </div>

              {/* Price */}
              <div style={{ textAlign:'right', flexShrink:0 }}>
                <div style={{ fontSize:'.92rem', fontWeight:800, color:'var(--t1)' }}>
                  ${fmt(pick.precio)}
                </div>
                {pick.scoreP > 0 && (
                  <div style={{ fontSize:'.6rem', color:'var(--t4)' }}>
                    score {pick.scoreP}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── PicksPanel ───────────────────────────────────────────────────────────────
export default function PicksPanel({ onProductClick }) {
  const [cats,    setCats]    = useState([]);
  const [rubro,   setRubro]   = useState('');
  const [loading, setLoading] = useState(false);
  const [selCat,  setSelCat]  = useState(null); // selected category for detail view
  const [busq,    setBusq]    = useState('');

  const load = useCallback(async (r) => {
    setLoading(true); setSelCat(null);
    const data = await fetchMejores(r);
    setCats(Array.isArray(data) ? data : []);
    setLoading(false);
  }, []);

  useEffect(() => { load(rubro); }, [rubro]);

  const visible = busq
    ? cats.filter(c => c.categoria.toLowerCase().includes(busq.toLowerCase()))
    : cats;

  if (selCat) return (
    <div style={{ flex:1, overflowY:'auto' }}>
      <CatDetail cat={selCat} onBack={() => setSelCat(null)}
                 onProductClick={onProductClick}/>
    </div>
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
          <div style={{ fontSize:'.85rem', fontWeight:800, color:'var(--t1)' }}>
            🏆 Mejor picks
          </div>
          <div style={{ fontSize:'.65rem', color:'var(--t4)' }}>
            El mejor de cada categoría según precio/calidad
          </div>
        </div>

        <div style={{ display:'flex', gap:5, flexWrap:'wrap', marginLeft:'auto' }}>
          {RUBROS.map(r => (
            <button key={r.k} onClick={() => setRubro(r.k)} style={{
              padding:'4px 10px', borderRadius:16, border:'none',
              cursor:'pointer', fontSize:'.72rem', fontWeight:700,
              background: rubro===r.k ? 'var(--p)' : 'var(--s2)',
              color: rubro===r.k ? '#fff' : 'var(--t4)',
            }}>{r.icon} {r.l}</button>
          ))}
        </div>

        <input value={busq} onChange={e => setBusq(e.target.value)}
          placeholder="Filtrar categorías..."
          style={{
            padding:'5px 10px', borderRadius:8, border:'1.5px solid var(--bd2)',
            background:'var(--s3)', color:'var(--t1)', font:'.75rem var(--font)',
            outline:'none', width:140,
          }}/>
      </div>

      {/* Grid */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {loading && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Calculando mejores picks...
          </div>
        )}
        {!loading && visible.length === 0 && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Sin datos. Ejecutá un scraping primero.
          </div>
        )}
        {!loading && visible.length > 0 && (
          <div style={{
            display:'grid', gap:10,
            gridTemplateColumns:'repeat(auto-fill, minmax(150px, 1fr))',
          }}>
            {visible.map(cat => (
              <CatCard key={cat.categoria} cat={cat} onClick={setSelCat}/>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
