import { useEffect, useState, useCallback } from 'react';
import { fetchOutfit, sendOutfitFeedback, fetchOutfitBuilder, fmt } from '../api';

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

// ─── Budget Builder taxonomy ───────────────────────────────────────────────────
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

// ─── BuilderTab ───────────────────────────────────────────────────────────────
function BuilderTab() {
  const [selectedCats, setSelectedCats] = useState(new Set());
  const [presupuesto, setPresupuesto]   = useState('');
  const [genero, setGenero]             = useState('');
  const [expanded, setExpanded]         = useState({ torso:true, piernas:true, calzado:true, accesorio:true });
  const [loading, setLoading]           = useState(false);
  const [result, setResult]             = useState(null);
  const [error, setError]               = useState(null);

  function toggleCat(cat) {
    setSelectedCats(prev => {
      const next = new Set(prev);
      next.has(cat) ? next.delete(cat) : next.add(cat);
      return next;
    });
  }

  function toggleGroup(key) {
    setExpanded(prev => ({ ...prev, [key]: !prev[key] }));
  }

  async function handleArmar() {
    if (!selectedCats.size) { setError('Seleccioná al menos una categoría.'); return; }
    const budget = Number(presupuesto);
    if (!budget || budget <= 0) { setError('Ingresá un presupuesto válido.'); return; }
    setError(null);
    setLoading(true);
    setResult(null);
    try {
      const data = await fetchOutfitBuilder({
        categorias: [...selectedCats],
        presupuesto: budget,
        genero: genero || undefined,
      });
      if (data === null) { setError('No hay catálogo cargado. Ejecutá un scraping primero.'); }
      else { setResult(data); }
    } catch {
      setError('Error de conexión.');
    } finally {
      setLoading(false);
    }
  }

  const noFit = result && result.noCumplePresupuesto && (!result.slots || result.slots.length === 0);

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:14 }}>

      {/* Category selector — 4 collapsible groups */}
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

      {/* Budget + gender + action */}
      <div style={{ display:'flex', gap:12, flexWrap:'wrap', alignItems:'center' }}>
        <div style={{ display:'flex', gap:6, alignItems:'center' }}>
          <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Presupuesto total:</span>
          <input
            type="number" placeholder="Ej: 80000"
            value={presupuesto}
            onChange={e => setPresupuesto(e.target.value)}
            style={{ width:130, padding:'3px 8px', fontSize:'.78rem', borderRadius:4,
                     border:'1px solid var(--bd)', background:'var(--s2)', color:'var(--t1)' }}
          />
        </div>
        <div style={{ display:'flex', gap:6, alignItems:'center' }}>
          <span style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600 }}>Género:</span>
          {['', 'hombre', 'mujer', 'unisex'].map(g => (
            <button key={g || 'todos'} onClick={() => setGenero(g)}
              className={`genero-pill ${genero === g ? 'active' : ''}`}>
              {g || 'todos'}
            </button>
          ))}
        </div>
        <button
          onClick={handleArmar}
          disabled={loading}
          style={{
            padding:'5px 18px', borderRadius:8, border:'1px solid var(--p)',
            background:'var(--p)', color:'#fff', fontSize:'.78rem', fontWeight:700,
            cursor: loading ? 'default' : 'pointer', opacity: loading ? .7 : 1,
          }}>
          {loading ? 'Buscando...' : 'Armar'}
        </button>
      </div>

      {/* Validation / error message */}
      {error && (
        <div style={{ color:'#ef4444', fontSize:'.78rem', fontWeight:600 }}>{error}</div>
      )}

      {/* No-fit empty state */}
      {noFit && (
        <div style={{ padding:'1.5rem', borderRadius:8, border:'1px solid var(--bd)',
                      background:'var(--s1)', display:'flex', flexDirection:'column', gap:10 }}>
          <div style={{ fontWeight:700, color:'var(--t1)', fontSize:'.9rem' }}>
            No se puede armar dentro de ${fmt(Number(presupuesto))}
          </div>
          {result.categoriasVacias?.length > 0 && (
            <div>
              <div style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600, marginBottom:4 }}>
                Sin productos en catálogo:
              </div>
              <div style={{ display:'flex', gap:6, flexWrap:'wrap' }}>
                {result.categoriasVacias.map(c => (
                  <span key={c} style={{ padding:'2px 8px', borderRadius:12, fontSize:'.7rem',
                    background:'var(--s2)', border:'1px solid var(--bd)', color:'var(--t3)' }}>
                    {c}
                  </span>
                ))}
              </div>
            </div>
          )}
          {result.categoriasSinPresupuesto?.length > 0 && (
            <div>
              <div style={{ fontSize:'.72rem', color:'var(--t4)', fontWeight:600, marginBottom:4 }}>
                No entran en el presupuesto:
              </div>
              <div style={{ display:'flex', gap:6, flexWrap:'wrap' }}>
                {result.categoriasSinPresupuesto.map(c => (
                  <span key={c} style={{ padding:'2px 8px', borderRadius:12, fontSize:'.7rem',
                    background:'var(--s2)', border:'1px solid var(--bd)', color:'var(--t3)' }}>
                    {c}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Partial-fit or full-fit result */}
      {result && result.slots && result.slots.length > 0 && (
        <div style={{ display:'flex', flexDirection:'column', gap:12 }}>
          {/* Partial-fit warning: categories with no products */}
          {result.categoriasVacias?.length > 0 && (
            <div style={{ fontSize:'.78rem', color:'var(--t4)', display:'flex', gap:6, flexWrap:'wrap', alignItems:'center' }}>
              <span style={{ fontWeight:600 }}>Sin productos:</span>
              {result.categoriasVacias.map(c => (
                <span key={c} style={{ padding:'2px 8px', borderRadius:12, fontSize:'.7rem',
                  background:'var(--s2)', border:'1px solid var(--bd)', color:'var(--t3)' }}>
                  {c}
                </span>
              ))}
            </div>
          )}

          {/* Product cards */}
          <div className="outfit-row">
            {result.slots.map(s => (
              <div key={s.slot} className="outfit-card">
                <div className="kit-tag">
                  <span className="kit-tag-label">{s.slot}</span>
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
                  <a href={s.url} target="_blank" rel="noreferrer"
                    style={{ fontSize:'.68rem', color:'var(--p2)', marginTop:4, display:'block' }}>
                    Ver producto
                  </a>
                </div>
              </div>
            ))}
          </div>

          {/* Total */}
          <div style={{ fontSize:'.8rem', fontWeight:700, color:'var(--t2)' }}>
            Total estimado: ${fmt(result.totalEstimado)}
            {result.noCumplePresupuesto && (
              <span style={{ color:'var(--t4)', fontWeight:400, marginLeft:8 }}>
                · Algunas categorías no entraron en el presupuesto
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── OutfitsPanel principal ────────────────────────────────────────────────────
export default function OutfitsPanel({ favoritos = [], onAddFavorito, savedOutfits = [], onSaveOutfit }) {
  const [tab, setTab] = useState('gym'); // gym | builder | casual | formal

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100%' }}>
      {/* Sub-tab bar */}
      <div style={{
        display:'flex', borderBottom:'1px solid var(--bd)',
        background:'var(--s1)', position:'sticky', top:0, zIndex:10,
      }}>
        {[['gym', 'Gym'], ['builder', 'Armar por presupuesto'], ['casual', 'Casual'], ['formal', 'Formal']].map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} style={{
            padding:'.55rem 1rem', background:'none', border:'none', cursor:'pointer',
            fontSize:'.78rem', fontWeight:600, letterSpacing:'.02em',
            color: tab === k ? 'var(--p2)' : 'var(--t4)',
            borderBottom: tab === k ? '2px solid var(--p2)' : '2px solid transparent',
          }}>{l}</button>
        ))}
      </div>

      <div style={{ flex:1, overflowY:'auto', padding:'1rem 1.25rem' }}>
        {tab === 'gym'     && (
          <GymTab
            favoritos={favoritos}
            onAddFavorito={onAddFavorito}
            savedOutfits={savedOutfits}
            onSaveOutfit={onSaveOutfit}
          />
        )}
        {tab === 'builder' && <BuilderTab />}
        {tab === 'casual'  && <PlaceholderTab label="Casual"/>}
        {tab === 'formal'  && <PlaceholderTab label="Formal"/>}
      </div>
    </div>
  );
}
