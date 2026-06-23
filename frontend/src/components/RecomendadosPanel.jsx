import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchRecomendados, sendRecomendadosFeedback, dismissCategoria, fmt } from '../api';
import ProductCard from './ProductCard';

const PAGE_SIZE = 24;

// Per-card like/dislike + "no me interesa esta categoría" row — mirrors
// OutfitsPanel's outfit-fb-btn styling (like/dislike pair), plus an extra
// category-wide dismiss action specific to this feed (design.md Decision 1).
function FeedbackRow({ product: p, sent, onFeedback, onDismissCategoria }) {
  if (sent) {
    return <div className="recomendados-fb-sent">Guardado</div>;
  }
  return (
    <div className="recomendados-fb-row">
      <div style={{ display: 'flex', gap: 6 }}>
        <button className="outfit-fb-btn like" onClick={() => onFeedback(p.url, true)}>
          Me gusta
        </button>
        <button className="outfit-fb-btn dislike" onClick={() => onFeedback(p.url, false)}>
          No me gusta
        </button>
      </div>
      {p.categoria && (
        <button className="recomendados-dismiss-cat-btn"
                onClick={() => onDismissCategoria(p.categoria)}
                title={`No me interesa la categoría ${p.categoria}`}>
          No me interesa "{p.categoria}"
        </button>
      )}
    </div>
  );
}

export default function RecomendadosPanel() {
  const [genero, setGenero] = useState('');
  const [prods, setProds] = useState([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [sentUrls, setSentUrls] = useState(() => new Set());
  const [dismissedCats, setDismissedCats] = useState(() => new Set());
  const loaderRef = useRef(null);

  const load = useCallback(async (targetPage, replace) => {
    setLoading(true);
    setError(false);
    try {
      const data = await fetchRecomendados(targetPage, PAGE_SIZE, { genero });
      if (data === null) {
        if (replace) { setProds([]); setTotal(0); setHasMore(false); }
        setError(replace);
        return;
      }
      setTotal(data.total || 0);
      setProds(prev => replace ? (data.items || []) : [...prev, ...(data.items || [])]);
      const loadedCount = (replace ? 0 : prods.length) + (data.items?.length || 0);
      setHasMore(loadedCount < (data.total || 0));
    } catch {
      if (replace) { setProds([]); setTotal(0); setHasMore(false); }
      setError(replace);
    } finally {
      setLoading(false);
    }
  }, [genero, prods.length]);

  useEffect(() => {
    setPage(1);
    setSentUrls(new Set());
    load(1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [genero]);

  useEffect(() => {
    if (!loaderRef.current) return;
    let pending = false;
    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting && hasMore && !loading && !pending) {
          pending = true;
          const nextPage = page + 1;
          setPage(nextPage);
          load(nextPage, false).finally(() => { pending = false; });
        }
      },
      { rootMargin: '600px', threshold: 0 }
    );
    observer.observe(loaderRef.current);
    return () => observer.disconnect();
  }, [hasMore, loading, page, load]);

  async function handleFeedback(url, liked) {
    const ok = await sendRecomendadosFeedback(genero, [{ url, liked }]);
    if (ok) setSentUrls(prev => new Set(prev).add(url));
  }

  async function handleDismissCategoria(categoria) {
    const ok = await dismissCategoria(categoria);
    if (ok) {
      setDismissedCats(prev => new Set(prev).add(categoria));
      setProds(prev => prev.filter(p => p.categoria !== categoria));
    }
  }

  const visibleProds = prods.filter(p => !dismissedCats.has(p.categoria));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{
        display: 'flex', gap: 8, alignItems: 'center', padding: '1rem 1.25rem .5rem',
      }}>
        <span style={{ fontSize: '.72rem', color: 'var(--t4)', fontWeight: 600 }}>Género:</span>
        {['', 'hombre', 'mujer', 'unisex'].map(g => (
          <button key={g || 'todos'} onClick={() => setGenero(g)}
            className={`genero-pill ${genero === g ? 'active' : ''}`}>
            {g || 'todos'}
          </button>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 1.25rem 1.25rem' }}>
        {loading && visibleProds.length === 0 && (
          <div style={{ color: 'var(--t4)', textAlign: 'center', padding: '3rem', fontSize: '.85rem' }}>
            Armando tus recomendaciones...
          </div>
        )}

        {!loading && error && (
          <div style={{ color: 'var(--t4)', textAlign: 'center', padding: '3rem', fontSize: '.85rem' }}>
            No hay catálogo cargado todavía. Ejecutá un scraping para ver recomendaciones.
          </div>
        )}

        {!error && visibleProds.length === 0 && !loading && (
          <div style={{ color: 'var(--t4)', textAlign: 'center', padding: '3rem', fontSize: '.85rem' }}>
            No encontramos productos para mostrarte con este filtro.
          </div>
        )}

        {visibleProds.length > 0 && (
          <>
            <div style={{ fontSize: '.7rem', color: 'var(--t4)', margin: '0 0 10px 2px' }}>
              Mostrando {visibleProds.length} de {total} productos
            </div>
            <div className="grid">
              {visibleProds.map(p => (
                <div key={p.url} className="recomendados-card-wrap">
                  <ProductCard
                    product={p}
                    catStats={{}}
                    isInComparar={false}
                    isFavorito={false}
                    onOpenDetail={() => {}}
                    onToggleComparar={() => {}}
                    onToggleFavorito={() => {}}
                  />
                  <FeedbackRow
                    product={p}
                    sent={sentUrls.has(p.url)}
                    onFeedback={handleFeedback}
                    onDismissCategoria={handleDismissCategoria}
                  />
                </div>
              ))}
            </div>

            <div ref={loaderRef} style={{ height: 1, marginTop: 16 }} />

            {loading && (
              <div style={{
                display: 'flex', justifyContent: 'center', alignItems: 'center',
                gap: 10, padding: '1.5rem', color: 'var(--t4)', fontSize: '.8rem',
              }}>
                Cargando más recomendaciones...
              </div>
            )}

            {!hasMore && visibleProds.length > 0 && (
              <div style={{ textAlign: 'center', padding: '1.5rem 0', fontSize: '.72rem', color: 'var(--t4)' }}>
                ✓ {visibleProds.length} productos cargados
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
