import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { fetchMejores } from '../api';
import { canonicalFromSlug } from '../lib/cat';
import CategoryPicksView from './CategoryPicksView';

// Routed page for /picks/:categoria — deep-linkable, bookmarkable, shareable.
// Fetches /api/mejores independently on mount (design doc ADR-1: PicksPanel
// owns its own fetch in local state, there is no lifted state to share, and
// this route must be able to mount cold with PicksPanel unmounted).
// rubro='' always returns ALL categories, so any slug resolves regardless of
// which rubro tab the panel last showed.
export default function CategoryPicksPage({ onProductClick }) {
  const { categoria: slug } = useParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState('loading'); // loading | found | not-found
  const [cat, setCat] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    fetchMejores('').then(data => {
      if (cancelled) return;
      const cats = Array.isArray(data) ? data : [];
      const match = canonicalFromSlug(slug, cats);
      if (match) {
        setCat(match);
        setStatus('found');
      } else {
        setStatus('not-found');
      }
    });
    return () => { cancelled = true; };
  }, [slug]);

  const goBack = () => navigate('/picks');

  if (status === 'loading') {
    return (
      <div className="picks-scroll">
        <div className="picks-state-msg">Cargando picks...</div>
      </div>
    );
  }

  if (status === 'not-found') {
    return (
      <div className="picks-scroll">
        <div className="picks-catdetail">
          <button onClick={goBack} className="picks-back-btn">← Volver</button>
          <div className="picks-state-msg">
            No encontramos esta categoría. Puede que ya no exista o que el enlace esté desactualizado.
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="picks-scroll">
      <CategoryPicksView cat={cat} onBack={goBack} onProductClick={onProductClick}/>
    </div>
  );
}
