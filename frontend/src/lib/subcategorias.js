import { PERFUME_SUBCATEGORIAS } from './perfumeria';

// Sub-category chips of a category detail, keyed `<rubro>/<categoria>`. An entry is a stored `subCategoria` value, or
// `{ value, label }` when the stored value (lowercase, as SubcategoryResolver writes it) reads badly on a chip.
const TCG_FRANQUICIAS = [
  { value: 'pokemon',     label: 'Pokémon' },
  { value: 'dragon ball', label: 'Dragon Ball' },
  { value: 'magic',       label: 'Magic' },
  { value: 'yu-gi-oh',    label: 'Yu-Gi-Oh!' },
  { value: 'one piece',   label: 'One Piece' },
  { value: 'lorcana',     label: 'Lorcana' },
  { value: 'digimon',     label: 'Digimon' },
];

const CHIPS = {
  'perfumeria/Perfume': PERFUME_SUBCATEGORIAS,
  'juguetes/TCG': TCG_FRANQUICIAS,
};

export function subChipsDe(rubro, categoria) {
  return CHIPS[`${rubro}/${categoria}`];
}
