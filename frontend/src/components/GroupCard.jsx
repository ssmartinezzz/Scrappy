import { fmt, BADGE_LABELS } from '../api';

const BADGE_COLOR = {
  precio_historico_bajo: '#f0a500',
  precio_bajo:           '#00b894',
  oferta_real:           '#a371f7',
  tendencia:             '#fd6400',
  precio_bajando:        '#3fb950',
};

export default function GroupCard({ grupo, onOpenDetail }) {
  const { nombre, categoria, img, precios = [], ahorroPct, precioMin, precioMax } = grupo;
  const cheapest = precios[0];
  const priciest = precios[precios.length - 1];
  const tieneAhorro = ahorroPct >= 5 && precios.length >= 2;

  return (
    <div style={{
      background: 'var(--s1)', border: '1px solid var(--bd)', borderRadius: 12,
      overflow: 'hidden', transition: 'box-shadow .15s',
      display: 'flex', flexDirection: 'column',
    }}
      onMouseOver={e => e.currentTarget.style.boxShadow = '0 8px 32px rgba(0,0,0,.4)'}
      onMouseOut={e  => e.currentTarget.style.boxShadow = 'none'}
    >
      {/* Imagen */}
      <div style={{ position: 'relative', paddingTop: '75%', background: 'var(--s2)', flexShrink: 0 }}>
        {img
          ? <img src={img} alt={nombre} loading="lazy"
                 style={{ position:'absolute', inset:0, width:'100%', height:'100%', objectFit:'cover' }}
                 onError={e => { e.target.style.display='none'; }} />
          : <div style={{ position:'absolute', inset:0, display:'flex', alignItems:'center',
                          justifyContent:'center', fontSize:'1.8rem', color:'var(--t4)' }}>🛍</div>
        }
        {/* Badge de ahorro */}
        {tieneAhorro && (
          <div style={{
            position:'absolute', top:8, right:8,
            background:'rgba(0,184,148,.9)', color:'#fff',
            fontSize:'.65rem', fontWeight:800, padding:'3px 8px', borderRadius:20,
          }}>
            💰 Ahorrás {ahorroPct.toFixed(0)}%
          </div>
        )}
        {/* Multi-sitio badge */}
        <div style={{
          position:'absolute', top:8, left:8,
          background:'rgba(0,0,0,.7)', color:'var(--t2)',
          fontSize:'.6rem', fontWeight:700, padding:'2px 7px', borderRadius:20,
          letterSpacing:'.05em',
        }}>
          {precios.length} sitios
        </div>
      </div>

      {/* Body */}
      <div style={{ padding:'.7rem .85rem', flex:1, display:'flex', flexDirection:'column', gap:8 }}>
        {/* Nombre + categoría */}
        <div>
          <div style={{
            fontSize:'.78rem', fontWeight:600, color:'var(--t1)',
            overflow:'hidden', display:'-webkit-box',
            WebkitLineClamp:2, WebkitBoxOrient:'vertical',
            lineHeight:1.35,
          }}>{nombre}</div>
          {categoria && (
            <div style={{ fontSize:'.62rem', color:'var(--t4)', marginTop:2 }}>{categoria}</div>
          )}
        </div>

        {/* Tabla de precios por sitio */}
        <div style={{ display:'flex', flexDirection:'column', gap:4 }}>
          {precios.map((p, i) => {
            const isCheapest = i === 0;
            const isExpensive = i === precios.length - 1 && precios.length > 1;
            return (
              <a key={p.url || i}
                 href={p.url} target="_blank" rel="noopener noreferrer"
                 style={{
                   display:'flex', alignItems:'center', gap:8,
                   padding:'5px 8px', borderRadius:7,
                   background: isCheapest ? 'rgba(0,184,148,.1)' :
                               isExpensive && tieneAhorro ? 'rgba(232,67,147,.07)' : 'var(--s2)',
                   border: `1px solid ${isCheapest ? '#00b894' :
                            isExpensive && tieneAhorro ? '#e84393' : 'var(--bd)'}`,
                   textDecoration:'none', transition:'all .12s',
                 }}
                 onMouseOver={e => e.currentTarget.style.borderColor = 'var(--p)'}
                 onMouseOut={e  => {
                   e.currentTarget.style.borderColor = isCheapest ? '#00b894' :
                     isExpensive && tieneAhorro ? '#e84393' : 'var(--bd)';
                 }}
              >
                {/* Ícono sitio */}
                <span style={{ fontSize:'.6rem', fontWeight:800, color:'var(--t4)',
                               flexShrink:0, width:18, textAlign:'center' }}>
                  {isCheapest ? '💚' : isExpensive && tieneAhorro ? '📈' : '•'}
                </span>

                {/* Nombre sitio */}
                <span style={{ fontSize:'.7rem', color:'var(--t3)', flex:1,
                               overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis' }}>
                  {p.sitio}
                </span>

                {/* Badge ML */}
                {p.badge && BADGE_LABELS[p.badge] && (
                  <span style={{
                    fontSize:'.55rem', padding:'1px 5px', borderRadius:10,
                    background: `${BADGE_COLOR[p.badge] || 'var(--t4)'}22`,
                    color: BADGE_COLOR[p.badge] || 'var(--t4)',
                    border: `1px solid ${BADGE_COLOR[p.badge] || 'var(--bd)'}44`,
                    flexShrink:0,
                  }}>{BADGE_LABELS[p.badge].split(' ')[0]}</span>
                )}

                {/* Precio */}
                <span style={{
                  fontSize:'.8rem', fontWeight:700, flexShrink:0,
                  color: isCheapest ? '#00b894' :
                         isExpensive && tieneAhorro ? '#e84393' : 'var(--t1)',
                }}>
                  ${fmt(p.precio)}
                </span>

                <span style={{ fontSize:'.6rem', color:'var(--t4)', flexShrink:0 }}>→</span>
              </a>
            );
          })}
        </div>

        {/* Conclusión: diferencia total */}
        {tieneAhorro && (
          <div style={{
            fontSize:'.68rem', color:'var(--t4)', paddingTop:4,
            borderTop:'1px solid var(--s3)',
          }}>
            💡 <strong style={{ color:'#00b894' }}>{cheapest.sitio}</strong> es{' '}
            <strong style={{ color:'#00b894' }}>${fmt(precioMax - precioMin)} más barato</strong>{' '}
            que <strong style={{ color:'#e84393' }}>{priciest.sitio}</strong>
          </div>
        )}
      </div>
    </div>
  );
}
