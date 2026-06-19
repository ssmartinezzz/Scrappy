// Shared señal (buy-signal) visual config — icon/color/label per classification.
// Used by the compact grid badge (ProductCard) and the full panel (BuySignal).
export const SEÑAL_CONFIG = {
  comprar_ahora:    { bg:'rgba(253,100,0,.15)', border:'#fd6400', icon:'🔥', label:'Comprar ahora' },
  muy_buen_momento: { bg:'rgba(63,185,80,.15)', border:'#3fb950', icon:'✅', label:'Muy buen momento' },
  buen_momento:     { bg:'rgba(63,185,80,.10)', border:'#3fb950', icon:'👍', label:'Buen momento' },
  precio_normal:    { bg:'rgba(163,113,247,.08)', border:'var(--bd)', icon:'📊', label:'Precio normal' },
  esperar:          { bg:'rgba(240,165,0,.12)', border:'#f0a500', icon:'⚠️', label:'Esperar' },
  caro:             { bg:'rgba(232,67,147,.12)', border:'#e84393', icon:'❌', label:'Caro históricamente' },
  sin_datos:        { bg:'var(--s3)', border:'var(--bd)', icon:'📉', label:'Sin historial aún' },
};

// Señal values that represent a reliable, renderable signal (excludes sin_datos).
export const SEÑALES_CONFIABLES = Object.keys(SEÑAL_CONFIG).filter(k => k !== 'sin_datos');

export function scoreColor(scoreCompra) {
  return scoreCompra >= 70 ? '#3fb950' : scoreCompra >= 40 ? '#f0a500' : '#e84393';
}
