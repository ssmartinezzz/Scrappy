import { RUBROS } from './rubros.js';
import { SITIO_LOGO_MARCA, getBrandLogoUrl } from './brandLogos.js';

// /api/sitios returns the lowercase config key as `nombre`; this is what the UI shows.
export const LABELS = {
  ambiancedeco: 'Ambiance Deco', arredo: 'Arredo', armytech: 'Armytech', ayrun: 'Ayrun', baesics: 'Baesics',
  barnes: 'Barnes', batuk: 'Batuk', bulks: 'Bulks', bullbenny: 'Bullbenny',
  burtonargentina: 'Burton', cetrogar: 'Cetrogar', city: 'City', compragamer: 'CompraGamer', dcshoes: 'DC Shoes',
  dellorean: 'Dellorean', easy: 'Easy', eldon: 'Eldon', elmundodeljuguete: 'El Mundo del Juguete',
  entreno: 'Entreno', equus: 'Equus', flowin: 'Flowin', forever: 'Forever',
  foreverbstrd: 'Forever BSTRD', freres: 'Freres', fuark: 'Fuark', fullh4rd: 'FullH4rd',
  fursten: 'Fursten', gamerfactory: 'GamerFactory', hadriatica: 'Hadriatica', harvey: 'Harvey',
  inpro: 'Inpro', kevingston: 'Kevingston', kingofthekongo: 'King of the Kongo',
  laspepas: 'Las Pepas', maximus: 'Maximus', midway: 'Midway', mmartinez: 'M. Martinez',
  monkyforce: 'Monkyforce', morashop: 'Morashop', mrplacadeco: 'MrPlacaDeco', naldo: 'Naldo', nike: 'Nike',
  notebookspro: 'NotebooksPro', oncity: 'Oncity', oneplaceworld: 'One Place World', rockethard: 'RocketHard', prestigio: 'Prestigio', samsung: 'Samsung', sportline: 'Sportline',
  somosrex: 'Somos Rex', sporting: 'Sporting', tussy: 'Tussy', underarmour: 'Under Armour', vaypol: 'Vaypol',
  vayzenhome: 'Vayzen Home', vcp: 'VCP', venex: 'Venex', zentra: 'Zentra',
};

const collator = new Intl.Collator('es', { sensitivity: 'base' });

const fold = (s) => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();

export function sitioLabel(key) {
  const k = key || '';
  if (LABELS[k]) return LABELS[k];
  // Dynamic extras may already arrive pretty-printed.
  if (/[A-Z\s]/.test(k)) return k;
  return k.charAt(0).toUpperCase() + k.slice(1);
}

export function sitioLogoMarca(key) {
  return SITIO_LOGO_MARCA[key] || key;
}

export function sitioLogoUrl(key) {
  return getBrandLogoUrl(sitioLogoMarca(key));
}

export function ordenarSitios(sitios) {
  return [...sitios].sort((a, b) => collator.compare(sitioLabel(a.nombre), sitioLabel(b.nombre)));
}

/** Ordered `[[rubro, sitios[]]]`: RUBROS order, unknown rubros last; sites sorted by label. */
export function agruparPorRubro(sitios) {
  const grupos = new Map();
  for (const s of ordenarSitios(sitios)) {
    const r = s.rubro || 'indumentaria';
    if (!grupos.has(r)) grupos.set(r, []);
    grupos.get(r).push(s);
  }
  const rank = (r) => {
    const i = RUBROS.findIndex((x) => x.key === r);
    return i === -1 ? Infinity : i;
  };
  return [...grupos.entries()].sort(([a], [b]) => {
    const ra = rank(a);
    const rb = rank(b);
    if (ra === rb) return collator.compare(a, b);
    return ra < rb ? -1 : 1;
  });
}

export function filtrarSitios(sitios, { q = '', vista = 'todas', rubro = '', selected = [] } = {}) {
  const needle = fold(q);
  const sel = new Set(selected);
  return sitios.filter((s) => {
    if (rubro && (s.rubro || 'indumentaria') !== rubro) return false;
    if (vista === 'elegidas' && !sel.has(s.nombre)) return false;
    if (vista === 'no-elegidas' && sel.has(s.nombre)) return false;
    if (!needle) return true;
    return fold(sitioLabel(s.nombre)).includes(needle) || fold(s.nombre).includes(needle);
  });
}
