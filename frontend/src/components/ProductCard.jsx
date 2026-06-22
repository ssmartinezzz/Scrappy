import { memo } from 'react';
import { BADGE_LABELS, fmt, addFavorito, removeFavorito } from '../api';
import { SEÑAL_CONFIG } from '../senalConfig';
import FinanBadge from './FinanBadge';

// Derive gym sub-label from product data (ADR-1: computed in frontend, not stored)
function gymSubcat(product) {
  if (!product || !product.gymrat) return null;
  return (product.categoria ? product.categoria : 'Ropa') + ' Gym';
}

// Badge compacto de señal de compra — sourced del precompute embebido en /api/data.
// Sin request adicional. Oculto cuando no hay señal confiable (null/sin_datos).
function SenalBadge({ senal }) {
  if (!senal || !senal.senal || senal.senal === 'sin_datos') return null;
  const cfg = SEÑAL_CONFIG[senal.senal];
  if (!cfg) return null;
  return (
    <span
      className="badge-senal"
      style={{ background: cfg.bg, borderColor: cfg.border, color: 'var(--t1)' }}
      title={`${cfg.label} · ${senal.scoreCompra}/100`}
    >
      {cfg.icon} {senal.scoreCompra}
    </span>
  );
}

// Barra de posición en la distribución (si tenemos stats de categoría)
function PriceBar({ precio, catStats, categoria }) {
  if (!catStats || !catStats[categoria]) return null;
  const st = catStats[categoria];
  if (!st.fence_high || st.fence_high <= 0) return null;
  const pct = Math.min(100, Math.max(0, (precio / st.fence_high) * 100));
  const color = pct <= 33 ? '#3fb950' : pct <= 66 ? '#f0a500' : '#e84393';
  return (
    <div className="card-price-bar">
      <div className="card-price-bar-fill"
           style={{ width: `${pct}%`, background: color }} />
    </div>
  );
}

const ProductCard = memo(function ProductCard({
  product: p,
  catStats,
  isInComparar,
  isFavorito,
  onOpenDetail,
  onToggleComparar,
  onToggleFavorito,
}) {
  const ml    = p.ml || {};
  const badge = ml.badge && BADGE_LABELS[ml.badge];

  function handleCardClick(e) {
    // No abrir detalle si se hace click en botones específicos
    if (e.target.closest('.card-compare-btn') ||
        e.target.closest('.card-fav-btn') ||
        e.target.closest('.ver-btn')) return;
    onOpenDetail(p);  // ← pasa el objeto directo, sin URL lookup
  }

  function handleCompareClick(e) {
    e.stopPropagation();
    onToggleComparar(p);
  }

  function handleFavoritoClick(e) {
    e.stopPropagation();
    if (isFavorito) removeFavorito(p.url);
    else addFavorito({ url: p.url, sitio: p.sitio, nombre: p.nombre });
    onToggleFavorito?.(p);
  }

  return (
    <div className="card" onClick={handleCardClick}>
      {/* Imagen */}
      <div className="card-img-wrap">
        {p.img
          ? <img className="card-img" src={p.img} alt={p.nombre} loading="lazy"
                 onError={e => { e.target.style.display = 'none'; }} />
          : <div className="card-img-placeholder">👕</div>
        }
        <span className="ov-marca">{p.sitio}</span>
        {p.descuento && <span className="ov-badge">OFERTA</span>}
      </div>

      {/* Body */}
      <div className="card-body">
        {badge && (
          <span className={`badge-ml badge-${ml.badge}`}>{badge}</span>
        )}
        {gymSubcat(p) && (
          <span className="badge-gymrat">🏋️ {gymSubcat(p)}</span>
        )}
        <SenalBadge senal={p.senal} />
        <FinanBadge finan={p.senalFinanciacion} />

        <p className="card-name">{p.nombre}</p>

        {p.talles?.length > 0 && (
          <div className="card-talles">
            {p.talles.slice(0, 5).map(t => (
              <span key={t} className="talle-chip">{t}</span>
            ))}
            {p.talles.length > 5 && <span className="talle-chip">+{p.talles.length - 5}</span>}
          </div>
        )}

        <div className="card-prices">
          {p.precioOrig && (() => {
            const raw = String(p.precioOrig).replace(/[^0-9.,]/g,'').replace(/\.(?=\d{3})/g,'').replace(',','.');
            const val = parseFloat(raw);
            return !isNaN(val) && val > 0
              ? <div className="card-price-orig">ARS ${fmt(val)}</div>
              : null;
          })()}
          <div className="card-price">ARS ${fmt(p.precio)}</div>
          {p.esPack && (
            <div className="card-price-unit">
              ARS ${fmt(p.precioUnitario)} c/u · x{p.cantidadUnidades}
            </div>
          )}
          <PriceBar precio={p.precio} catStats={catStats} categoria={p.categoria} />
        </div>

        {p.url && (
          <a className="ver-btn" href={p.url} target="_blank" rel="noopener noreferrer"
             onClick={e => e.stopPropagation()}>
            Ver producto →
          </a>
        )}
      </div>

      {/* Botón favorito */}
      <button
        className={`card-fav-btn ${isFavorito ? 'added' : ''}`}
        onClick={handleFavoritoClick}
        title="Favorito"
      >{isFavorito ? '★' : '☆'}</button>

      {/* Botón comparar */}
      <button
        className={`card-compare-btn ${isInComparar ? 'added' : ''}`}
        onClick={handleCompareClick}
        title="Comparar"
      >⚖</button>
    </div>
  );
});

export default ProductCard;
