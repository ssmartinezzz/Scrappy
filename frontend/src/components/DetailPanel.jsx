import { useEffect, useState } from 'react';
import * as DialogPrimitive from '@radix-ui/react-dialog';
import { fetchHistorial, fmt, BADGE_LABELS, buscarExterno, EXTERNAL_SEARCH } from '../api';
import BuySignal from './BuySignal';
import { Dialog, DialogOverlay, DialogTitle } from './ui/dialog';

// Visual cue marking a link that navigates AWAY from the app (new tab/external
// site), as opposed to in-panel actions/sections that stay inside the Dialog.
function ExternalCue() {
  return <span aria-hidden="true" className="text-[.85em]">↗</span>;
}

// ─── Gauge ───────────────────────────────────────────────────────────────────
function Gauge({ score = 50 }) {
  const c = Math.min(100, Math.max(0, score));
  const r = 52, cx = 70, cy = 70;
  const rad = ((c / 100) * 180 - 180) * Math.PI / 180;
  const x = cx + r * Math.cos(rad), y = cy + r * Math.sin(rad);
  const color = c <= 33 ? '#3fb950' : c <= 66 ? '#f0a500' : '#e84393';
  const label = c <= 20 ? 'Muy barato' : c <= 40 ? 'Barato' :
                c <= 60 ? 'Normal' : c <= 80 ? 'Caro' : 'Muy caro';
  return (
    <svg width="140" height="85" viewBox="0 0 140 85">
      <path d={`M18,70 A${r},${r} 0 0,1 122,70`} fill="none" stroke="var(--s3)" strokeWidth="10" strokeLinecap="round"/>
      <path d={`M18,70 A${r},${r} 0 0,1 ${x.toFixed(1)},${y.toFixed(1)}`}
            fill="none" stroke={color} strokeWidth="10" strokeLinecap="round"/>
      <text x="70" y="68" textAnchor="middle" fontSize="18" fontWeight="700" fill="var(--t1)">{c}</text>
      <text x="70" y="80" textAnchor="middle" fontSize="9" fill="var(--t4)">{label}</text>
    </svg>
  );
}

// ─── BoxPlot ──────────────────────────────────────────────────────────────────
function BoxPlot({ precio, st }) {
  if (!st?.fence_high) return null;
  const W = 280, H = 40, PAD = 10;
  const lo = Math.max(0, st.fence_low || 0), hi = st.fence_high;
  const sc = v => PAD + Math.min(W-PAD*2, Math.max(0, (v-lo)/(hi-lo)*(W-PAD*2)));
  return (
    <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} style={{ overflow:'visible' }}>
      <line x1={PAD} y1="20" x2={sc(hi)} y2="20" stroke="var(--t4)" strokeWidth="1.5"/>
      <rect x={sc(st.q1)} y="12" width={Math.max(2,sc(st.q3)-sc(st.q1))} height="16"
            fill="rgba(110,64,201,.2)" stroke="var(--p)" strokeWidth="1.5" rx="2"/>
      <line x1={sc(st.median)} y1="11" x2={sc(st.median)} y2="29" stroke="var(--p2)" strokeWidth="2"/>
      <circle cx={sc(precio)} cy="20" r="5" fill="#f0a500" stroke="var(--s1)" strokeWidth="1.5"/>
      {[['Q1',sc(st.q1)],['Med',sc(st.median)],['Q3',sc(st.q3)]].map(([l,x])=>(
        <text key={l} x={x} y="38" textAnchor="middle" fontSize="8" fill="var(--t4)">{l}</text>
      ))}
    </svg>
  );
}

// ─── Sparkline ────────────────────────────────────────────────────────────────
function Sparkline({ hist }) {
  const pts = hist?.puntos || [];
  if (pts.length < 2) return <div className="text-[.72rem] text-t4">Sin historial aún</div>;
  const W = 280, H = 60, PAD = 8;
  const prices = pts.map(p => p.precio);
  const minP = Math.min(...prices), maxP = Math.max(...prices), rangeP = maxP - minP || 1;
  const sx = i => PAD + i * (W-PAD*2) / (pts.length-1);
  const sy = v => H - PAD - (v-minP) / rangeP * (H-PAD*2);
  const d    = pts.map((p,i) => `${i===0?'M':'L'}${sx(i).toFixed(1)},${sy(p.precio).toFixed(1)}`).join(' ');
  const fill = `${d} L${sx(pts.length-1).toFixed(1)},${H} L${sx(0).toFixed(1)},${H} Z`;
  const delta = hist?.deltaPct;
  const dc = delta === undefined ? 'var(--t4)' : delta < 0 ? 'var(--g)' : 'var(--r)';
  return (
    <div>
      <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`}>
        <defs><linearGradient id="sg" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--p)" stopOpacity=".3"/>
          <stop offset="100%" stopColor="var(--p)" stopOpacity="0"/>
        </linearGradient></defs>
        <path d={fill} fill="url(#sg)"/>
        <path d={d} fill="none" stroke="var(--p)" strokeWidth="2" strokeLinecap="round"/>
        <text x={sx(0)} y={H-1} fontSize="8" fill="var(--t4)">${fmt(prices[0])}</text>
        <text x={sx(pts.length-1)} y={H-1} fontSize="8" fill={dc} textAnchor="end">
          ${fmt(prices[prices.length-1])}
        </text>
      </svg>
      {delta !== undefined && (
        // dc is a runtime-computed color (depends on delta sign) — not a static
        // design token, kept inline per the Pill/chip precedent from PR2/PR3.
        <div style={{ color: dc }} className="text-[.65rem] text-right">
          {delta > 0 ? '+' : ''}{delta}% vs inicio
        </div>
      )}
    </div>
  );
}

// ─── Comparativa de precios externos ─────────────────────────────────────────
function PreciosExternos({ product }) {
  const [data,    setData]    = useState(null);   // { resultados, searchUrl, queryUsada }
  const [loading, setLoading] = useState(false);
  const { nombre, precio, url } = product;

  async function cargar() {
    setLoading(true);
    const res = await buscarExterno(nombre, url);
    setData(res);
    setLoading(false);
  }

  const delta = (mlPrecio) => {
    if (!precio || !mlPrecio) return null;
    const d = ((mlPrecio - precio) / precio * 100).toFixed(1);
    return { d, color: +d < 0 ? 'var(--g)' : +d > 5 ? 'var(--r)' : 'var(--t3)' };
  };

  const items     = data?.resultados || [];
  const searchUrl = data?.searchUrl  || EXTERNAL_SEARCH.mercadolibre(nombre);
  const queryUsada = data?.queryUsada;

  return (
    <div>
      <div className="detail-section-title">💹 Comparativa de precios</div>

      {/* Botones de búsqueda directa — SIEMPRE visibles. Son anchors externos:
          llevan el cue/aria-label de "se abre en otro sitio" (tasks 3/4). */}
      <div className="flex flex-wrap gap-1.5 mb-3">
        <a href={searchUrl} target="_blank" rel="noopener noreferrer"
           aria-label="Buscar en MercadoLibre (se abre en una pestaña nueva)"
           className="flex items-center gap-1 rounded-btn border-[1.5px] border-[#f0c000]
                      bg-[rgba(255,224,0,.1)] px-3 py-1.5 text-[.72rem] font-bold
                      text-[#f0c000] no-underline">
          🛒 Buscar en MercadoLibre <ExternalCue />
        </a>
        <a href={EXTERNAL_SEARCH.amazon(nombre)} target="_blank" rel="noopener noreferrer"
           aria-label="Buscar en Amazon (se abre en una pestaña nueva)"
           className="flex items-center gap-1 rounded-btn border-[1.5px] border-border
                      bg-s2 px-3 py-1.5 text-[.72rem] font-semibold text-t2 no-underline">
          📦 Amazon <ExternalCue />
        </a>
        <a href={EXTERNAL_SEARCH.google(nombre)} target="_blank" rel="noopener noreferrer"
           aria-label="Buscar en Google Shopping (se abre en una pestaña nueva)"
           className="flex items-center gap-1 rounded-btn border-[1.5px] border-border
                      bg-s2 px-3 py-1.5 text-[.72rem] font-semibold text-t2 no-underline">
          🔍 Google Shopping <ExternalCue />
        </a>
      </div>

      {/* Precios de ML — on demand, acción IN-PANEL (no navega, no lleva cue externo) */}
      {data === null && !loading && (
        <button className="btn-sm btn-outline w-full py-2 text-[.78rem]"
          onClick={cargar}>
          📊 Ver precios en MercadoLibre (automático)
        </button>
      )}

      {loading && (
        <div className="py-2 text-center text-[.75rem] text-t4">
          Buscando precios...
        </div>
      )}

      {data !== null && (
        <div className="flex flex-col gap-1.5">
          {queryUsada && (
            <div className="mb-0.5 text-[.62rem] text-t4">
              🔎 Query usada: <em className="text-t3">{queryUsada}</em>
            </div>
          )}

          {items.length === 0 && (
            <div className="flex flex-col gap-1.5 rounded-lg border border-border bg-s2 p-2.5 text-[.75rem] text-t3">
              <span>Sin resultados automáticos. Buscá directamente:</span>
              <a href={searchUrl} target="_blank" rel="noopener noreferrer"
                 aria-label="Ver resultados en MercadoLibre (se abre en una pestaña nueva)"
                 className="flex items-center gap-1 font-bold text-[#f0c000] no-underline">
                🛒 Ver resultados en MercadoLibre <ExternalCue />
              </a>
            </div>
          )}

          {/* Precio de referencia */}
          {items.length > 0 && (
            <>
              <div className="flex items-center justify-between rounded-lg border border-border bg-s2 px-3 py-2 text-[.72rem]">
                <span className="text-t4">📍 Precio scrapeado (este sitio)</span>
                <strong className="text-t1">${fmt(precio)}</strong>
              </div>

              {items.map((item, i) => {
                const d = delta(item.precio);
                return (
                  <a key={i} href={item.url} target="_blank" rel="noopener noreferrer"
                     aria-label={`Ver "${item.titulo}" en MercadoLibre (se abre en una pestaña nueva)`}
                     className="flex items-center gap-2.5 rounded-lg border border-border bg-s2 px-3 py-2 no-underline">
                    {item.thumbnail && (
                      <img src={item.thumbnail.replace('http://','https://')}
                           alt="" width="36" height="36"
                           className="flex-shrink-0 rounded object-cover"
                           onError={e=>e.target.style.display='none'} />
                    )}
                    <div className="min-w-0 flex-1">
                      <div className="overflow-hidden whitespace-nowrap text-ellipsis text-[.7rem] text-t2">
                        {item.titulo}
                      </div>
                      <div className="mt-0.5 text-[.63rem] text-t4">
                        {item.condicion === 'new' ? 'Nuevo' : 'Usado'} · MercadoLibre
                      </div>
                    </div>
                    <div className="flex-shrink-0 text-right">
                      <div className="text-[.82rem] font-bold text-t1">
                        ${fmt(item.precio)}
                      </div>
                      {d && (
                        // d.color is a runtime-computed comparison color (cheaper/pricier
                        // vs scraped price) — kept inline per the established exception.
                        <div style={{ color: d.color }} className="text-[.65rem]">
                          {+d.d > 0 ? '+' : ''}{d.d}%
                        </div>
                      )}
                    </div>
                  </a>
                );
              })}

              <a href={searchUrl} target="_blank" rel="noopener noreferrer"
                 aria-label="Ver todos los resultados en MercadoLibre (se abre en una pestaña nueva)"
                 className="flex items-center justify-end gap-1 text-right text-[.68rem] text-primary2 no-underline">
                Ver todos los resultados en MercadoLibre <ExternalCue />
              </a>
            </>
          )}
        </div>
      )}
    </div>
  );
}


// ─── Contexto estadístico en lenguaje natural ────────────────────────────────
function PriceContext({ product: p, st }) {
  const ml  = p.ml || {};
  const items = [];

  // 1. Percentil
  if (ml.pctil !== undefined) {
    const pct = ml.pctil;
    if (pct <= 10)
      items.push({ icon:'🏆', color:'#00b894', text: `Está en el ${pct}° percentil — uno de los más baratos de su categoría` });
    else if (pct <= 25)
      items.push({ icon:'💚', color:'#00b894', text: `Precio en el cuartil inferior — más barato que el ${100-pct}% del mercado` });
    else if (pct <= 50)
      items.push({ icon:'◉', color:'var(--p2)', text: `Por debajo de la mediana — más barato que la mitad del catálogo en ${p.categoria||'su categoría'}` });
    else if (pct <= 75)
      items.push({ icon:'⚠️', color:'#f0a500', text: `Precio por encima de la mediana (percentil ${pct}°) en ${p.categoria||'su categoría'}` });
    else
      items.push({ icon:'📈', color:'#e84393', text: `Precio alto — percentil ${pct}°, más caro que el ${pct}% del mercado` });
  }

  // 2. Vs media de la categoría
  if (st?.mean && p.precio > 0) {
    const diffPct = ((p.precio - st.mean) / st.mean * 100).toFixed(1);
    if (diffPct < -15)
      items.push({ icon:'💰', color:'#00b894', text: `${Math.abs(diffPct)}% más barato que la media de ${p.categoria||'su categoría'} ($${p.precio > 0 ? Math.round(st.mean).toLocaleString('es-AR') : '—'})` });
    else if (diffPct < 0)
      items.push({ icon:'◎', color:'var(--t3)', text: `Ligeramente por debajo de la media ($${Math.round(st.mean).toLocaleString('es-AR')}) de ${p.categoria||'su categoría'}` });
    else if (diffPct > 15)
      items.push({ icon:'💎', color:'#f0a500', text: `${diffPct}% más caro que la media — podría ser premium o tener características especiales` });
  }

  // 3. Vs mediana
  if (st?.median && p.precio > 0) {
    const diffMed = ((p.precio - st.median) / st.median * 100).toFixed(1);
    if (Math.abs(diffMed) > 5) {
      const dir = diffMed < 0 ? 'por debajo' : 'por encima';
      const col = diffMed < 0 ? '#00b894' : '#f0a500';
      items.push({ icon: diffMed < 0 ? '📉' : '📊', color: col,
        text: `${Math.abs(diffMed)}% ${dir} de la mediana ($${Math.round(st.median).toLocaleString('es-AR')}) de ${p.categoria||'su categoría'}` });
    }
  }

  // 4. Z-score
  if (ml.zScore !== undefined) {
    const z = ml.zScore;
    if (z <= -2)
      items.push({ icon:'⚡', color:'#00b894', text: `Z-score ${z.toFixed(2)}: estadísticamente muy barato — posible error de precio o promoción especial` });
    else if (z <= -1)
      items.push({ icon:'✅', color:'#00b894', text: `Z-score ${z.toFixed(2)}: precio bien por debajo del promedio ajustado` });
    else if (z >= 2)
      items.push({ icon:'🔴', color:'#e84393', text: `Z-score ${z.toFixed(2)}: estadísticamente caro — outlier superior` });
  }

  // 5. CV de la categoría — qué tan fiable es la comparación
  if (st?.cv !== undefined) {
    if (st.cv > 80)
      items.push({ icon:'ℹ️', color:'var(--t4)', text: `La categoría ${p.categoria||''} tiene alta variabilidad (CV=${st.cv}%) — los precios varían mucho, comparar individualmente` });
    else if (st.cv < 30)
      items.push({ icon:'📌', color:'var(--t4)', text: `Categoría homogénea (CV=${st.cv}%) — los precios son consistentes, esta comparación es muy confiable` });
  }

  // 6. Historial
  if (ml.tendencia === 'bajando')
    items.push({ icon:'📉', color:'#00b894', text: 'El precio viene bajando en los últimos días — buena oportunidad' });
  else if (ml.tendencia === 'subiendo')
    items.push({ icon:'📈', color:'#f0a500', text: 'El precio está subiendo — conviene comprar pronto' });

  if (!items.length) return null;

  return (
    <div>
      <div className="detail-section-title">🧠 Contexto de precio</div>
      <div className="flex flex-col gap-1.5">
        {items.slice(0, 4).map((item, i) => (
          // border color is per-item runtime data (item.color comes from the ML
          // context computation above) — not a static design token, kept inline
          // per the established Pill/chip exception.
          <div key={i} style={{ border: `1px solid ${item.color}22` }}
               className="flex items-start gap-2.5 rounded-lg bg-s2 px-3 py-2 text-[.73rem] text-t3">
            <span className="flex-shrink-0 text-[.85rem]">{item.icon}</span>
            <span dangerouslySetInnerHTML={{ __html: item.text
              .replace(/\$[\d.,]+/g, m => `<strong style="color:${item.color}">${m}</strong>`)
            }}/>
          </div>
        ))}
      </div>
    </div>
  );
}

// ─── DetailPanel ─────────────────────────────────────────────────────────────
export default function DetailPanel({ product: p, catStats, onClose }) {
  const [hist, setHist] = useState(null);
  const ml  = p.ml || {};
  const st  = catStats?.[p.categoria];
  const segColors = { budget:'#00b894', standard:'var(--p2)', premium:'#f0a500', luxury:'var(--r)' };

  useEffect(() => {
    if (p.url) fetchHistorial(p.url).then(setHist).catch(() => setHist(null));
  }, [p.url]);

  // DetailPanel only mounts while a product is selected (see AppLayout.jsx
  // `{S.detailProd && <DetailPanel .../>}`), so it is always "open" for as long
  // as it exists in the tree. Radix Dialog's onOpenChange(false) fires on ESC,
  // outside-click, and the Close control — all map to the same onClose prop
  // the parent already passes, so the open/close trigger logic is unchanged.
  // This replaces the manual ESC keydown useEffect and the hand-rolled
  // .detail-backdrop onClick with Radix's native focus-trap/ESC/backdrop-click.
  // Mirrors Sidebar.jsx's wiring: use the raw DialogPrimitive.Content (not the
  // generic DialogContent) so the existing .detail-panel/.detail-backdrop CSS
  // classes keep their original z-index/positioning/responsive rules — Radix
  // only supplies the open/close behavior, not the visual shell.
  return (
    <Dialog open modal onOpenChange={next => { if (!next) onClose(); }}>
      <DialogPrimitive.Portal>
        <DialogOverlay className="detail-backdrop z-[399] bg-transparent" />
      </DialogPrimitive.Portal>
      <DialogPrimitive.Content
        onEscapeKeyDown={onClose}
        onPointerDownOutside={onClose}
        className="detail-panel z-[400] outline-none"
      >
        <div className="detail-header">
          <DialogTitle asChild><h3>{p.nombre}</h3></DialogTitle>
          <button className="detail-close" onClick={onClose}>✕</button>
        </div>

        {p.img && (
          <img className="detail-img" src={p.img} alt={p.nombre}
               onError={e => { e.target.style.display='none'; }} />
        )}

        <div className="detail-body">
          {ml.badge && BADGE_LABELS[ml.badge] && (
            <span className={`badge-ml badge-${ml.badge}`}>{BADGE_LABELS[ml.badge]}</span>
          )}

          {ml.scoreP !== undefined && (
            <div className="gauge-wrap"><Gauge score={ml.scoreP} /></div>
          )}

          <div className="detail-stat-grid">
            <div className="detail-stat">
              <div className="detail-stat-val">ARS ${fmt(p.precio)}</div>
              <div className="detail-stat-lbl">Precio scrapeado</div>
            </div>
            <div className="detail-stat">
              {/* segment color is data-driven (ml.segment) — runtime value, not a
                  static token, kept inline per the established exception. */}
              <div className="detail-stat-val"
                   style={{ color: segColors[ml.segment] || 'var(--t3)' }}>
                {(ml.segment || '—').toUpperCase()}
              </div>
              <div className="detail-stat-lbl">Segmento</div>
            </div>
            <div className="detail-stat">
              <div className="detail-stat-val">{ml.pctil !== undefined ? `${ml.pctil}°` : '—'}</div>
              <div className="detail-stat-lbl">Percentil cat.</div>
            </div>
            <div className="detail-stat">
              <div className="detail-stat-val">
                {ml.zScore !== undefined ? `${ml.zScore>0?'+':''}${Number(ml.zScore).toFixed(2)}` : '—'}
              </div>
              <div className="detail-stat-lbl">Z-score</div>
            </div>
          </div>

          {st && (
            <div>
              <div className="detail-section-title">Distribución en {p.categoria || '—'}</div>
              <BoxPlot precio={p.precio} st={st} />
              <div className="mt-2 flex flex-wrap gap-4">
                {[['Mediana',fmt(st.median)],['Media',fmt(st.mean)],
                  ['Moda',fmt(st.mode)],['CV',`${st.cv}%`]].map(([l,v])=>(
                  <span key={l} className="text-[.7rem] text-t4">
                    {l}: <strong className="text-t2">{v}</strong>
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Contexto estadístico */}
          <PriceContext product={p} st={st} />

          {/* Historial de precio: inline, sin cue externo — se queda en el panel */}
          <div>
            <div className="detail-section-title">Historial de precio</div>
            <Sparkline hist={hist} />
          </div>

          {/* ── COMPARATIVA DE PRECIOS EXTERNOS ── */}
          {p?.url && <BuySignal url={p.url}/>}
          <PreciosExternos product={p} />

          {p.url && (
            <a href={p.url} target="_blank" rel="noopener noreferrer"
               aria-label={`Ver en ${p.sitio} (se abre en una pestaña nueva)`}
               className="btn-primary flex items-center justify-center gap-1.5 text-center no-underline">
              Ver en {p.sitio} <ExternalCue />
            </a>
          )}
        </div>
      </DialogPrimitive.Content>
    </Dialog>
  );
}
