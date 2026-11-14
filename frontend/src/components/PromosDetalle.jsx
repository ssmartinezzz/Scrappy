import { textoPromoLarga, vigenciaTexto } from '../lib/promos';
import MedioPagoIcon from './MedioPagoIcon';

const ALCANCE = { sitio: 'Todo el sitio', producto: 'Este producto' };

// Sección del panel de detalle. Sin promos no hay sección (vacío = sin opinión).
export default function PromosDetalle({ promos }) {
  const lista = (promos ?? []).filter(p => textoPromoLarga(p));
  if (lista.length === 0) return null;
  return (
    <div>
      <div className="detail-section-title">Promos y medios de pago</div>
      <ul className="promo-list">
        {lista.map((p, i) => {
          const vigencia = vigenciaTexto(p);
          const medio = p.medio?.nombre;
          return (
            <li key={`${medio}|${p.red?.nombre}|${p.tipo}|${p.cuotas}|${p.pct}|${p.alcance}|${i}`} className="promo-item">
              <MedioPagoIcon medio={medio} size={22} />
              <div className="promo-item-body">
                <div className="promo-item-main">{textoPromoLarga(p)}</div>
                <div className="promo-item-sub">{p.red?.nombre ? `${medio} ${p.red.nombre}` : medio}</div>
                <div className="promo-item-meta">
                  <span>{ALCANCE[p.alcance] ?? p.alcance}</span>
                  {vigencia && <span>{vigencia}</span>}
                </div>
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
