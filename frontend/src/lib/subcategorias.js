import { CABELLO_SUBCATEGORIAS, MAQUILLAJE_SUBCATEGORIAS, PERFUME_SUBCATEGORIAS, PIEL_SUBCATEGORIAS } from './belleza';
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
  'belleza/Perfume': PERFUME_SUBCATEGORIAS,
  'belleza/Maquillaje': MAQUILLAJE_SUBCATEGORIAS,
  'belleza/Cuidado de la Piel': PIEL_SUBCATEGORIAS,
  'belleza/Cuidado del Cabello': CABELLO_SUBCATEGORIAS,
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
  // V135: the platform; the stored values are what GamingReglas / V135 write.
  'tecnologia/Consola': [c('playstation', 'PlayStation'), c('xbox', 'Xbox'), c('nintendo switch', 'Nintendo Switch'),
    c('nintendo', 'Nintendo'), c('pc portátil', 'PC portátil'),
    c('emulador portátil', 'Emulador portátil'), c('emulador tv', 'Emulador para TV'), c('retro', 'Retro')],
  'tecnologia/Videojuego': [c('ps5', 'PS5'), c('ps4', 'PS4'), c('playstation retro', 'PlayStation retro'), c('switch 2', 'Switch 2'),
    c('switch', 'Switch'), c('xbox', 'Xbox'), c('nintendo 3ds', 'Nintendo 3DS'), c('wii', 'Wii'), c('pc', 'PC'), c('retro', 'Retro')],
  // V141: Title Case values (NetworkingReglas), so the chip label is the stored value. The network cables left Cable for it.
  'tecnologia/Networking': ['Router', 'Mesh', 'Access Point', 'Switch', 'Repetidor', 'Placa y Adaptador', 'Cable de Red', 'Conector'],
  'tecnologia/Cable': [c('poder', 'Poder'), c('usb', 'USB'), c('video', 'Video')],
  // V142: pads and desk mats are one shelf and always oficina; the values are Title Case (MousepadReglas), in the order of its rules.
  'oficina/Mousepad y Mat': ['Antifatiga', 'Apoyamuñecas', 'Mousepad Ergonómico', 'Mat de Escritorio', 'Mousepad RGB', 'Mousepad XL', 'Mousepad'],
  // V142: only a desk whose name proves a motor; "regulable" alone is height-adjustable and has no chip.
  'oficina/Escritorio': ['Motorizado'],
  'tecnologia/Impresión': [c('impresora', 'Impresora'), c('tinta', 'Tinta'), c('toner', 'Tóner')],
  'tecnologia/Cámara': [c('exterior', 'Exterior'), c('interior', 'Interior'), c('wifi', 'WiFi')],
  'tecnologia/Reloj': [c('deportivo', 'Deportivo'), c('smartwatch', 'Smartwatch')],
  // V128: Title Case values, so the chip label is the stored value.
  // V139: eight of the ten moved to electrodomesticos; a forced technology store still holds all ten under tecnologia.
  'tecnologia/TV y Audio': ['Barra de Sonido', 'Consola y Mixer', 'Equipo de Música', 'Handy', 'Parlante', 'Proyector', 'Radio',
    'Soporte', 'Tocadiscos', 'Televisor'],
  'electrodomesticos/TV y Audio': ['Barra de Sonido', 'Equipo de Música', 'Parlante', 'Proyector', 'Radio', 'Soporte', 'Tocadiscos',
    'Televisor'],
  'electrodomesticos/Pequeño Electrodoméstico': ['Accesorio y Repuesto', 'Afeitadora y Cortadora', 'Batidora y Mixer', 'Cafetera', 'Depiladora',
    'Freidora', 'Licuadora y Exprimidor', 'Pava Eléctrica', 'Plancha de Ropa', 'Planchita y Rizador', 'Procesadora', 'Secador de Pelo',
    'Tostadora y Sandwichera'],
  // V140: the phone category has its own rubro; Batería Externa is carved out of Cargador (power banks).
  'celulares/Celular': ['Smartphone', 'Funda', 'Protector', 'Cargador', 'Batería Externa', 'Cable', 'Soporte', 'Repuesto'],
  // V129: Argentine retail words; the chip label is the stored value.
  'construccion/Iluminación': ['Aplique', 'Colgante', 'Farol y Estaca', 'Guirnalda', 'Lamparita', 'Lámpara de Pie',
    'Luz de Emergencia', 'Panel y Listón', 'Plafón', 'Portalámpara', 'Reflector', 'Spot', 'Tira LED', 'Velador'],
  'hogar/Mueble': ['Alacena', 'Aparador', 'Bajo Mesada', 'Biblioteca', 'Chifonier', 'Cocina Modular', 'Cómoda y Cajonera', 'Despensero',
    'Mesa de Luz', 'Portahorno', 'Rack de TV', 'Vajillero', 'Zapatero'],
  // V130: bedding by product type, mattress by size in plazas (the value keeps no "½": its slug would collide with 1 Plaza).
  'hogar/Ropa de Cama': ['Acolchado', 'Almohada', 'Cubrecama', 'Frazada y Manta', 'Funda', 'Juego de Sábanas', 'Plumón', 'Protector'],
  'hogar/Colchón': ['1 Plaza', '1 1/2 Plaza', '2 Plazas', '2 1/2 Plazas', 'Queen', 'King'],
  // V131: the names of a ferretería.
  'construccion/Herramienta Manual': ['Albañilería', 'Corte y Limado', 'Destornillador y Puntas', 'Engrampadora y Remachadora',
    'Espátula y Rasqueta', 'Juego y Set', 'Llave y Tubo', 'Martillo y Maza', 'Medición', 'Pinza y Alicate', 'Pistola de Silicona',
    'Prensa y Morsa'],
  // V132: the bike type; a stationary bike is exercise equipment and has no chip.
  'deporte/Bicicleta': ['BMX', 'Camicleta', 'Eléctrica', 'Infantil', 'Mountain Bike', 'Paseo y Playera', 'Plegable', 'Ruta', 'Triciclo',
    'Urbana / Fixie'],
};

export function subChipsDe(rubro, categoria) {
  return CHIPS[`${rubro}/${categoria}`];
}

/** The chip whose URL slug is `slug` (see `slugify`), or undefined. */
export function chipPorSlug(chips, slug) {
  return (chips || []).find(chip => slugify(chip.value ?? chip) === slug);
}
