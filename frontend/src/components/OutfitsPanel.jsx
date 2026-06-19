import { useEffect, useState, useCallback } from 'react';
import { fetchOutfit, sendOutfitFeedback, fmt } from '../api';

// Orden real en que se compone un outfit (de torso a calzado, accesorio al final) —
// el índice no es decorativo, refleja la secuencia con la que te vestís.
const SLOT_ORDER = [
  { key: 'torso',     label: 'Torso' },
  { key: 'piernas',   label: 'Piernas' },
  { key: 'calzado',   label: 'Calzado' },
  { key: 'accesorio', label: 'Accesorio' },
];
const SLOT_LABELS = Object.fromEntries(SLOT_ORDER.map(s => [s.key, s.label]));
const SLOT_INDEX  = Object.fromEntries(SLOT_ORDER.map((s, i) => [s.key, i + 1]));

// ─── OutfitCard ──────────────────────────────────────────────────────────────
function OutfitCard({ outfit, onReroll, onFeedback, rerolling, sentSlots }) {
  const slots = outfit.slots || [];

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:16, maxWidth:1040 }}>
      <div style={{ display:'flex', gap:16, flexWrap:'wrap' }}>
        {slots.map(s => {
          const sent = sentSlots.has(s.slot);
          return (
            <div key={s.slot} className="outfit-card">
              <div className="kit-tag">
                <span className="kit-tag-idx">{String(SLOT_INDEX[s.slot] || '–').padStart(2, '0')}</span>
                <span className="kit-tag-label">{SLOT_LABELS[s.slot] || s.slot}</span>
              </div>

              {s.img && (
                <div className="outfit-img-wrap">
                  <img src={s.img} alt={s.nombre} loading="lazy"
                       onError={e => { e.target.parentElement.style.display = 'none'; }}/>
                  {(s.marca || s.sitio) && (
                    <span className="outfit-marca-pill">{s.marca || s.sitio}</span>
                  )}
                </div>
              )}

              <div className="outfit-card-body">
                <div className="outfit-card-name">{s.nombre || '—'}</div>
                <div className="outfit-card-price">${fmt(s.precio)}</div>

                <div className="outfit-feedback-row">
                  {sent ? (
                    <span className="outfit-fb-sent">Guardado</span>
                  ) : (
                    <>
                      <button className="outfit-fb-btn like" onClick={() => onFeedback(s.slot, s.url, true)}>
                        Me gusta
                      </button>
                      <button className="outfit-fb-btn dislike" onClick={() => onFeedback(s.slot, s.url, false)}>
                        No me gusta
                      </button>
                    </>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <div style={{ display:'flex', gap:8, alignItems:'center' }}>
        <button className="reroll-btn" onClick={onReroll} disabled={rerolling}>
          {rerolling ? 'Generando...' : 'Generar otra combinación'}
        </button>
      </div>
    </div>
  );
}

// ─── SuplementosCombo ─────────────────────────────────────────────────────────
function SuplementosCombo({ items }) {
  if (!items || items.length === 0) return null;

  return (
    <div className="supl-section" style={{ maxWidth:1040, marginTop:4 }}>
      <div className="supl-eyebrow">Sugerido para vos</div>
      <div className="supl-title">Stack de suplementos</div>
      <div className="supl-grid">
        {items.map(it => (
            <div key={it.tipo} className="supl-card">
              <div className="supl-tipo-header">{it.tipo}</div>

              {it.img && (
                <div className="supl-img-wrap">
                  <img src={it.img} alt={it.nombre} loading="lazy"
                       onError={e => { e.target.parentElement.style.display = 'none'; }}/>
                </div>
              )}

              <div className="supl-card-body">
                <div className="supl-card-name">{it.nombre || '—'}</div>
                <div className="supl-card-price">${fmt(it.precio)}</div>
                <div className="supl-card-marca">{it.marca || it.sitio}</div>
              </div>
            </div>
        ))}
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
  const [sentSlots, setSentSlots] = useState(() => new Set());
  const [error, setError] = useState(false);

  const load = useCallback(async (busy) => {
    busy === 'reroll' ? setRerolling(true) : setLoading(true);
    setError(false);
    setSentSlots(new Set());
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

  async function handleFeedback(slot, url, liked) {
    if (!outfit) return;
    const body = {
      genero: outfit.genero,
      items: [{ slot, url, liked }],
    };
    const ok = await sendOutfitFeedback(body);
    if (ok) setSentSlots(prev => new Set(prev).add(slot));
  }

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:14 }}>
      {/* Genero selector */}
      <div style={{ display:'flex', gap:8, alignItems:'center' }}>
        <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Género:</span>
        {['hombre', 'mujer', 'unisex'].map(g => (
          <button key={g} onClick={() => setGenero(g)}
            className={`genero-pill ${genero === g ? 'active' : ''}`}>{g}</button>
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
            <div className="partial-warning">
              <strong>Catálogo limitado.</strong> No hay suficientes productos para completar un outfit para este género — mostrando lo disponible.
            </div>
          )}

          {(!outfit.slots || outfit.slots.length === 0) ? (
            <div style={{ color:'var(--t4)', textAlign:'center', padding:'2rem', fontSize:'.85rem' }}>
              No se encontraron productos para armar un outfit con este filtro.
            </div>
          ) : (
            <>
              <OutfitCard
                outfit={outfit}
                rerolling={rerolling}
                sentSlots={sentSlots}
                onReroll={() => load('reroll')}
                onFeedback={handleFeedback}
              />
              <SuplementosCombo items={outfit.suplementos} />
            </>
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
        {[['gym', 'Gym'], ['casual', 'Casual'], ['formal', 'Formal']].map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} style={{
            padding:'.55rem 1rem', background:'none', border:'none', cursor:'pointer',
            fontSize:'.78rem', fontWeight:600, letterSpacing:'.02em',
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
