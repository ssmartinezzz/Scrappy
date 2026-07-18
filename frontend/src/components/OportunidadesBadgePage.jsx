import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { fetchData } from '../api';
import { BADGE_META } from '../lib/colors';
import ProductGrid from './ProductGrid';

const PAGE_SIZE = 48;

// Routed page for /analisis/oportunidades/:badge — deep-linkable, bookmarkable,
// shareable full paginated listing of every product holding a given badge
// (spec "Drill-Down Full Paginated Listing" / "Badge drill-down deep link").
// Mirrors CategoryPicksPage's ADR-1 pattern (owns its own fetch/local state,
// mounts cold, no lifted AppLayout state) but paginates via /api/data?badge=
// (like the main catalog) instead of the capped /api/mejores preview.
export default function OportunidadesBadgePage({ onProductClick }) {
  const { badge } = useParams();
  const navigate = useNavigate();
  const [prods, setProds] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const loadingRef = useRef(false);

  const meta = BADGE_META[badge];

  const loadPage = useCallback(async (p, replace) => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    const data = await fetchData({ badge, page: p, size: PAGE_SIZE });
    loadingRef.current = false;
    if (!data) return;
    const nuevos = data.productos || [];
    const totalNuevo = data.meta?.total || 0;
    setProds(prev => {
      const base = replace ? [] : prev;
      const seen = new Set(base.map(x => x.url));
      const merged = [...base, ...nuevos.filter(x => !seen.has(x.url))];
      // hasMore derived from the merged length computed HERE (server total,
      // not the stale closure over `prods` from when loadPage was created) —
      // avoids the infinite-scroll desync class of bug on large catalogs.
      setHasMore(merged.length < totalNuevo);
      return merged;
    });
    setTotal(totalNuevo);
    setPage(p + 1);
  }, [badge]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    setProds([]);
    setPage(1);
    setHasMore(true);
    loadPage(1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [badge]);

  const goBack = () => navigate('/analisis/oportunidades');

  return (
    <div className="picks-scroll">
      <div className="picks-catdetail">
        <button onClick={goBack} className="picks-back-btn">← Volver</button>
        <div style={{ display:'flex', alignItems:'center', gap:10, margin:'0 0 1rem' }}>
          <h2 style={{ margin:0, fontSize:'1.1rem' }}>{meta ? meta.label : badge}</h2>
          {total > 0 && (
            <span style={{ fontSize:'.75rem', color:'var(--t4)' }}>{total} productos</span>
          )}
        </div>
        <ProductGrid
          prods={prods}
          view="grid"
          meta={{}}
          catStats={{}}
          hasMore={hasMore}
          total={total}
          comparar={[]}
          favoritos={[]}
          onOpenDetail={onProductClick}
          onToggleComparar={() => {}}
          onToggleFavorito={() => {}}
          onLoadMore={() => loadPage(page, false)}
        />
      </div>
    </div>
  );
}
