import { fmt, BADGE_LABELS } from '../api';
import { TIPO_META, SEMANTIC } from '../lib/colors';

// Extracted VERBATIM from PicksPanel.jsx CatDetail (formerly inline in the
// removed .picks-pick-list grid) — zero visual diff. Shared by CategoryPicksView
// (used both in-panel and on the routed /picks/:categoria page) via
// CategoryPicksCarousel's renderItem. Keeps all .picks-pick-* plain-CSS classes.
export default function PickCard({ pick, mediana, onClick }) {
  const m = TIPO_META[pick.tipo] || TIPO_META.valor;
  const unitVal = pick.precioUnitario ?? pick.precio;
  const pctBajoMedia = mediana > 0 && unitVal > 0
    ? ((mediana - unitVal) / mediana * 100).toFixed(0)
    : null;

  return (
    <div
      onClick={() => onClick(pick)}
      className="picks-pick-item"
      style={{ borderColor: `${m.color}33` }}
      onMouseOver={e => e.currentTarget.style.borderColor = m.color}
      onMouseOut={e => e.currentTarget.style.borderColor = `${m.color}33`}>

      {/* Imagen grande arriba + rank en overlay */}
      <div className="picks-pick-imgwrap">
        {pick.img
          ? <img src={pick.img} alt={pick.nombre} loading="lazy"
              className="picks-pick-img"
              onError={e => e.target.style.display='none'}/>
          : <div className="picks-pick-imgph">🛍</div>}
        <span className="picks-pick-rank" title={m.label}>{m.icon}</span>
      </div>

      {/* Cuerpo: etiqueta, nombre, meta, precio al fondo */}
      <div className="picks-pick-body">
        <div className="picks-pick-label" style={{ color: m.color }}>
          {m.label}
        </div>
        <div className="picks-pick-name">{pick.nombre}</div>
        <div className="picks-pick-meta">
          {pick.sitio} · {pick.marca || ''}
          {pick.badge && BADGE_LABELS[pick.badge] && (
            <span className="picks-pick-badge">
              · {BADGE_LABELS[pick.badge]}
            </span>
          )}
        </div>
        {pick.esPack && (
          <span className="badge-pack" title={`Pack x${pick.cantidadUnidades}`}>
            📦 Pack x{pick.cantidadUnidades}{pctBajoMedia && pctBajoMedia > 5 ? ` · -${pctBajoMedia}%` : ''}
          </span>
        )}
        {!pick.esPack && pctBajoMedia && pctBajoMedia > 5 && (
          <div className="picks-pick-pct" style={{ color: SEMANTIC.positive }}>
            {pctBajoMedia}% por debajo de la mediana
          </div>
        )}

        <div className="picks-pick-pricerow">
          <div className="picks-pick-price-val">${fmt(pick.precio)}</div>
          {pick.esPack && (
            <div className="card-price-unit">
              ${fmt(pick.precioUnitario)} c/u · x{pick.cantidadUnidades}
            </div>
          )}
          {pick.scoreP > 0 && (
            <div className="picks-pick-score">score {pick.scoreP}</div>
          )}
        </div>
      </div>
    </div>
  );
}
