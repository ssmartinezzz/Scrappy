import { useEffect, useState, useCallback } from 'react';
import { fetchOutfit, sendOutfitFeedback, fetchOutfitBuilder, resetOutfitFeedback, fmt } from '../api';

// Orden real en que se compone un outfit (de torso a calzado, accesorio al final) —
// el índice no es decorativo, refleja la secuencia con la que te vestís.
const SLOT_ORDER = [
  { key: 'torso',          label: 'Torso' },
  { key: 'torso-base',     label: 'Base' },
  { key: 'torso-outer',    label: 'Abrigo' },
  { key: 'piernas',        label: 'Piernas' },
  { key: 'calzado',        label: 'Calzado' },
  { key: 'accesorio',      label: 'Accesorio' },
  { key: 'accesorio-head', label: 'Gorra' },
  { key: 'accesorio-feet', label: 'Medias' },
  { key: 'accesorio-body', label: 'Accesorio' },
];
const SLOT_LABELS = Object.fromEntries(SLOT_ORDER.map(s => [s.key, s.label]));
const SLOT_INDEX  = Object.fromEntries(SLOT_ORDER.map((s, i) => [s.key, i + 1]));

// ─── OutfitCard ──────────────────────────────────────────────────────────────
function OutfitCard({ outfit, onReroll, onFeedback, onSwapSlot, rerolling, sentSlots, removedSlots, onRemoveSlot }) {
  const slots = (outfit.slots || []).filter(s => !removedSlots?.has(s.slot));

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:16, maxWidth:1040, margin:'0 auto', width:'100%' }}>
      <div className="outfit-row">
        {slots.map((s, idx) => {
          const sent = sentSlots.has(s.slot);
          return (
            <div key={s.slot} className="outfit-card">
              <div className="kit-tag">
                <span className="kit-tag-idx">{String(idx + 1).padStart(2, '0')}</span>
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

// ─── Placeholder tab (Casual / Formal) ────────────────────────────────────────
function PlaceholderTab({ label }) {
  return (
    <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.85rem' }}>
      {label} — Próximamente
    </div>
  );
}

// ─── OutfitPanel taxonomy ─────────────────────────────────────────────────────
const BUILDER_GROUPS = [
  {
    key: 'torso', label: 'Torso',
    cats: ['Puffer', 'Campera', 'Sweater', 'Buzo', 'Musculosa', 'Camisa',
           'Remera', 'Chomba', 'Casaca', 'Chaleco', 'Saco', 'Traje', 'Piloto'],
  },
  {
    key: 'piernas', label: 'Piernas',
    cats: ['Calza', 'Baggy', 'Jean', 'Jogging', 'Short', 'Bermuda', 'Pollera', 'Pantalón'],
  },
  {
    key: 'calzado', label: 'Calzado',
    cats: ['Zapatilla', 'Zapatilla Running', 'Zapatilla Entrenamiento',
           'Zapatilla Skate', 'Zapatilla Urbana', 'Sneaker',
           'Botines', 'Borcego', 'Botas', 'Ojotas'],
  },
  {
    key: 'accesorio', label: 'Accesorio',
    cats: ['Mochila', 'Bolso', 'Riñonera', 'Billetera', 'Cinturón', 'Bufanda',
           'Guantes', 'Gorro', 'Gorra', 'Lentes', 'Medias', 'Suplemento'],
  },
];

// Default gym categories pre-selected on mount (UOB-03, UOB-05)
const GYM_DEFAULT_CATS = new Set([
  'Remera', 'Buzo', 'Musculosa', 'Campera',                                    // torso
  'Short', 'Calza', 'Jogging', 'Pantalón',                                     // piernas
  'Zapatilla', 'Zapatilla Running', 'Zapatilla Entrenamiento',
  'Zapatilla Urbana', 'Sneaker',                                                // calzado
]);

// ─── OutfitPanel ──────────────────────────────────────────────────────────────
// Unified outfit component that replaces GymTab + BuilderTab.
// Renders gender tabs, an editable category picker, budget inputs, and a
// live outfit result card with re-roll variety logic (MCKP → greedy after 10).
function OutfitPanel({ favoritos, onAddFavorito, savedOutfits, onSaveOutfit }) {
  const [genero, setGenero]                     = useState('hombre');
  const [selectedCats, setSelectedCats]         = useState(new Set(GYM_DEFAULT_CATS));
  const [presupuesto, setPresupuesto]           = useState('');
  const [presupuestoSuplementos, setPresupuestoSuplementos] = useState('');
  const [attemptCount, setAttemptCount]         = useState(0);
  const [currentOutfitUrls, setCurrentOutfitUrls] = useState([]);
  const [result, setResult]                     = useState(null);
  const [loading, setLoading]                   = useState(false);
  const [error, setError]                       = useState(null);
  const [expanded, setExpanded]                 = useState({ torso:true, piernas:true, calzado:true, accesorio:false });
  const [sentSlots, setSentSlots]               = useState(() => new Set());
  const [removedSlots, setRemovedSlots]         = useState(() => new Set());
  const [saving, setSaving]                     = useState(false);
  const [greedyToast, setGreedyToast]           = useState(false);
  const [greedyExcluded, setGreedyExcluded]     = useState([]);
  const [resetting, setResetting]               = useState(false);

  // Core load function — called on mount and on re-roll
  const load = useCallback(async (excluir = [], isGreedy = false) => {
    setLoading(true);
    setError(null);
    setSentSlots(new Set());
    try {
      const data = await fetchOutfitBuilder({
        categorias: [...selectedCats],
        presupuesto: Number(presupuesto) || 0,
        genero,
        excluir,
        greedy: isGreedy,
      });
      if (data === null) {
        setError('No hay catálogo cargado. Ejecutá un scraping primero.');
        setResult(null);
      } else if ((data.slots || []).length === 0 && excluir.length > 0) {
        // Catalog exhausted due to accumulated exclusions — reset and retry fresh.
        setGreedyExcluded([]);
        setCurrentOutfitUrls([]);
        setAttemptCount(0);
        load([], false);
      } else {
        setResult(data);
        setCurrentOutfitUrls((data.slots || []).map(s => s.url));
      }
    } catch {
      setError('Error de conexión.');
      setResult(null);
    } finally {
      setLoading(false);
    }
  }, [selectedCats, presupuesto, genero]);

  // Auto-generate on mount with gym defaults (UOB-05)
  useEffect(() => { load([], false); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Re-roll: attempts 1–10 use MCKP+excluir; >10 switch to greedy (UOB-07)
  function handleReroll() {
    const next = attemptCount + 1;
    setAttemptCount(next);
    setRemovedSlots(new Set());
    if (next === 11) {
      setGreedyToast(true);
      setTimeout(() => setGreedyToast(false), 3500);
    }
    // Always accumulate excluded URLs across re-rolls (both MCKP and greedy)
    // so the same outfit can't cycle back — replacing-only caused A→B→A→B loops
    // even with MCKP shuffle when the affordable pool is small.
    const accumulated = [...new Set([...greedyExcluded, ...currentOutfitUrls])];
    setGreedyExcluded(accumulated);
    load(accumulated, next > 10);
  }

  async function handleResetFeedback() {
    if (resetting) return;
    setResetting(true);
    await resetOutfitFeedback();
    setResetting(false);
    setAttemptCount(0);
    setCurrentOutfitUrls([]);
    setGreedyExcluded([]);
    load([], false);
  }

  // Gender tab switch: reset counter and exclusions (UOB-07)
  function handleGeneroChange(g) {
    setGenero(g);
    setAttemptCount(0);
    setCurrentOutfitUrls([]);
    setGreedyExcluded([]);
  }

  // Category toggle: reset counter and exclusions (UOB-04, UOB-07)
  function toggleCat(cat) {
    setSelectedCats(prev => {
      const next = new Set(prev);
      next.has(cat) ? next.delete(cat) : next.add(cat);
      return next;
    });
    setAttemptCount(0);
    setCurrentOutfitUrls([]);
    setGreedyExcluded([]);
  }

  function toggleGroup(key) {
    setExpanded(prev => ({ ...prev, [key]: !prev[key] }));
  }

  // Budget change: reset counter and exclusions (UOB-07)
  function handlePresupuestoChange(v) {
    setPresupuesto(v);
    setAttemptCount(0);
    setCurrentOutfitUrls([]);
    setGreedyExcluded([]);
  }

  async function handleFeedback(slot, url, liked) {
    if (!result) return;
    const body = {
      genero: result.genero || genero,
      items: [{ slot, url, liked }],
    };
    const ok = await sendOutfitFeedback(body);
    if (ok) setSentSlots(prev => new Set(prev).add(slot));

    if (liked && onAddFavorito) {
      const item = (result.slots || []).find(s => s.url === url);
      if (item && !favoritos?.some(f => f.url === url)) {
        onAddFavorito({ url: item.url, sitio: item.sitio, nombre: item.nombre });
      }
    }
  }

  function handleSwapSlot(url) {
    const next = [...currentOutfitUrls.filter(u => u !== url), url];
    setCurrentOutfitUrls(next);
    load(next, attemptCount > 10);
  }

  function handleRemoveSlot(slotKey) {
    setRemovedSlots(prev => new Set(prev).add(slotKey));
  }

  async function handleSaveOutfit() {
    if (!result || !onSaveOutfit || saving) return;
    setSaving(true);
    const nombre = `Outfit ${(savedOutfits?.length || 0) + 1}`;
    const visibleSlots = (result.slots || []).filter(s => !removedSlots.has(s.slot));
    const totalEstimado = visibleSlots.reduce((sum, s) => sum + s.precio, 0);
    try {
      await onSaveOutfit({ nombre, slots: visibleSlots, suplementos: [], totalEstimado });
    } finally {
      setSaving(false);
    }
  }

  const hasSlots = result && result.slots && result.slots.length > 0;
  const isNoFit  = result && (!result.slots || result.slots.length === 0);
  const budget   = Number(presupuesto);

  // No-fit messaging (UOB-11)
  let noFitMessage = null;
  if (isNoFit) {
    if (result.minimoBudgetNecesario != null) {
      const gap = result.minimoBudgetNecesario - budget;
      noFitMessage = budget > 0
        ? `Necesitás al menos $${fmt(gap)} más para armar este outfit.`
        : `El outfit mínimo cuesta $${fmt(result.minimoBudgetNecesario)}.`;
    } else {
      const emptycat = result.categoriasVacias?.[0];
      noFitMessage = emptycat
        ? `Sin productos en catálogo para ${emptycat}.`
        : 'No se encontraron productos para las categorías seleccionadas.';
    }
  }

  const totalVisibleSlots = hasSlots
    ? (result.slots || []).filter(s => !removedSlots.has(s.slot)).reduce((sum, s) => sum + s.precio, 0)
    : 0;
  const presupuestoExcedido = budget > 0 && totalVisibleSlots > budget;
  const hasActiveOutfit = !loading && !error && hasSlots;

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:14 }}>

      {greedyToast && (
        <div className="greedy-toast">
          Modo variedad máxima activado — explorando combinaciones menos obvias
        </div>
      )}

      {/* Gender tabs — Hombre / Mujer only (UOB-02) */}
      <div style={{ display:'flex', gap:8, alignItems:'center', flexWrap:'wrap' }}>
        <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Género:</span>
        {['hombre', 'mujer'].map(g => (
          <button key={g} onClick={() => handleGeneroChange(g)}
            className={`genero-pill ${genero === g ? 'active' : ''}`}>{g}</button>
        ))}
        <button
          onClick={handleResetFeedback}
          disabled={resetting}
          title="Borra el historial de Me gusta / No me gusta para que el generador empiece desde cero"
          style={{
            marginLeft:'auto', padding:'3px 10px', borderRadius:12, fontSize:'.68rem',
            fontWeight:600, cursor: resetting ? 'default' : 'pointer',
            background:'none', border:'1px solid var(--bd)',
            color:'var(--t4)', opacity: resetting ? .5 : 1,
          }}>
          {resetting ? 'Reseteando...' : '↺ Resetear gustos'}
        </button>
      </div>

      {/* Category picker — 4 collapsible groups (UOB-04) */}
      {BUILDER_GROUPS.map(group => (
        <div key={group.key} style={{ borderRadius:8, border:'1px solid var(--bd)', overflow:'hidden' }}>
          <button
            onClick={() => toggleGroup(group.key)}
            style={{
              width:'100%', textAlign:'left', padding:'.5rem .75rem',
              background:'var(--s1)', border:'none', cursor:'pointer',
              fontSize:'.75rem', fontWeight:700, color:'var(--t2)',
              display:'flex', justifyContent:'space-between', alignItems:'center',
            }}>
            <span>{group.label}</span>
            <span style={{ fontSize:'.65rem', color:'var(--t4)' }}>
              {expanded[group.key] ? '▲' : '▼'}
            </span>
          </button>

          {expanded[group.key] && (
            <div style={{ padding:'.5rem .75rem', display:'flex', flexWrap:'wrap', gap:6 }}>
              {group.cats.map(cat => {
                const active = selectedCats.has(cat);
                return (
                  <button
                    key={cat}
                    onClick={() => toggleCat(cat)}
                    style={{
                      padding:'3px 10px', borderRadius:20, fontSize:'.7rem', fontWeight:600,
                      cursor:'pointer',
                      border: active ? '1.5px solid var(--p2)' : '1px solid var(--bd)',
                      background: active ? 'var(--p2)' : 'var(--s2)',
                      color: active ? '#fff' : 'var(--t3)',
                    }}>
                    {cat}
                  </button>
                );
              })}
            </div>
          )}
        </div>
      ))}

      {/* Budget inputs (UOB-08, UOB-09) */}
      <div style={{ display:'flex', gap:16, flexWrap:'wrap', alignItems:'center' }}>
        <div style={{ display:'flex', gap:8, alignItems:'center' }}>
          <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Presupuesto outfit:</span>
          <input
            type="number"
            placeholder="Sin límite"
            value={presupuesto}
            onChange={e => handlePresupuestoChange(e.target.value)}
            style={{ width:130, padding:'3px 8px', fontSize:'.78rem', borderRadius:4,
                     border:'1px solid var(--bd)', background:'var(--s2)', color:'var(--t1)' }}
          />
        </div>
        {/* TODO: supplement budget — wire to builder endpoint when /api/outfits/builder supports supplements
        <div style={{ display:'flex', gap:8, alignItems:'center' }}>
          <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Presupuesto suplementos (opcional):</span>
          <input
            type="number"
            placeholder="Sin límite"
            value={presupuestoSuplementos}
            onChange={e => setPresupuestoSuplementos(e.target.value)}
            style={{ width:130, padding:'3px 8px', fontSize:'.78rem', borderRadius:4,
                     border:'1px solid var(--bd)', background:'var(--s2)', color:'var(--t1)' }}
          />
        </div>
        */}
        <button
          onClick={() => { setAttemptCount(0); setCurrentOutfitUrls([]); load([], false); }}
          disabled={loading || !selectedCats.size}
          style={{
            padding:'5px 18px', borderRadius:8, border:'1px solid var(--p)',
            background:'var(--p)', color:'#fff', fontSize:'.78rem', fontWeight:700,
            cursor: (loading || !selectedCats.size) ? 'default' : 'pointer',
            opacity: (loading || !selectedCats.size) ? .7 : 1,
          }}>
          {loading ? 'Buscando...' : 'Armar'}
        </button>
      </div>

      {/* Re-roll counter indicator */}
      {attemptCount > 0 && (
        <div style={{ fontSize:'.7rem', color:'var(--t4)' }}>
          Intento {attemptCount}{attemptCount > 10 ? ' · modo greedy activo' : ''}
        </div>
      )}

      {/* Loading state */}
      {loading && (
        <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.85rem' }}>
          Generando outfit...
        </div>
      )}

      {/* Error state */}
      {!loading && error && (
        <div style={{ color:'var(--t4)', textAlign:'center', padding:'3rem', fontSize:'.85rem' }}>
          {error}
        </div>
      )}

      {/* No-fit state (UOB-11) */}
      {!loading && !error && isNoFit && (
        <div style={{ padding:'1.5rem', borderRadius:8, border:'1px solid var(--bd)',
                      background:'var(--s1)', display:'flex', flexDirection:'column', gap:10 }}>
          <div style={{ fontWeight:700, color:'var(--t1)', fontSize:'.9rem' }}>
            No se puede armar el outfit
            {budget > 0 ? ` dentro de $${fmt(budget)}` : ''}
          </div>
          {noFitMessage && (
            <div style={{ fontSize:'.82rem', color:'var(--t3)' }}>{noFitMessage}</div>
          )}
          <button className="reroll-btn" onClick={handleReroll} disabled={loading}>
            Intentar de nuevo
          </button>
        </div>
      )}

      {/* Success state */}
      {!loading && !error && hasSlots && (
        <>
          {result.partial && (
            <div className="partial-warning">
              <strong>Catálogo limitado.</strong> No hay suficientes productos para completar el outfit — mostrando lo disponible.
            </div>
          )}

          <OutfitCard
            outfit={result}
            rerolling={loading}
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
    </div>
  );
}

// ─── OutfitsPanel principal ────────────────────────────────────────────────────
export default function OutfitsPanel({ favoritos = [], onAddFavorito, savedOutfits = [], onSaveOutfit }) {
  const [tab, setTab] = useState('outfit'); // outfit | casual | formal

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Sub-tab bar — always visible at top; content scrolls below */}
      <div style={{
        display:'flex', borderBottom:'1px solid var(--bd)',
        background:'var(--s1)', flexShrink:0,
      }}>
        {[['outfit', 'Outfit'], ['casual', 'Casual'], ['formal', 'Formal']].map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} style={{
            padding:'.55rem 1rem', background:'none', border:'none', cursor:'pointer',
            fontSize:'.78rem', fontWeight:600, letterSpacing:'.02em',
            color: tab === k ? 'var(--p2)' : 'var(--t4)',
            borderBottom: tab === k ? '2px solid var(--p2)' : '2px solid transparent',
          }}>{l}</button>
        ))}
      </div>

      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {tab === 'outfit'  && (
          <OutfitPanel
            favoritos={favoritos}
            onAddFavorito={onAddFavorito}
            savedOutfits={savedOutfits}
            onSaveOutfit={onSaveOutfit}
          />
        )}
        {tab === 'casual'  && <PlaceholderTab label="Casual"/>}
        {tab === 'formal'  && <PlaceholderTab label="Formal"/>}
      </div>
    </div>
  );
}
