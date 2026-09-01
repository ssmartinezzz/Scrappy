import { useEffect, useState, useCallback, useMemo } from 'react';
import { fetchOutfit, sendOutfitFeedback, fetchOutfitBuilder, resetOutfitFeedback, fmt } from '../api';
import { MultiSelectTags } from './ui/multi-select-tags';
import { MoneyInput } from './ui/money-input';
import { cn } from '@/lib/utils';

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

// BUILDER_GROUPS in the shape MultiSelectTags speaks. Derived rather than a second
// hand-written list: the taxonomy above stays the single owner, so a category added
// there shows up in the picker without a matching edit here.
//
// The picker relies on tags being unique across ALL groups — it drives framer-motion's
// shared-element animation with `layoutId={tag}`, which needs each tag mounted in
// exactly one place. Duplicating a category across two groups would break that
// silently, as a chip that refuses to animate.
const PICKER_GROUPS = BUILDER_GROUPS.map(g => ({ label: g.label, tags: g.cats }));

// Default gym categories pre-selected on mount (UOB-03, UOB-05)
const GYM_DEFAULT_CATS = new Set([
  'Remera', 'Buzo', 'Musculosa', 'Campera',                                    // torso
  'Short', 'Calza', 'Jogging', 'Pantalón',                                     // piernas
  'Zapatilla', 'Zapatilla Running', 'Zapatilla Entrenamiento',
  'Zapatilla Urbana', 'Sneaker',                                                // calzado
]);

// Default casual categories pre-selected on mount. Broad on purpose — the user
// wants "casi todo" available as casual. The backend gate (estilo=casual) keeps
// only non-gymrat apparel for torso/piernas; calzado is category-driven.
const CASUAL_DEFAULT_CATS = new Set([
  'Remera', 'Buzo', 'Campera', 'Sweater', 'Camisa', 'Chomba', 'Musculosa',     // torso
  'Jean', 'Pantalón', 'Bermuda', 'Short', 'Jogging', 'Baggy',                  // piernas
  'Zapatilla', 'Zapatilla Urbana', 'Zapatilla Skate', 'Sneaker',               // calzado
]);

// Per-style configuration: default category selection + the estilo sent to the
// builder endpoint. Keeps the gym panel byte-for-byte behavior under 'gym'.
const STYLE_CONFIG = {
  gym:    { defaultCats: GYM_DEFAULT_CATS,    estilo: 'gym' },
  casual: { defaultCats: CASUAL_DEFAULT_CATS, estilo: 'casual' },
};

// ─── OutfitPanel ──────────────────────────────────────────────────────────────
// Unified outfit component that replaces GymTab + BuilderTab.
// Renders gender tabs, an editable category picker, budget inputs, and a
// live outfit result card with re-roll variety logic (MCKP → greedy after 10).
function OutfitPanel({ style = 'gym', favoritos, onAddFavorito, savedOutfits, onSaveOutfit }) {
  const styleConfig = STYLE_CONFIG[style] || STYLE_CONFIG.gym;
  const presupuestoId = `presupuesto-outfit-${style}`;
  const [genero, setGenero]                     = useState('hombre');
  const [selectedCats, setSelectedCats]         = useState(new Set(styleConfig.defaultCats));
  const [presupuesto, setPresupuesto]           = useState('');
  const [attemptCount, setAttemptCount]         = useState(0);
  const [currentOutfitUrls, setCurrentOutfitUrls] = useState([]);
  const [result, setResult]                     = useState(null);
  const [loading, setLoading]                   = useState(false);
  const [error, setError]                       = useState(null);
  const [sentSlots, setSentSlots]               = useState(() => new Set());
  const [removedSlots, setRemovedSlots]         = useState(() => new Set());
  const [saving, setSaving]                     = useState(false);
  const [greedyToast, setGreedyToast]           = useState(false);
  const [greedyExcluded, setGreedyExcluded]     = useState([]);
  const [resetting, setResetting]               = useState(false);

  // Core load function — called on mount and on re-roll
  const load = useCallback(async (excluir = [], isGreedy = false, pinUrls = []) => {
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
        pin: pinUrls,
        estilo: styleConfig.estilo,
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
  }, [selectedCats, presupuesto, genero, styleConfig.estilo]);

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
    await resetOutfitFeedback(styleConfig.estilo);
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
      estilo: styleConfig.estilo,
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
    const nextExcluded = [...new Set([...greedyExcluded, url])];
    setGreedyExcluded(nextExcluded);
    const pinUrls = currentOutfitUrls.filter(u => u !== url);
    load(nextExcluded, attemptCount > 10, pinUrls);
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

      {/* Form — same language as the supplements builder: design tokens instead of
          inline styles, one card per decision, and a picker that scrolls inside its
          own card rather than stretching the page.

          The four groups used to be collapsible accordions, which traded one problem
          for another: collapsed, you could not see what was selected without opening
          each group; expanded, 43 chips pushed the budget, the button and the outfit
          itself below the fold. The scrolling card with a pinned "Seleccionados" row
          answers both — the summary is always visible and the page never grows.

          Not carried over: the commented-out "presupuesto suplementos" input. It was
          dead markup behind dead state — /api/outfits/builder still has no supplement
          budget to send it to, and SuplementosPanel is where that decision lives now. */}
      <div style={{ display:'flex', flexDirection:'column', gap:16 }}>

        {/* Gender tabs — Hombre / Mujer only (UOB-02) */}
        <div className="flex flex-wrap items-center gap-[8px]">
          <span className="text-[.72rem] font-semibold text-t4">Género:</span>
          {['hombre', 'mujer'].map(g => (
            <button
              key={g}
              onClick={() => handleGeneroChange(g)}
              aria-pressed={genero === g}
              className={cn(
                'inline-flex min-h-[44px] cursor-pointer items-center rounded-btn px-[16px] py-[8px] text-[.9rem] capitalize',
                '[touch-action:manipulation] transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
                genero === g
                  ? 'border border-transparent bg-primary text-white'
                  : 'border border-bd2 bg-s2 text-t2 hover:border-primary'
              )}
            >
              {g}
            </button>
          ))}
          <button
            onClick={handleResetFeedback}
            disabled={resetting}
            title="Borra el historial de Me gusta / No me gusta para que el generador empiece desde cero"
            className={cn(
              'ml-auto inline-flex min-h-[44px] items-center rounded-btn border border-bd2 bg-s2 px-[14px] py-[8px]',
              'text-[.8rem] font-semibold text-t3 transition-colors',
              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
              resetting ? 'cursor-default opacity-50' : 'cursor-pointer hover:border-primary'
            )}
          >
            {resetting ? 'Reseteando...' : '↺ Resetear gustos'}
          </button>
        </div>

        {/* Category picker (UOB-04) */}
        <div className="rounded-card bg-s1 px-[20px] py-[20px] sm:px-[24px]">
          <p className="mb-[14px] text-[.85rem] font-semibold text-t2">
            ¿Qué prendas querés en el outfit?
          </p>
          {/* bg-s1 on the picker and not only on the card: the pinned header uses
              `bg-inherit`, which inherits the COMPUTED colour of its parent — without
              a background of its own on this root it resolves to transparent and the
              chips scroll visibly underneath it. */}
          <MultiSelectTags
            data-testid="cat-picker"
            groups={PICKER_GROUPS}
            selected={selectedCats}
            onToggle={toggleCat}
            stickySelected
            className="max-h-[min(56vh,440px)] overflow-y-auto bg-s1"
          />
        </div>

        {/* Budget + generate (UOB-08, UOB-09) */}
        <div className="flex flex-wrap items-end gap-[12px]">
          <div className="min-w-[200px] flex-1">
            <label
              htmlFor={presupuestoId}
              className="mb-[6px] block text-[.8rem] font-semibold text-t3"
            >
              Presupuesto del outfit (opcional)
            </label>
            <MoneyInput
              id={presupuestoId}
              value={presupuesto}
              onChange={handlePresupuestoChange}
              placeholder="Ej: 150.000"
            />
          </div>
          <button
            onClick={() => { setAttemptCount(0); setCurrentOutfitUrls([]); load([], false); }}
            disabled={loading || !selectedCats.size}
            className={cn(
              'inline-flex min-h-[44px] shrink-0 items-center whitespace-nowrap rounded-btn px-[28px]',
              'text-[.9rem] font-bold transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary',
              (loading || !selectedCats.size)
                ? 'cursor-not-allowed bg-s3 text-t3'
                : 'cursor-pointer bg-primary text-white hover:bg-primary2'
            )}
          >
            {loading ? 'Buscando...' : 'Armar'}
          </button>
        </div>
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
            style="gym"
            favoritos={favoritos}
            onAddFavorito={onAddFavorito}
            savedOutfits={savedOutfits}
            onSaveOutfit={onSaveOutfit}
          />
        )}
        {tab === 'casual'  && (
          <OutfitPanel
            style="casual"
            favoritos={favoritos}
            onAddFavorito={onAddFavorito}
            savedOutfits={savedOutfits}
            onSaveOutfit={onSaveOutfit}
          />
        )}
        {tab === 'formal'  && <PlaceholderTab label="Formal"/>}
      </div>
    </div>
  );
}
