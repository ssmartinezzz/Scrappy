import { PERFUME_SUBCATEGORIAS } from './perfumeria';
import { slugify } from './cat';

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

const c = (value, label) => ({ value, label });
const SOCKETS = [c('am4', 'AM4'), c('am5', 'AM5'), c('lga1700', 'LGA1700'), c('lga1851', 'LGA1851'),
  c('lga1200', 'LGA1200'), c('lga1151', 'LGA1151')];

// Tecnología: the stored values are what SubcategoryResolver / V103 write into `productos.sub_categoria`.
const CHIPS = {
  'perfumeria/Perfume': PERFUME_SUBCATEGORIAS,
  'juguetes/TCG': TCG_FRANQUICIAS,
  'tecnologia/CPU': SOCKETS,
  'tecnologia/Motherboard': SOCKETS,
  'tecnologia/RAM': [c('notebook', 'Notebook'), c('ddr5', 'DDR5'), c('ddr4', 'DDR4'), c('ddr3', 'DDR3')],
  'tecnologia/GPU': [c('nvidia', 'NVIDIA'), c('amd', 'AMD'), c('intel', 'Intel')],
  'tecnologia/Auricular': [c('inalámbrico', 'Inalámbrico'), c('gamer', 'Gamer'), c('oficina', 'Oficina'), c('in-ear', 'In-ear')],
  'tecnologia/PC': [c('gamer', 'Gamer'), c('oficina', 'Oficina')],
  'tecnologia/Almacenamiento': [c('hdd', 'HDD'), c('microsd', 'microSD'), c('nvme', 'NVMe'), c('pendrive', 'Pendrive'), c('ssd', 'SSD')],
  'tecnologia/Teclado': [c('gamer', 'Gamer'), c('inalámbrico', 'Inalámbrico'), c('mecánico', 'Mecánico')],
  'tecnologia/Mouse': [c('gamer', 'Gamer'), c('inalámbrico', 'Inalámbrico')],
  'tecnologia/Cooler': [c('gabinete', 'Gabinete'), c('líquida', 'Líquida'), c('pasta', 'Pasta térmica')],
  'tecnologia/Fuente': [c('80 plus', '80 Plus'), c('modular', 'Modular')],
  'tecnologia/Notebook': [c('gamer', 'Gamer')],
  'tecnologia/Monitor': [c('4k', '4K'), c('curvo', 'Curvo'), c('gaming', 'Gaming')],
  'tecnologia/Joystick': [c('gamepad', 'Gamepad'), c('volante', 'Volante')],
  'tecnologia/Red': [c('adaptador', 'Adaptador'), c('repetidor', 'Repetidor'), c('router', 'Router'), c('switch', 'Switch')],
  'tecnologia/Cable': [c('poder', 'Poder'), c('red', 'Red'), c('usb', 'USB'), c('video', 'Video')],
  'tecnologia/Impresión': [c('impresora', 'Impresora'), c('tinta', 'Tinta'), c('toner', 'Tóner')],
  'tecnologia/Cámara': [c('exterior', 'Exterior'), c('interior', 'Interior'), c('wifi', 'WiFi')],
  'tecnologia/Reloj': [c('deportivo', 'Deportivo'), c('smartwatch', 'Smartwatch')],
};

export function subChipsDe(rubro, categoria) {
  return CHIPS[`${rubro}/${categoria}`];
}

/** The chip whose URL slug is `slug` (see `slugify`), or undefined. */
export function chipPorSlug(chips, slug) {
  return (chips || []).find(chip => slugify(chip.value ?? chip) === slug);
}
