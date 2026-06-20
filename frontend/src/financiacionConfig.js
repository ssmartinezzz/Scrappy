// Financing-signal ("¿conviene en cuotas?") visual config — icon/color/label
// per classification. Fully independent palette from senalConfig.js (the
// existing buy-signal): blue/violet financing tones vs. orange/green buy tones,
// so the two badges never look like the same signal (spec: "Visually
// Independent Financing Badge" — must never reuse SEÑAL_CONFIG entries).
export const FINANCIACION_CONFIG = {
  conviene_cuotas:    { bg:'rgba(56,139,253,.15)',  border:'#388bfd', icon:'💳', label:'Conviene en cuotas' },
  indistinto:          { bg:'rgba(110,118,129,.12)', border:'#6e7681', icon:'⚖️', label:'Indistinto' },
  conviene_contado:    { bg:'rgba(165,94,234,.15)',  border:'#a55eea', icon:'💵', label:'Conviene al contado' },
  sin_preset_activo:   { bg:'var(--s3)', border:'var(--bd)', icon:'➖', label:'Sin preset activo' },
};

// Señal values that represent a reliable, renderable financing signal
// (excludes sin_preset_activo / sin_datos — calculator fallback states).
export const FINANCIACION_SEÑALES_CONFIABLES = Object.keys(FINANCIACION_CONFIG)
  .filter(k => k !== 'sin_preset_activo');
