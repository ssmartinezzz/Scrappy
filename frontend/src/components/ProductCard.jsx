import { memo } from 'react';
import { BADGE_LABELS, fmt, addFavorito, removeFavorito } from '../api';
import { SEÑAL_CONFIG, gaugeColor } from '../lib/colors';
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

// Ahorro del precio unitario del pack vs la mediana de la categoría
function PackBadge({ product: p, catStats }) {
  if (!p.esPack) return null;
  const st = catStats?.[p.categoria];
  let ahorro = null;
  if (st?.median > 0 && p.precioUnitario > 0) {
    const pct = Math.round((st.median - p.precioUnitario) / st.median * 100);
    if (pct > 5) ahorro = pct;
  }
  return (
    <span className="badge-pack" title={ahorro ? `Precio unitario ${ahorro}% más barato que la mediana de ${p.categoria}` : undefined}>
      📦 Pack x{p.cantidadUnidades}{ahorro ? ` · -${ahorro}%` : ''}
    </span>
  );
}

// Barra de posición en la distribución (si tenemos stats de categoría)
function PriceBar({ precio, catStats, categoria }) {
  if (!catStats || !catStats[categoria]) return null;
  const st = catStats[categoria];
  if (!st.fence_high || st.fence_high <= 0) return null;
  const pct = Math.min(100, Math.max(0, (precio / st.fence_high) * 100));
  const color = gaugeColor(pct);
  return (
    <div className="card-price-bar">
      <div className="card-price-bar-fill"
           style={{ width: `${pct}%`, background: color }} />
    </div>
  );
}

// Cluster acotado de badges sobre la foto (top-left): max 2 visibles + "+N"
// overflow. Preserva la lógica condicional de cada badge (p.ej. SenalBadge
// oculto sin señal confiable) — solo cambia DÓNDE se renderiza cada uno.
function BadgeCluster({ p, ml, badge, catStats }) {
  const candidates = [
    badge && <span key="ml" className={`badge-ml badge-${ml.badge}`}>{badge}</span>,
    gymSubcat(p) && <span key="gym" className="badge-gymrat">🏋️ {gymSubcat(p)}</span>,
    p.senal?.senal && p.senal.senal !== 'sin_datos' && SEÑAL_CONFIG[p.senal.senal] && (
      <SenalBadge key="senal" senal={p.senal} />
    ),
    p.senalFinanciacion?.senal && <FinanBadge key="finan" finan={p.senalFinanciacion} />,
    p.esPack && <PackBadge key="pack" product={p} catStats={catStats} />,
  ].filter(Boolean);

  if (candidates.length === 0) return null;
  const visible  = candidates.slice(0, 2);
  const overflow = candidates.length - visible.length;

  return (
    <div className="absolute left-2 top-2 z-[2] flex max-w-[calc(100%-1rem)] flex-wrap items-start gap-1">
      {visible}
      {overflow > 0 && (
        <span className="badge-ml" style={{ background: 'rgba(0,0,0,.55)', color: '#fff', border: 'none' }}>
          +{overflow}
        </span>
      )}
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
      {/* Imagen — fixed aspect-ratio crop (letterbox-safe for inconsistent
          third-party photos); onError fallback keeps a neutral placeholder
          + site-name so the card stays usable even with a broken image. */}
      <div className="card-img-wrap aspect-[3/4]">
        {p.img
          ? <img className="card-img" src={p.img} alt={p.nombre} loading="lazy"
                 onError={e => { e.target.style.display = 'none'; e.target.nextSibling?.classList.remove('hidden'); }} />
          : null
        }
        <div className={`card-img-placeholder ${p.img ? 'hidden' : ''}`}>
          <span>👕</span>
          <span className="text-[.6rem] text-t4">{p.sitio}</span>
        </div>

        <BadgeCluster p={p} ml={ml} badge={badge} catStats={catStats} />

        <span className="ov-marca">{p.sitio}</span>
        {p.descuento && <span className="ov-badge">OFERTA</span>}

        {/* Cluster favorito + comparar — unificado bottom-right de la foto */}
        <div className="absolute bottom-2 right-2 z-[3] flex gap-1.5">
          <button
            className={`card-fav-btn ${isFavorito ? 'added' : ''}`}
            onClick={handleFavoritoClick}
            title="Favorito"
          >{isFavorito ? '★' : '☆'}</button>
          <button
            className={`card-compare-btn ${isInComparar ? 'added' : ''}`}
            onClick={handleCompareClick}
            title="Comparar"
          >⚖</button>
        </div>
      </div>

      {/* Body */}
      <div className="card-body">
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

        {/* Cue: toda la card (salvo los botones explícitos) es clickeable para
            abrir el detalle */}
        <span className="card-detail-hint" aria-hidden="true">📊 Ver detalle</span>
      </div>
    </div>
  );
});

export default ProductCard;
