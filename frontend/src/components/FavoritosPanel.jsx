import BuySignal from './BuySignal';
import { rescrapeFavoritos } from '../api';
import { SEMANTIC } from '../lib/colors';

export default function FavoritosPanel({
  favoritos, scrapeStatus, onOpenDetail, onStartPolling, onRefreshFavoritos, onSetScraping,
}) {
  const items = favoritos || [];

  async function handleRefresh() {
    const ok = await rescrapeFavoritos();
    if (ok) {
      onSetScraping?.();
      onStartPolling?.(() => onRefreshFavoritos?.());
    }
  }

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
            ⭐ Favoritos
          </div>
          <div style={{ fontSize:'.65rem', color:'var(--t4)' }}>
            {items.length} producto{items.length === 1 ? '' : 's'} guardado{items.length === 1 ? '' : 's'}
          </div>
        </div>

        <button
          onClick={handleRefresh}
          disabled={scrapeStatus === 'RUNNING'}
          style={{
            marginLeft:'auto', padding:'5px 12px', borderRadius:16, border:'none',
            cursor: scrapeStatus === 'RUNNING' ? 'default' : 'pointer',
            fontSize:'.72rem', fontWeight:700,
            background: scrapeStatus === 'RUNNING' ? 'var(--s2)' : 'var(--p)',
            color: scrapeStatus === 'RUNNING' ? 'var(--t4)' : '#fff',
            opacity: scrapeStatus === 'RUNNING' ? .6 : 1,
          }}>
          {scrapeStatus === 'RUNNING' ? 'Actualizando...' : '↻ Actualizar favoritos'}
        </button>
      </div>

      {/* List */}
      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {items.length === 0 && (
          <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem' }}>
            Todavía no marcaste productos como favoritos.
            Usá el botón ☆ en cada producto del catálogo.
          </div>
        )}

        {items.length > 0 && (
          <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
            {items.map(f => (
              <div key={f.url}
                onClick={() => onOpenDetail?.(f)}
                style={{
                  display:'flex', flexDirection:'column', gap:6,
                  background:'var(--s2)', borderRadius:10, padding:'.75rem',
                  border:'1.5px solid var(--bd)', cursor:'pointer',
                  transition:'border-color .15s',
                }}
                onMouseOver={e => e.currentTarget.style.borderColor = 'var(--p2)'}
                onMouseOut={e => e.currentTarget.style.borderColor = 'var(--bd)'}>

                <div style={{ display:'flex', alignItems:'center', gap:8 }}>
                  <div style={{
                    fontSize:'.8rem', fontWeight:600, color:'var(--t1)', flex:1,
                    overflow:'hidden', whiteSpace:'nowrap', textOverflow:'ellipsis',
                  }}>{f.nombre || f.url}</div>

                  {f.descontinuado && (
                    <span style={{
                      fontSize:'.6rem', fontWeight:700, color: SEMANTIC.negative,
                      background: `color-mix(in srgb, ${SEMANTIC.negative} 12%, transparent)`, padding:'2px 8px', borderRadius:12,
                      whiteSpace:'nowrap',
                    }}>Descontinuado</span>
                  )}
                </div>

                <div style={{ fontSize:'.65rem', color:'var(--t4)' }}>
                  {f.sitio}
                </div>

                {!f.descontinuado && <BuySignal url={f.url}/>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
