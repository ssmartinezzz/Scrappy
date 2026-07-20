// Single source of truth for ML/semantic badge colors. CSS surfaces read the
// mirrored `--sem-*` custom properties in styles.css (same hex values); JS
// surfaces import directly from here. Changing a role's hex in ONE place
// (this file + its CSS mirror) updates every consumer — see design doc
// "Decision: Badge/semantic color single source of truth".
//
// 7 canonical semantic roles (collapses prior divergent per-file hex):
export const SEMANTIC = {
  positive: '#2E7D52', // below_market, budget, cheapest, muy/buen_momento, bajando
  negative: '#C0392B', // above_market, luxury, caro, subiendo
  warn:     '#D08A1E', // premium, esperar, all_time_low
  oferta:   '#8E5BD6', // verified_deal, histLow
  trend:    '#D2622A', // trending, comprar_ahora, oferta (pick)
  gym:      '#6E9B2E', // gymrat, subcat chips
  pack:     '#8E5BD6', // pack/combo, unit price (= oferta)
};

// Single source of truth for the 7 badge keys (badges-oportunidades-revamp
// spec "Badge Key Taxonomy → Renamed Keys and Labels"): key -> {label, color,
// tier}. tier drives styling (positive/neutral deal callouts vs. warning for
// above_market/fake_discount — spec "Negative Badges Visible as Warnings").
// BADGE_LABELS/BADGE_COLORS below are re-exported/derived so existing
// consumers (CatalogoFilterBar, ProductCard, GroupCard, TrendsPanel, api.js)
// need no per-key edits.
export const BADGE_META = {
  all_time_low:   { label: '🏆 Mínimo histórico',          color: SEMANTIC.warn,     tier: 'positive' },
  below_market:   { label: '💚 Por debajo del mercado',    color: SEMANTIC.positive, tier: 'positive' },
  verified_deal:  { label: '✅ Descuento verificado',      color: SEMANTIC.oferta,   tier: 'positive' },
  trending:       { label: '🔥 En demanda',                color: SEMANTIC.trend,    tier: 'neutral'  },
  price_dropping: { label: '📉 Bajando de precio',         color: SEMANTIC.positive, tier: 'positive' },
  above_market:   { label: '📈 Caro vs. mercado',          color: SEMANTIC.negative, tier: 'warning'  },
  fake_discount:  { label: '⚠️ Descuento dudoso',          color: 'var(--t4)',       tier: 'warning'  },
};

// Derived exports (back-compat shape for existing consumers).
export const BADGE_LABELS = Object.fromEntries(
  Object.entries(BADGE_META).map(([k, v]) => [k, v.label]));
export const BADGE_COLORS = Object.fromEntries(
  Object.entries(BADGE_META).map(([k, v]) => [k, v.color]));

// Segmento tiers (Sidebar SEGMENTOS, DetailPanel segColors).
export const SEG_COLORS = {
  budget:   SEMANTIC.positive,
  standard: 'var(--p)',
  premium:  SEMANTIC.warn,
  luxury:   SEMANTIC.negative,
};

// PicksPanel pick-type meta (icon + color + label per pick type).
export const TIPO_META = {
  valor:   { icon: '🥇', color: SEMANTIC.positive, label: 'Mejor precio/calidad' },
  premium: { icon: '💎', color: SEMANTIC.warn,     label: 'Premium accesible'    },
  histLow: { icon: '🏆', color: SEMANTIC.oferta,   label: 'Mínimo histórico'    },
  oferta:  { icon: '🔥', color: SEMANTIC.trend,    label: 'Oferta real'         },
  top:     { icon: '✨', color: SEMANTIC.positive, label: 'Buena compra'        },
};

// Buy-signal (señal de compra) visual config — icon/color/label per
// classification. Re-exported shape matches the former senalConfig.js
// SEÑAL_CONFIG so ProductCard/BuySignal back-compat imports keep working.
export const SEÑAL_CONFIG = {
  comprar_ahora:    { bg: 'color-mix(in srgb, var(--sem-trend) 15%, transparent)',    border: SEMANTIC.trend,    icon: '🔥', label: 'Comprar ahora' },
  muy_buen_momento: { bg: 'color-mix(in srgb, var(--sem-positive) 15%, transparent)', border: SEMANTIC.positive, icon: '✅', label: 'Muy buen momento' },
  buen_momento:     { bg: 'color-mix(in srgb, var(--sem-positive) 10%, transparent)', border: SEMANTIC.positive, icon: '👍', label: 'Buen momento' },
  precio_normal:    { bg: 'color-mix(in srgb, var(--p) 8%, transparent)',             border: 'var(--bd)',       icon: '📊', label: 'Precio normal' },
  esperar:          { bg: 'color-mix(in srgb, var(--sem-warn) 12%, transparent)',     border: SEMANTIC.warn,     icon: '⚠️', label: 'Esperar' },
  caro:             { bg: 'color-mix(in srgb, var(--sem-negative) 12%, transparent)', border: SEMANTIC.negative, icon: '❌', label: 'Caro históricamente' },
  sin_datos:        { bg: 'var(--s3)',                                                border: 'var(--bd)',       icon: '📉', label: 'Sin historial aún' },
};

// Señal values that represent a reliable, renderable signal (excludes sin_datos).
export const SEÑALES_CONFIABLES = Object.keys(SEÑAL_CONFIG).filter(k => k !== 'sin_datos');

export function scoreColor(scoreCompra) {
  return scoreCompra >= 70 ? SEMANTIC.positive : scoreCompra >= 40 ? SEMANTIC.warn : SEMANTIC.negative;
}

// Financing-signal ("¿conviene en cuotas?") visual config. Independent
// palette from SEÑAL_CONFIG by design (spec: "Visually Independent Financing
// Badge" — must never reuse buy-signal entries) — uses --p/--p2 (accent) +
// neutral/indistinto tones instead of the 7 canonical semantic roles.
export const FINANCIACION_CONFIG = {
  conviene_cuotas:   { bg: 'color-mix(in srgb, var(--p) 15%, transparent)',  border: 'var(--p)',  icon: '💳', label: 'Conviene en cuotas' },
  indistinto:        { bg: 'color-mix(in srgb, var(--t4) 12%, transparent)', border: 'var(--t4)', icon: '⚖️', label: 'Indistinto' },
  conviene_contado:  { bg: 'color-mix(in srgb, var(--p2) 15%, transparent)', border: 'var(--p2)', icon: '💵', label: 'Conviene al contado' },
  sin_preset_activo: { bg: 'var(--s3)',                                     border: 'var(--bd)', icon: '➖', label: 'Sin preset activo' },
};

// Señal values that represent a reliable, renderable financing signal
// (excludes sin_preset_activo / sin_datos — calculator fallback states).
export const FINANCIACION_SEÑALES_CONFIABLES = Object.keys(FINANCIACION_CONFIG)
  .filter(k => k !== 'sin_preset_activo');

// Gauge/percentile color ramp (ProductCard PriceBar, DetailPanel Gauge/BoxPlot).
export function gaugeColor(pct) {
  return pct <= 33 ? SEMANTIC.positive : pct <= 66 ? SEMANTIC.warn : SEMANTIC.negative;
}
