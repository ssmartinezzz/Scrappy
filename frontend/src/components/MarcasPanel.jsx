import { useEffect, useState, useCallback, useRef } from 'react';
import { fetchMarcasBrowser, fetchData, fmt, BADGE_LABELS } from '../api';
import { SEMANTIC } from '../lib/colors';
import { getBrandLogoUrl, getBrandInitials, getBrandColor, logoSobreOscuro } from '../lib/brandLogos';
import { RUBROS } from '../lib/rubros';


const SORT_OPTS = [
  { v:'nombre',      l:'A–Z'           },
  { v:'count',       l:'Más productos' },
  { v:'precio_asc',  l:'Precio ↑'      },
  { v:'precio_desc', l:'Precio ↓'      },
];

// ─── Tarjeta de marca (estilo actress card) ──────────────────────────────────
function MarcaCard({ marca, onClick }) {
  const [hovered, setHovered] = useState(false);
  const [logoFailed, setLogoFailed] = useState(false);
  const logoUrl = getBrandLogoUrl(marca.marca);
  const showLogo = logoUrl && !logoFailed;

  return (
    <button
      type="button"
      className="btn-reset"
      onClick={() => onClick(marca)}
      onMouseOver={() => setHovered(true)}
      onFocus={() => setHovered(true)}
      onMouseOut={() => setHovered(false)}
      onBlur={() => setHovered(false)}
      style={{
        position:'relative', cursor:'pointer', borderRadius:14, overflow:'hidden',
        aspectRatio:'4/5',   // mismo tamaño/disposición que Mejores Picks
        display:'flex', flexDirection:'column',
        background:'var(--s2)', border:`1px solid ${hovered?'var(--p)':'var(--bd)'}`,
        transition:'border-color .12s, transform .15s, box-shadow .15s',
        transform: hovered ? 'translateY(-4px)' : 'none',
        boxShadow: hovered ? '0 14px 40px rgba(0,0,0,.65)' : 'none',
      }}>

      {/* Brand logo, contained and centered on a plate that contrasts with the mark */}
      {showLogo ? (
        <div style={{ width:'100%', flex:1, minHeight:0, display:'flex',
                      alignItems:'center', justifyContent:'center',
                      background: logoSobreOscuro(marca.marca) ? '#111' : '#fff', padding:'14%' }}>
          <img src={logoUrl} alt={marca.marca} loading="lazy"
            style={{ width:'100%', height:'100%', objectFit:'contain', display:'block' }}
            onError={() => setLogoFailed(true)}/>
        </div>
      ) : (
        <div style={{ width:'100%', flex:1, minHeight:0, display:'flex',
                      alignItems:'center', justifyContent:'center',
                      background: getBrandColor(marca.marca) }}>
          <span style={{ fontSize:'2.2rem', fontWeight:900, color:'#fff',
                         letterSpacing:'.02em' }}>
            {getBrandInitials(marca.marca)}
          </span>
        </div>
      )}

      {/* Count badge */}
      <div style={{
        position:'absolute', top:8, right:8,
        background:'rgba(0,0,0,.65)', color:'var(--t2)',
        fontSize:'.6rem', fontWeight:700, padding:'2px 7px', borderRadius:12,
      }}>
        {marca.count}
      </div>

      {/* Brand info: its own strip, never over the logo (a dark gradient hid black marks) */}
      <div style={{ background:'#141416', padding:'8px 10px 9px', minHeight:64, textAlign:'left' }}>
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
    </button>
  );
}

// ─── Vista de productos de una marca ─────────────────────────────────────────
function MarcaDetail({ marca, onBack, onProductClick }) {
  const [prods,   setProds]   = useState([]);
  const [loading, setLoading] = useState(true);
  // Keyset pagination: page 0 (page-number mode) hands out the first cursor, the rest is `cursor=`.
  const [cursor,  setCursor]  = useState(null);
  const [fin,     setFin]     = useState(false);
  const cargando = useRef(false);
  const sentinelRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setProds([]); setCursor(null); setFin(false); cargando.current = false;
    fetchData({ marca: marca.marca, size: 48, page: 0, orden:'precio_asc' })
      .then(d => {
        if (cancelled) return;
        setProds(d?.productos || []);
        setCursor(d?.nextCursor || null);
        setFin(!d?.nextCursor);
        setLoading(false);
      })
      .catch(() => {
        // Unreachable backend: keep the loading state instead of showing an empty brand.
      });
    return () => { cancelled = true; };
  }, [marca.marca]);

  const cargarMas = useCallback(async () => {
    if (cargando.current || !cursor) return;
    cargando.current = true;
    try {
      const d = await fetchData({ marca: marca.marca, size: 48, orden:'precio_asc', cursor });
      setProds(prev => {
        const vistos = new Set(prev.map(p => p.url));
        return [...prev, ...(d?.productos || []).filter(p => !vistos.has(p.url))];
      });
      setCursor(d?.nextCursor || null);
      if (!d?.nextCursor) setFin(true);
    } catch {
      // A failed page leaves the sentinel armed; scrolling again retries it.
    } finally {
      cargando.current = false;
    }
  }, [marca.marca, cursor]);

  useEffect(() => {
    if (fin || loading) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      entries => { if (entries[0].isIntersecting) cargarMas(); },
      { rootMargin: '600px', threshold: 0 }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [fin, loading, cargarMas]);

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
            <button key={p.url || i} type="button" className="btn-reset"
              onClick={() => onProductClick(p)}
              style={{
                background:'var(--s2)', borderRadius:10, overflow:'hidden',
                border:'1px solid var(--bd)', cursor:'pointer',
                transition:'border-color .12s',
              }}
              onMouseOver={e => e.currentTarget.style.borderColor='var(--p)'}
              onFocus={e => e.currentTarget.style.borderColor='var(--p)'}
              onMouseOut={e => e.currentTarget.style.borderColor='var(--bd)'}
              onBlur={e => e.currentTarget.style.borderColor='var(--bd)'}>
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
            </button>
          ))}
        </div>
        {!fin && !loading && <div ref={sentinelRef} style={{ height:1 }} />}
      </div>
    </div>
  );
}

// ─── MarcasPanel ─────────────────────────────────────────────────────────────
export default function MarcasPanel({ onProductClick }) {
  const [marcas,  setMarcas]  = useState([]);
  const [loading, setLoading] = useState(false);
  const [rubro,   setRubro]   = useState('');
  const [sort,    setSort]    = useState('nombre');
  const [q,       setQ]       = useState('');
  const [qAplicada, setQAplicada] = useState('');
  const [cursor,  setCursor]  = useState(null);
  const [fin,     setFin]     = useState(true);
  const [total,   setTotal]   = useState(0);
  const [selMarca, setSelMarca] = useState(null);
  const cargando = useRef(false);
  const generacion = useRef(0);
  const sentinelRef = useRef(null);

  // Debounce the search box; the list itself only reacts to the applied text.
  useEffect(() => {
    const t = setTimeout(() => setQAplicada(q), 350);
    return () => clearTimeout(t);
  }, [q]);

  // Keyset pagination: any filter change starts over from the first page. A response that belongs to an older
  // filter set (its generation is no longer current) is dropped, so a slow request cannot overwrite a newer list.
  useEffect(() => {
    const mia = ++generacion.current;
    cargando.current = false;
    setLoading(true); setMarcas([]); setCursor(null); setFin(true); setTotal(0);
    fetchMarcasBrowser({ rubro, sort, q: qAplicada })
      .then(d => {
        if (generacion.current !== mia) return;
        setMarcas(d?.marcas || []);
        setTotal(d?.total || 0);
        setCursor(d?.nextCursor || null);
        setFin(!d?.nextCursor);
        setLoading(false);
      })
      .catch(() => {
        if (generacion.current === mia) setLoading(false);
      });
    return () => { generacion.current++; };
  }, [rubro, sort, qAplicada]);

  const cargarMas = useCallback(async () => {
    if (cargando.current || !cursor) return;
    const mia = generacion.current;
    cargando.current = true;
    try {
      const d = await fetchMarcasBrowser({ rubro, sort, q: qAplicada, cursor });
      if (generacion.current !== mia) return;
      setMarcas(prev => {
        const vistas = new Set(prev.map(m => m.marca));
        return [...prev, ...(d?.marcas || []).filter(m => !vistas.has(m.marca))];
      });
      setCursor(d?.nextCursor || null);
      if (!d?.nextCursor) setFin(true);
    } catch {
      // A failed page leaves the sentinel armed; scrolling again retries it.
    } finally {
      if (generacion.current === mia) cargando.current = false;
    }
  }, [rubro, sort, qAplicada, cursor]);

  useEffect(() => {
    if (fin || loading || selMarca) return;
    const el = sentinelRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      entries => { if (entries[0].isIntersecting) cargarMas(); },
      { rootMargin: '600px', threshold: 0 }
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [fin, loading, selMarca, cargarMas]);

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
            {total.toLocaleString('es-AR')} marcas · clickeá para ver sus productos
          </div>
        </div>

        {/* Rubro filter */}
        <div style={{ display:'flex', gap:4 }}>
          {RUBROS.map(r => (
            <button key={r.key} onClick={() => setRubro(r.key)} style={{
              padding:'4px 9px', borderRadius:16, border:'none',
              cursor:'pointer', fontSize:'.7rem', fontWeight:700,
              background: rubro===r.key ? 'var(--p)' : 'var(--s2)',
              color: rubro===r.key ? '#fff' : 'var(--t4)',
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
            display:'grid', gap:14,
            // Two columns on a phone, the original 300px cards from tablet up.
            gridTemplateColumns:'repeat(auto-fill, minmax(clamp(140px, 42vw, 300px), 1fr))',
          }}>
            {marcas.map(m => (
              <MarcaCard key={m.marca} marca={m} onClick={setSelMarca}/>
            ))}
          </div>
        )}
        {!fin && !loading && <div ref={sentinelRef} style={{ height:1 }} />}
      </div>
    </div>
  );
}
