import { parseFechaApi } from '../lib/fechas';

// Dueño único del badge de stock (card y panel de detalle son callers finos).
// El servidor ya degrada un chequeo viejo a "desconocido": acá no se re-deriva
// vigencia. Sólo `sin_stock` pinta algo; en_stock / desconocido se abstienen
// (CODE-5: vacío es "sin opinión", nunca "malo").
export function esSinStock(p) {
  return p?.stock === 'sin_stock';
}

function fechaCorta(valor) {
  const fecha = parseFechaApi(valor);
  return fecha ? fecha.toLocaleDateString('es-AR', { day: 'numeric', month: 'numeric' }) : null;
}

export default function StockBadge({ product }) {
  if (!esSinStock(product)) return null;
  const visto = fechaCorta(product.vistoEnStockAt);
  return (
    <span className="badge-sin-stock">
      {visto ? `Sin stock · visto el ${visto}` : 'Sin stock'}
    </span>
  );
}
