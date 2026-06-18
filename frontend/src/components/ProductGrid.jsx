import { useEffect, useRef, useState } from 'react';
import ProductCard from './ProductCard';

export default function ProductGrid({
  prods, view, meta, catStats, hasMore, total,
  comparar, favoritos, onOpenDetail, onToggleComparar, onToggleFavorito, onLoadMore
}) {
  const loaderRef    = useRef(null);
  const [loading, setLoading] = useState(false);
  const comparaUrls  = new Set(comparar.map(p => p.url));
  const favoritoUrls = new Set((favoritos || []).map(f => f.url));

  // IntersectionObserver for infinite scroll
  useEffect(() => {
    if (!loaderRef.current) return;
    let pending = false;
    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting && hasMore && !loading && !pending) {
          pending = true;
          setLoading(true);
          Promise.resolve(onLoadMore()).finally(() => {
            setLoading(false);
            pending = false;
          });
        }
      },
      { rootMargin: '600px', threshold: 0 }
    );
    observer.observe(loaderRef.current);
    return () => observer.disconnect();
  }, [hasMore, loading, onLoadMore]);

  if (!prods?.length && !loading) {
    return (
      <div className="grid-wrap">
        <div className="empty-state">
          <div className="empty-icon">🔍</div>
          <div className="empty-msg">No hay productos con estos filtros</div>
        </div>
      </div>
    );
  }

  return (
    <div className="grid-wrap">
      {total > 0 && (
        <div style={{ fontSize:'.7rem', color:'var(--t4)', marginBottom:10, paddingLeft:2 }}>
          Mostrando {prods.length} de {total} productos
        </div>
      )}

      <div className={`grid ${view === 'list' ? 'list-mode' : ''}`}>
        {prods.map(p => (
          <ProductCard
            key={p.url || p.nombre}
            product={p}
            catStats={catStats || meta?.catStats}
            isInComparar={comparaUrls.has(p.url)}
            isFavorito={favoritoUrls.has(p.url)}
            onOpenDetail={onOpenDetail}
            onToggleComparar={onToggleComparar}
            onToggleFavorito={onToggleFavorito}
          />
        ))}
      </div>

      {/* Infinite scroll sentinel */}
      <div ref={loaderRef} style={{ height:1, marginTop:16 }}/>

      {loading && (
        <div style={{
          display:'flex', justifyContent:'center', alignItems:'center',
          gap:10, padding:'1.5rem', color:'var(--t4)', fontSize:'.8rem',
        }}>
          <div style={{
            width:18, height:18, borderRadius:'50%',
            border:'2px solid var(--bd)', borderTopColor:'var(--p)',
            animation:'spin 0.7s linear infinite',
          }}/>
          Cargando más productos...
        </div>
      )}

      {!hasMore && prods.length > 0 && (
        <div style={{ textAlign:'center', padding:'1.5rem 0', fontSize:'.72rem', color:'var(--t4)' }}>
          ✓ {prods.length} productos cargados
        </div>
      )}

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
