// Brand logo resolution via Clearbit Logo API (free, no API key required).
// https://logo.clearbit.com/{domain}?size=128
//
// Only brands we're genuinely confident about get a domain mapped here.
// Regional/local brands (Topper, Flecha, Jaguar, Gola, Penalty, Olympikus,
// Tommy, Bulks, Fuark, Harvey Willys, Harvey) or site names (Sporting, City,
// Vaypol, etc.) are intentionally left unmapped — they fall back to the
// initials badge instead of a guessed/wrong domain.
const BRAND_DOMAINS = {
  nike: 'nike.com',
  adidas: 'adidas.com',
  puma: 'puma.com',
  reebok: 'reebok.com',
  'new balance': 'newbalance.com',
  asics: 'asics.com',
  saucony: 'saucony.com',
  brooks: 'brooksrunning.com',
  hoka: 'hoka.com',
  'on running': 'on-running.com',
  salomon: 'salomon.com',
  mizuno: 'mizuno.com',
  'under armour': 'underarmour.com',
  fila: 'fila.com',
  umbro: 'umbro.com',
  vans: 'vans.com',
  converse: 'converse.com',
  dc: 'dcshoes.com',
  etnies: 'etnies.com',
  volcom: 'volcom.com',
  quiksilver: 'quiksilver.com',
  billabong: 'billabong.com',
  'the north face': 'thenorthface.com',
  columbia: 'columbia.com',
  patagonia: 'patagonia.com',
  timberland: 'timberland.com',
  merrell: 'merrell.com',
  lacoste: 'lacoste.com',
  'calvin klein': 'calvinklein.com',
  "levi's": 'levi.com',
  levis: 'levi.com',
  wrangler: 'wrangler.com',
  champion: 'champion.com',
  kappa: 'kappa.com',
  ellesse: 'ellesse.com',
  'le coq sportif': 'lecoqsportif.com',
  'fred perry': 'fredperry.com',
  caterpillar: 'catfootwear.com',
  keen: 'keenfootwear.com',
  palladium: 'palladiumboots.com',
  crocs: 'crocs.com',
  birkenstock: 'birkenstock.com',
};

/**
 * Returns the Clearbit logo URL for a brand name, or null if we don't have
 * a confident domain mapping for it.
 */
export function getBrandLogoUrl(marca) {
  if (!marca) return null;
  const domain = BRAND_DOMAINS[marca.trim().toLowerCase()];
  return domain ? `https://logo.clearbit.com/${domain}?size=128` : null;
}

/**
 * Derives up to 3 initials from a brand name, one per word.
 * "Nike" -> "N", "New Balance" -> "NB", "The North Face" -> "TNF".
 */
export function getBrandInitials(marca) {
  if (!marca) return '?';
  const words = marca.trim().split(/\s+/).filter(Boolean);
  return words
    .slice(0, 3)
    .map(w => w[0].toUpperCase())
    .join('');
}

/**
 * Deterministic background color for a brand name: simple string hash -> HSL hue.
 * Same brand always gets the same color, no manual palette required.
 */
export function getBrandColor(marca) {
  const str = marca || '';
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) >>> 0;
  }
  const hue = hash % 360;
  return `hsl(${hue}, 58%, 38%)`;
}
