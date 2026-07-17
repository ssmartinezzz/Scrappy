import { useEffect, useLayoutEffect, useRef, useState, useCallback } from 'react';
import ProductCard from './ProductCard';
import ProductGridSkeleton from './ProductGridSkeleton';

// Clamp the bottom-only IntersectionObserver margin to viewport height so a
// fast/fling scroll on tablets can't outrun the trigger before the fetch
// resolves (ADR-3). min 600px keeps the old baseline feel on short viewports;
// max ~2400px avoids runaway margins on very tall/desktop screens.
export function computeBottomMargin(innerHeight) {
  const raw = Math.round((innerHeight || 0) * 1.75);
  return Math.min(2400, Math.max(600, raw));
}

// Fill-until-overflow recheck (ADR-4): true when the sentinel is still
// within reach of the viewport + margin, meaning the page isn't tall enough
// yet to naturally push it out of range.
export function sentinelNeedsFill(rect, innerHeight, margin) {
  if (!rect) return false;
  return rect.top <= (innerHeight || 0) + (margin || 0);
}

export default function ProductGrid({
  prods, view, meta, catStats, hasMore, total,
  comparar, favoritos, onOpenDetail, onToggleComparar, onToggleFavorito, onLoadMore,
  onDeleteProducto,
}) {
  const [loading, setLoading] = useState(false);
  const comparaUrls  = new Set(comparar.map(p => p.url));
  const favoritoUrls = new Set((favoritos || []).map(f => f.url));

  // Refs kept fresh so the observer callback + fill-recheck never close over
  // stale props/state (ADR-2).
  const hasMoreRef    = useRef(hasMore);
  const loadingRef    = useRef(loading);
  const onLoadMoreRef = useRef(onLoadMore);
  useEffect(() => { hasMoreRef.current = hasMore; }, [hasMore]);
  useEffect(() => { loadingRef.current = loading; }, [loading]);
  useEffect(() => { onLoadMoreRef.current = onLoadMore; }, [onLoadMore]);

  const observerRef = useRef(null);
  const marginRef    = useRef(computeBottomMargin(typeof window !== 'undefined' ? window.innerHeight : 0));
  const sentinelNodeRef = useRef(null);

  // Single fetch-in-flight guard, independent from the async onLoadMore
  // identity — mirrors the mutex already present in AppLayout's loadingRef.
  const pendingRef = useRef(false);

  const triggerLoadMore = useCallback(() => {
    if (!hasMoreRef.current || loadingRef.current || pendingRef.current) return;
    pendingRef.current = true;
    setLoading(true);
    Promise.resolve(onLoadMoreRef.current?.()).finally(() => {
      setLoading(false);
      pendingRef.current = false;
    });
  }, []);

  // Build the IntersectionObserver ONCE per mount (ADR-2). The callback
  // reads refs, never closure-captured props, so it survives every
  // loading/hasMore/onLoadMore identity change without teardown/rebuild.
  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting) triggerLoadMore();
      },
      { rootMargin: `0px 0px ${marginRef.current}px 0px`, threshold: 0 }
    );
    observerRef.current = observer;
    if (sentinelNodeRef.current) observer.observe(sentinelNodeRef.current);
    return () => observer.disconnect();
  }, [triggerLoadMore]);

  // Recompute the margin on resize (throttled via rAF) without recreating
  // the observer instance — IntersectionObserver doesn't expose a way to
  // mutate rootMargin in place, so we recreate lazily only when the
  // computed margin actually changes.
  useEffect(() => {
    let raf = null;
    function onResize() {
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = null;
        const next = computeBottomMargin(window.innerHeight);
        if (next === marginRef.current) return;
        marginRef.current = next;
        const prevObserver = observerRef.current;
        const node = sentinelNodeRef.current;
        prevObserver?.disconnect();
        const observer = new IntersectionObserver(
          entries => { if (entries[0].isIntersecting) triggerLoadMore(); },
          { rootMargin: `0px 0px ${next}px 0px`, threshold: 0 }
        );
        observerRef.current = observer;
        if (node) observer.observe(node);
      });
    }
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [triggerLoadMore]);

  // Callback-ref: the sentinel unmounts/remounts across the empty↔populated
  // state transition (filter reset), so attach/detach must happen here
  // instead of a plain mount-only effect (ADR-2).
  const sentinelRef = useCallback(node => {
    const observer = observerRef.current;
    if (sentinelNodeRef.current && observer) observer.unobserve(sentinelNodeRef.current);
    sentinelNodeRef.current = node;
    if (node && observer) observer.observe(node);
  }, []);

  // Fill-until-overflow recheck (ADR-4): after an append, if the sentinel is
  // still within trigger range (page too short to push it out), fire
  // another guarded load. Naturally stops once content overflows or
  // hasMore is false.
  useLayoutEffect(() => {
    if (!hasMoreRef.current) return;
    const node = sentinelNodeRef.current;
    if (!node) return;
    const rect = node.getBoundingClientRect();
    if (sentinelNeedsFill(rect, window.innerHeight, marginRef.current)) {
      triggerLoadMore();
    }
  }, [prods.length, triggerLoadMore]);

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
            onDelete={onDeleteProducto ? () => onDeleteProducto(p) : undefined}
          />
        ))}
      </div>

      {/* Infinite scroll sentinel */}
      <div ref={sentinelRef} style={{ height:1, marginTop:16 }}/>

      {loading && hasMore && <ProductGridSkeleton />}

      {!hasMore && prods.length > 0 && (
        <div style={{ textAlign:'center', padding:'1.5rem 0', fontSize:'.72rem', color:'var(--t4)' }}>
          ✓ {prods.length} productos cargados
        </div>
      )}
    </div>
  );
}
