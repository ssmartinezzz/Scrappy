// Brand logos are static files in public/brand-logos/ (sources and licenses in its
// README.md). Clearbit's free logo API, which this used to call, no longer resolves.
// A brand without a logo falls back to the initials badge.
//
// Two ways a file gets served: an entry in BRAND_LOGO_FILES (hand-curated brand marks), or just
// being in the folder: scripts/fetch-site-logos.mjs regenerates brandLogos.generated.js from it,
// so a store's `<key>.<ext>` needs no wiring here.
import { LOGO_FILES_BY_NAME } from './brandLogos.generated.js';

export const BRAND_LOGO_FILES = {
  "adidas": "adidas.svg",
  "puma": "puma.svg",
  "nike": "nike.svg",
  "under armour": "under-armour.svg",
  "fila": "fila.svg",
  "reebok": "reebok.svg",
  "new balance": "new-balance.svg",
  "salomon": "salomon.svg",
  "topper": "topper.svg",
  "vans": "vans.svg",
  "kappa": "kappa.svg",
  "umbro": "umbro.svg",
  "converse": "converse.svg",
  "mizuno": "mizuno.svg",
  "lacoste": "lacoste.svg",
  "brooks": "brooks.svg",
  "le coq sportif": "le-coq-sportif.svg",
  "cellucor": "cellucor.png",
  "saucony": "saucony.png",
  "dc": "dc.png",
  "columbia": "columbia.png",
  "crocs": "crocs.png",
  "champion": "champion.png",
  "quiksilver": "quiksilver.png",
  "timberland": "timberland.png",
  "star nutrition": "star-nutrition.png",
  "gold nutrition": "gold-nutrition.png",
  "gentech": "gentech.png",
  "optimum nutrition": "optimum-nutrition.svg",
  "crudda": "crudda.png",
  "labs nutrition": "labs-nutrition.png",
  "natuliv": "natuliv.svg",
  "innovanaturals": "innovanaturals.png",
  "muecas": "muecas.png",
  "universal nutrition": "universal-nutrition.svg",
  "entrenuts": "entrenuts.png",
  "asics": "asics.svg",
  "ena": "ena.svg",
  "leguilab": "leguilab.png",
  "nutrex": "nutrex.svg",
  "grosz nutrition": "grosz-nutrition.png",
  "mervick": "mervick.png",
  "penalty": "penalty.svg",
  "freres": "freres.svg",
  "fuark": "fuark.png",
  "mmartinez": "mmartinez.png",
  "monkyforce": "monkyforce.png",
  "harvey": "harvey.png",
  "bulks": "bulks.png",
  "bullbenny": "bullbenny.png",
  "batuk": "batuk.png",
  "tussy": "tussy.png",
  "foreverbstrd": "foreverbstrd.png",
  "forever": "forever.png",
  "fursten": "fursten.png",
  "barnes": "barnes.png",
  "vcp": "vcp.png",
  "eldon": "eldon.png",
  "midway": "midway.png",
  "inpro": "inpro.svg",
  "flowin": "flowin.png",
  "vayzenhome": "vayzenhome.png",
  "mrplacadeco": "mrplacadeco.png",
  "ambiancedeco": "ambiancedeco.png",
};

// Site keys whose logo is filed under a different brand name; everything else matches as-is.
export const SITIO_LOGO_MARCA = { underarmour: 'under armour', dcshoes: 'dc' };

// White or light-grey marks: they need a dark plate behind them.
export const LOGOS_SOBRE_OSCURO = new Set([
  'barnes', 'bulks', 'crudda', 'foreverbstrd', 'labs nutrition', 'mervick', 'mrplacadeco', 'vayzenhome',
  'armytech', 'ayrun', 'entreno', 'equus', 'hadriatica', 'macstation', 'notebookspro', 'oneplaceworld', 'prestigio', 'sommiercenter', 'sporting', 'vaypol',
]);

const clave = marca => (marca || '').trim().toLowerCase().replace(/\s+/g, ' ');

/** File name (inside public/brand-logos) of the brand's logo, or undefined. */
export function brandLogoFile(marca) {
  const k = clave(marca);
  return BRAND_LOGO_FILES[k] ?? LOGO_FILES_BY_NAME[k];
}

/** Same-origin URL of the brand's logo, or null when there is none. */
export function getBrandLogoUrl(marca) {
  const file = brandLogoFile(marca);
  return file ? `${import.meta.env.BASE_URL}brand-logos/${file}` : null;
}

export function logoSobreOscuro(marca) {
  return LOGOS_SOBRE_OSCURO.has(clave(marca));
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
    hash = (hash * 31 + str.codePointAt(i)) >>> 0;
  }
  const hue = hash % 360;
  return `hsl(${hue}, 58%, 38%)`;
}
