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
function OutfitCard({ outfit, onReroll, onFeedback, onSwapSlot, rerolling, sentSlots, removedSlots, onRemoveSlot }) {
  const slots = (outfit.slots || []).filter(s => !removedSlots?.has(s.slot));

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:16, maxWidth:1040, margin:'0 auto', width:'100%' }}>
      <div className="outfit-row">
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
                      <button
                        className="outfit-fb-btn swap"
                        onClick={() => onSwapSlot(s.url)}
                        title="Cambiar este item">↻</button>
                      <button
                        className="outfit-fb-btn remove"
                        onClick={() => onRemoveSlot?.(s.slot)}
                        title="Quitar este item">✕</button>
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
function SuplementosCombo({ items, removedSupls, onRemoveSupl }) {
  if (!items || items.length === 0) return null;
  const visibleItems = items.filter((_, i) => !removedSupls?.has(i));
  if (visibleItems.length === 0) return null;
  const total = visibleItems.reduce((s, it) => s + (it.precio || 0), 0);

  return (
    <div className="supl-section" style={{ maxWidth:1040, marginTop:4 }}>
      <div className="supl-eyebrow">Sugerido para vos</div>
      <div className="supl-title">Stack de suplementos</div>
      <div className="supl-grid">
        {items.map((it, i) => {
          if (removedSupls?.has(i)) return null;
          return (
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
                <button
                  className="outfit-fb-btn remove"
                  onClick={() => onRemoveSupl?.(i)}
                  title="Quitar suplemento"
                  style={{ marginTop:6, alignSelf:'flex-start' }}>✕</button>
              </div>
            </div>
          );
        })}
      </div>
      {total > 0 && (
        <div style={{ fontSize:'.8rem', fontWeight:600, color:'var(--t2)', marginTop:8 }}>
          Total suplementos: ${fmt(total)}
        </div>
      )}
    </div>
  );
}

// ─── GymTab ──────────────────────────────────────────────────────────────────
function GymTab({ favoritos, onAddFavorito, savedOutfits, onSaveOutfit }) {
  const [genero, setGenero] = useState('hombre');
  const [presupuesto, setPresupuesto] = useState(0);
  const [presupuestoSuplementos, setPresupuestoSuplementos] = useState(0);
  const [excluirUrls, setExcluirUrls] = useState([]);
  const [outfit, setOutfit] = useState(null);
  const [loading, setLoading] = useState(true);
  const [rerolling, setRerolling] = useState(false);
  const [sentSlots, setSentSlots] = useState(() => new Set());
  const [error, setError] = useState(false);
  const [removedSlots, setRemovedSlots] = useState(() => new Set());
  const [removedSupls, setRemovedSupls] = useState(() => new Set());
  const [saving, setSaving] = useState(false);

  const load = useCallback(async (busy, excluir = excluirUrls) => {
    busy === 'reroll' ? setRerolling(true) : setLoading(true);
    setError(false);
    setSentSlots(new Set());
    try {
      const data = await fetchOutfit(genero, presupuesto, excluir, presupuestoSuplementos);
      setOutfit(data);
      if (data === null) setError(true);
    } catch {
      setOutfit(null);
      setError(true);
    } finally {
      setLoading(false);
      setRerolling(false);
    }
  }, [genero, presupuesto, presupuestoSuplementos, excluirUrls]);

  useEffect(() => { load(); }, [load]);

  async function handleFeedback(slot, url, liked) {
    if (!outfit) return;
    const body = {
      genero: outfit.genero,
      items: [{ slot, url, liked }],
    };
    const ok = await sendOutfitFeedback(body);
    if (ok) setSentSlots(prev => new Set(prev).add(slot));

    // R4: like → auto-add to favoritos (optimistic, idempotent)
    if (liked && onAddFavorito) {
      const item = outfit.slots.find(s => s.url === url);
      if (item && !favoritos.some(f => f.url === url)) {
        onAddFavorito({ url: item.url, sitio: item.sitio, nombre: item.nombre });
      }
    }
  }

  function handleSwapSlot(url) {
    const next = [...excluirUrls, url];
    setExcluirUrls(next);
    load('reroll', next);
  }

  function handleReroll() {
    setExcluirUrls([]);
    setRemovedSlots(new Set());
    setRemovedSupls(new Set());
    load('reroll', []);
  }

  function handleRemoveSlot(slotKey) {
    setRemovedSlots(prev => new Set(prev).add(slotKey));
  }

  function handleRemoveSupl(idx) {
    setRemovedSupls(prev => new Set(prev).add(idx));
  }

  async function handleSaveOutfit() {
    if (!outfit || !onSaveOutfit || saving) return;
    setSaving(true);
    const nombre = `Outfit ${(savedOutfits?.length || 0) + 1}`;
    const visibleSlots = (outfit.slots || []).filter(s => !removedSlots.has(s.slot));
    const visibleSupls = (outfit.suplementos || []).filter((_, i) => !removedSupls.has(i));
    const totalEstimado = visibleSlots.reduce((sum, s) => sum + s.precio, 0);
    try {
      await onSaveOutfit({ nombre, slots: visibleSlots, suplementos: visibleSupls, totalEstimado });
    } finally {
      setSaving(false);
    }
  }

  // Totals recalculated excluding removed items
  const totalVisibleSlots = outfit
    ? (outfit.slots || []).filter(s => !removedSlots.has(s.slot)).reduce((sum, s) => sum + s.precio, 0)
    : 0;
  const presupuestoExcedido = presupuesto > 0 && totalVisibleSlots > presupuesto;

  const hasActiveOutfit = !loading && !error && outfit &&
    outfit.slots && outfit.slots.length > 0;

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:14 }}>
      {/* Budget inputs */}
      <div style={{ display:'flex', gap:16, flexWrap:'wrap', alignItems:'center' }}>
        <div style={{ display:'flex', gap:8, alignItems:'center' }}>
          <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Presupuesto outfit:</span>
          <input
            type="number"
            placeholder="Sin límite"
            value={presupuesto || ''}
            onChange={e => setPresupuesto(Number(e.target.value) || 0)}
            style={{ width:130, padding:'3px 8px', fontSize:'.78rem', borderRadius:4,
                     border:'1px solid var(--bd)', background:'var(--s2)', color:'var(--t1)' }}
          />
        </div>
        <div style={{ display:'flex', gap:8, alignItems:'center' }}>
          <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Presupuesto suplementos (opcional):</span>
          <input
            type="number"
            placeholder="Sin límite"
            value={presupuestoSuplementos || ''}
            onChange={e => setPresupuestoSuplementos(Number(e.target.value) || 0)}
            style={{ width:130, padding:'3px 8px', fontSize:'.78rem', borderRadius:4,
                     border:'1px solid var(--bd)', background:'var(--s2)', color:'var(--t1)' }}
          />
        </div>
      </div>

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
                removedSlots={removedSlots}
                onRemoveSlot={handleRemoveSlot}
                onReroll={handleReroll}
                onFeedback={handleFeedback}
                onSwapSlot={handleSwapSlot}
              />
              {totalVisibleSlots > 0 && (
                <div style={{ fontSize:'.8rem', fontWeight:600,
                              color: presupuestoExcedido ? '#ef4444' : 'var(--t2)' }}>
                  Total estimado: ${fmt(totalVisibleSlots)}
                  {presupuestoExcedido && ' · Excede el presupuesto'}
                </div>
              )}
              <SuplementosCombo
                items={outfit.suplementos}
                removedSupls={removedSupls}
                onRemoveSupl={handleRemoveSupl}
              />
              {hasActiveOutfit && onSaveOutfit && (
                <button
                  onClick={handleSaveOutfit}
                  disabled={saving}
                  style={{
                    alignSelf:'flex-start', padding:'6px 16px', borderRadius:8,
                    border:'1px solid var(--p)', background:'var(--p)', color:'#fff',
                    fontSize:'.78rem', fontWeight:700, cursor: saving ? 'default' : 'pointer',
                    opacity: saving ? .7 : 1,
                  }}>
                  {saving ? 'Guardando...' : '⭐ Guardar outfit'}
                </button>
              )}
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
export default function OutfitsPanel({ favoritos = [], onAddFavorito, savedOutfits = [], onSaveOutfit }) {
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
        {tab === 'gym'    && (
          <GymTab
            favoritos={favoritos}
            onAddFavorito={onAddFavorito}
            savedOutfits={savedOutfits}
            onSaveOutfit={onSaveOutfit}
          />
        )}
        {tab === 'casual' && <PlaceholderTab label="Casual"/>}
        {tab === 'formal' && <PlaceholderTab label="Formal"/>}
      </div>
    </div>
  );
}
