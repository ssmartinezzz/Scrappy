import { fmt } from '../api';
import PickCard from './PickCard';
import CategoryPicksCarousel from './CategoryPicksCarousel';

// Generates tagline from statistical data. Shared by CategoryBanner (in
// PicksPanel's PicksGallery) and this component's header — single source of
// truth, was previously duplicated ad hoc inside PicksPanel.jsx.
export function tagline(cat, pick, mediana) {
  if (!pick) return '';
  const badge = pick.badge;
  const pctil = pick.pctil;
  const unitVal = pick.precioUnitario ?? pick.precio;
  const pct = mediana > 0 ? ((mediana - unitVal) / mediana * 100).toFixed(0) : null;

  if (badge === 'precio_historico_bajo') return 'Nunca estuvo tan barato — mínimo histórico';
  if (badge === 'oferta_real')           return 'Descuento verificado estadísticamente';
  if (pct && pct >= 25)  return `Un ${pct}% más barato que la media de ${cat}`;
  if (pct && pct >= 10)  return `Por debajo de la media — buena relación precio/calidad`;
  if (pctil && pctil <= 15) return `Percentil ${pctil}° — entre los más accesibles`;
  if (pick.segment === 'premium') return 'La opción premium más accesible ahora mismo';
  return `El mejor precio/calidad en ${cat} en este momento`;
}

// Shared detail body: header (title, count · mediana, tagline), back control,
// and the picks carousel. Consumed by BOTH PicksPanel's in-panel CatDetail
// and the routed /picks/:categoria page (CategoryPicksPage) so both entries
// render one identical body (design doc ADR-2).
export default function CategoryPicksView({ cat, onBack, onProductClick }) {
  const picks = cat.picks || [];
  const firstPick = picks[0];

  return (
    <div className="picks-catdetail">
      <button onClick={onBack} className="picks-back-btn">
        ← Volver
      </button>

      <h2 className="picks-catdetail-title">
        {cat.categoria}
      </h2>
      <p className="picks-catdetail-meta">
        {(cat.count||0).toLocaleString('es-AR')} productos · mediana ${fmt(cat.mediana)}
      </p>
      {firstPick && (
        <p className="picks-catdetail-tagline">
          "{tagline(cat.categoria, firstPick, cat.mediana)}"
        </p>
      )}

      {picks.length === 0 ? (
        <div className="picks-empty">
          Todavía no hay picks calculados para esta categoría.
        </div>
      ) : (
        <CategoryPicksCarousel
          title={cat.categoria}
          items={picks}
          renderItem={pick => (
            <PickCard pick={pick} mediana={cat.mediana} onClick={onProductClick}/>
          )}
        />
      )}
    </div>
  );
}
