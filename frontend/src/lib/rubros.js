/**
 * El vocabulario de `rubro`, en un solo lugar.
 *
 * Vivía copiado palabra por palabra en `Topbar.jsx`, `PicksPanel.jsx` y
 * `MarcasPanel.jsx`. Tres copias de una taxonomía es la forma en la que un
 * rubro nuevo entra al backend y queda inalcanzable desde media UI: sin error,
 * sin warning, sólo productos que no aparecen en una pantalla y sí en otra.
 *
 * Las claves son exactamente el dominio de `chk_productos_rubro_domain`
 * (`V27`). Mandar cualquier otra cosa a `/api/data?rubro=` filtra por un valor
 * que ninguna fila tiene y devuelve una vista vacía.
 */

/** El primero es el neutro: clave vacía = no filtrar. */
export const RUBROS = [
  { key: '',             icon: '🛍', label: 'Todos'        },
  { key: 'indumentaria', icon: '👕', label: 'Indumentaria' },
  { key: 'tecnologia',   icon: '💻', label: 'Tecnología'   },
  { key: 'suplementos',  icon: '💊', label: 'Suplementos'  },
  { key: 'oficina',      icon: '🪑', label: 'Oficina'      },
];

export const RUBRO_KEYS = RUBROS.map((r) => r.key);

/** La etiqueta de un rubro, o la clave cruda si no está en el vocabulario. */
export function rubroLabel(key) {
  const found = RUBROS.find((r) => r.key === key);
  return found ? found.label : key;
}
