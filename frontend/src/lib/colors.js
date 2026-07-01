// Single source of truth for ML/semantic badge colors. CSS surfaces read the
// mirrored `--sem-*` custom properties in styles.css (same hex values); JS
// surfaces import directly from here. Changing a role's hex in ONE place
// (this file + its CSS mirror) updates every consumer — see design doc
// "Decision: Badge/semantic color single source of truth".
//
// 7 canonical semantic roles (collapses prior divergent per-file hex):
export const SEMANTIC = {
  positive: '#2E7D52', // precio_bajo, budget, cheapest, muy/buen_momento, bajando
  negative: '#C0392B', // precio_alto, luxury, caro, subiendo
  warn:     '#D08A1E', // premium, esperar, precio_historico_bajo
  oferta:   '#8E5BD6', // oferta_real, histLow
  trend:    '#D2622A', // tendencia, comprar_ahora, oferta (pick)
  gym:      '#6E9B2E', // gymrat, subcat chips
  pack:     '#8E5BD6', // pack/combo, unit price (= oferta)
};

// ML badge colors (Sidebar BADGE_COLORS, ProductCard, GroupCard, TrendsPanel
// BADGE_COLOR, PicksPanel tagline references).
export const BADGE_COLORS = {
  precio_historico_bajo: SEMANTIC.warn,
  precio_bajo:           SEMANTIC.positive,
  oferta_real:            SEMANTIC.oferta,
  tendencia:              SEMANTIC.trend,
  precio_bajando:         SEMANTIC.positive,
  precio_alto:            SEMANTIC.negative,
  descuento_cosmetico:    'var(--t4)',
};

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
