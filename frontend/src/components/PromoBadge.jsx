import { MEDIOS_VISIBLES, textoPromoCorta } from '../lib/promos';
import MedioPagoIcon from './MedioPagoIcon';

// Mismo patrón que StockBadge: dueño único, la card es un caller fino. Sólo se
// pinta si el servidor mandó `mejorPromo` (recargo nunca llega como mejor).
function Cupon() {
  return (
    <svg className="badge-promo-ico" width="11" height="11" viewBox="0 0 24 24" aria-hidden="true"
         fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 8a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4Z" />
      <path d="M14 6v12" strokeDasharray="2 3" />
    </svg>
  );
}

export default function PromoBadge({ product }) {
  const texto = textoPromoCorta(product?.mejorPromo);
  if (!texto) return null;
  return (
    <span className="badge-promo" title={texto}>
      <Cupon />
      <span className="badge-promo-txt">{texto}</span>
    </span>
  );
}

// Fila de iconos en el orden del servidor (Mercado Pago, Transferencia y
// Santander primero), capeada a MEDIOS_VISIBLES + "+N" con el resto en el title.
export function MediosPago({ medios }) {
  if (!medios?.length) return null;
  const visibles = medios.slice(0, MEDIOS_VISIBLES);
  const resto = medios.slice(MEDIOS_VISIBLES);
  return (
    <span className="medios-row">
      {visibles.map(m => <MedioPagoIcon key={m} medio={m} size={16} />)}
      {resto.length > 0 && (
        <span className="badge-compact" title={resto.join(', ')}>+{resto.length}</span>
      )}
    </span>
  );
}
