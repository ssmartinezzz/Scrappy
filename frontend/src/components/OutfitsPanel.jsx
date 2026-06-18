import { useEffect, useState, useCallback } from 'react';
import { fetchOutfit, sendOutfitFeedback, fmt } from '../api';

const SLOT_LABELS = {
  torso:     'Torso',
  piernas:   'Piernas',
  calzado:   'Calzado',
  accesorio: 'Accesorio',
};

// ─── OutfitCard ──────────────────────────────────────────────────────────────
function OutfitCard({ outfit, onReroll, onFeedback, rerolling, feedbackSent }) {
  const slots = outfit.slots || [];

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:12, maxWidth:760 }}>
      <div style={{ display:'flex', gap:10, flexWrap:'wrap' }}>
        {slots.map(s => (
          <div key={s.slot} style={{
            flex:'1 1 160px', minWidth:140, background:'var(--s2)', borderRadius:10,
            border:'1.5px solid var(--bd)', overflow:'hidden',
          }}>
            <div style={{
              fontSize:'.62rem', fontWeight:700, color:'var(--t4)',
              padding:'4px 8px', background:'var(--s1)', borderBottom:'1px solid var(--bd)',
            }}>{SLOT_LABELS[s.slot] || s.slot}</div>

            {s.img && (
              <img src={s.img} alt={s.nombre} loading="lazy"
                   style={{ width:'100%', height:140, objectFit:'cover', display:'block' }}
                   onError={e => e.target.style.display = 'none'}/>
            )}

            <div style={{ padding:'8px 10px' }}>
              <div style={{
                fontSize:'.72rem', fontWeight:600, color:'var(--t1)',
                overflow:'hidden', display:'-webkit-box',
                WebkitLineClamp:2, WebkitBoxOrient:'vertical',
              }}>{s.nombre || '—'}</div>
              <div style={{ fontSize:'.78rem', fontWeight:700, color:'var(--g, #3fb950)', marginTop:4 }}>
                ${fmt(s.precio)}
              </div>
              <div style={{ fontSize:'.62rem', color:'var(--t4)' }}>
                {s.marca || s.sitio}
              </div>
            </div>
          </div>
        ))}
      </div>

      <div style={{ display:'flex', gap:8, alignItems:'center' }}>
        <button
          onClick={onReroll}
          disabled={rerolling}
          style={{
            padding:'6px 14px', borderRadius:16, border:'none', cursor: rerolling ? 'default' : 'pointer',
            fontSize:'.74rem', fontWeight:700,
            background: rerolling ? 'var(--s2)' : 'var(--p)',
            color: rerolling ? 'var(--t4)' : '#fff',
            opacity: rerolling ? .6 : 1,
          }}>
          {rerolling ? 'Generando...' : '🎲 Re-roll'}
        </button>

        <button onClick={() => onFeedback(true)} disabled={!!feedbackSent}
          style={{
            padding:'6px 12px', borderRadius:16, border:'1px solid var(--bd)',
            background:'var(--s2)', cursor: feedbackSent ? 'default' : 'pointer',
            fontSize:'.85rem', opacity: feedbackSent ? .5 : 1,
          }}>👍</button>

        <button onClick={() => onFeedback(false)} disabled={!!feedbackSent}
          style={{
            padding:'6px 12px', borderRadius:16, border:'1px solid var(--bd)',
            background:'var(--s2)', cursor: feedbackSent ? 'default' : 'pointer',
            fontSize:'.85rem', opacity: feedbackSent ? .5 : 1,
          }}>👎</button>

        {feedbackSent && (
          <span style={{ fontSize:'.68rem', color:'var(--t4)' }}>
            ¡Gracias por tu feedback!
          </span>
        )}
      </div>
    </div>
  );
}

// ─── GymTab ──────────────────────────────────────────────────────────────────
function GymTab() {
  const [genero, setGenero] = useState('hombre');
  const [outfit, setOutfit] = useState(null);
  const [loading, setLoading] = useState(true);
  const [rerolling, setRerolling] = useState(false);
  const [feedbackSent, setFeedbackSent] = useState(false);
  const [error, setError] = useState(false);

  const load = useCallback(async (busy) => {
    busy === 'reroll' ? setRerolling(true) : setLoading(true);
    setError(false);
    setFeedbackSent(false);
    try {
      const data = await fetchOutfit(genero);
      setOutfit(data);
      if (data === null) setError(true);
    } catch {
      setOutfit(null);
      setError(true);
    } finally {
      setLoading(false);
      setRerolling(false);
    }
  }, [genero]);

  useEffect(() => { load(); }, [load]);

  async function handleFeedback(liked) {
    if (!outfit) return;
    const body = {
      genero: outfit.genero,
      liked,
      slots: (outfit.slots || []).map(s => ({ slot: s.slot, url: s.url })),
    };
    const ok = await sendOutfitFeedback(body);
    if (ok) setFeedbackSent(true);
  }

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:14 }}>
      {/* Genero selector */}
      <div style={{ display:'flex', gap:6, alignItems:'center' }}>
        <span style={{ fontSize:'.72rem', color:'var(--t4)' }}>Género:</span>
        {['hombre', 'mujer', 'unisex'].map(g => (
          <button key={g} onClick={() => setGenero(g)}
            style={{
              padding:'5px 12px', borderRadius:14, border:'1px solid var(--bd)',
              cursor:'pointer', fontSize:'.72rem', fontWeight:600,
              background: genero === g ? 'var(--p)' : 'var(--s2)',
              color: genero === g ? '#fff' : 'var(--t3)',
            }}>{g}</button>
        ))}
      </div>

      {loading && (
        <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.85rem' }}>
          Generando outfit...
        </div>
      )}

      {!loading && error && (
        <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.85rem' }}>
          No hay catálogo cargado todavía. Ejecutá un scraping para generar outfits.
        </div>
      )}

      {!loading && !error && outfit && (
        <>
          {outfit.partial && (
            <div style={{
              fontSize:'.72rem', color:'#f0a500', background:'rgba(240,165,0,.12)',
              border:'1px solid rgba(240,165,0,.3)', borderRadius:8, padding:'8px 12px',
            }}>
              ⚠️ No hay suficientes productos para completar un outfit para este género. Mostrando lo disponible.
            </div>
          )}

          {(!outfit.slots || outfit.slots.length === 0) ? (
            <div style={{ color:'var(--t4)', textAlign:'center', padding:'2rem', fontSize:'.85rem' }}>
              No se encontraron productos para armar un outfit con este filtro.
            </div>
          ) : (
            <OutfitCard
              outfit={outfit}
              rerolling={rerolling}
              feedbackSent={feedbackSent}
              onReroll={() => load('reroll')}
              onFeedback={handleFeedback}
            />
          )}
        </>
      )}
    </div>
  );
}

// ─── Placeholder tab (Casual / Formal) ────────────────────────────────────────
function PlaceholderTab({ label }) {
  return (
    <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.85rem' }}>
      {label} — Próximamente
    </div>
  );
}

// ─── OutfitsPanel principal ────────────────────────────────────────────────────
export default function OutfitsPanel() {
  const [tab, setTab] = useState('gym'); // gym | casual | formal

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Sub-tab bar */}
      <div style={{
        display:'flex', borderBottom:'1px solid var(--bd)',
        background:'var(--s1)', position:'sticky', top:0, zIndex:10,
      }}>
        {[['gym', '💪 Gym'], ['casual', '👕 Casual'], ['formal', '🤵 Formal']].map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} style={{
            padding:'.55rem 1rem', background:'none', border:'none', cursor:'pointer',
            fontSize:'.78rem', fontWeight:600,
            color: tab === k ? 'var(--p2)' : 'var(--t4)',
            borderBottom: tab === k ? '2px solid var(--p2)' : '2px solid transparent',
          }}>{l}</button>
        ))}
      </div>

      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {tab === 'gym'    && <GymTab/>}
        {tab === 'casual' && <PlaceholderTab label="Casual"/>}
        {tab === 'formal' && <PlaceholderTab label="Formal"/>}
      </div>
    </div>
  );
}
