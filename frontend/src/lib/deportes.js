// Display labels for the sport codes the backend stores in `productos.deporte`.
const LABELS = {
  futbol: 'Fútbol',
  natacion: 'Natación',
  hockey: 'Hockey',
  rugby: 'Rugby',
  tenis: 'Tenis',
  basquet: 'Básquet',
  voley: 'Vóley',
  ciclismo: 'Ciclismo',
  golf: 'Golf',
  combate: 'Combate',
};

export const deporteLabel = (code) => LABELS[code] || code;
