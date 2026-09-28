import { useCallback, useReducer } from 'react';
import { fireEvent, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ProductGrid from '@/components/ProductGrid';
import ProductCardMemo from '@/components/ProductCard';

// addFavorito/removeFavorito hit the network from ProductCard's
// handleFavoritoClick — stub them, keep everything else (fmt, BADGE_LABELS)
// real, same as the rest of the ProductCard suite.
vi.mock('@/api', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, addFavorito: vi.fn(), removeFavorito: vi.fn() };
});

// Stable references for props ProductGrid threads straight through to every
// ProductCard unchanged (ProductGrid.jsx:159-161). A fresh `{}`/`[]` literal
// in the harness's JSX would itself be a new prop identity on every render —
// a test-harness artifact, not part of the defect under test — and would
// fail every card, not just the unrelated ones.
const EMPTY_CAT_STATS = {};
const EMPTY_META = {};
const EMPTY_COMPARAR = [];

function buildProducts(n) {
  return Array.from({ length: n }, (_, i) => ({
    url: `https://tienda.test/p/${i}`,
    nombre: `Producto ${i}`,
    sitio: 'Freres',
    precio: 10000 + i,
    precioOrig: null,
    categoria: 'Remera',
    talles: [],
    cantidadUnidades: 1,
  }));
}

// Minimal stand-in for AppLayout's reducer — only the one action this test
// needs. The point under test is prop identity, not this reducer's shape.
function harnessReducer(state, action) {
  if (action.type !== 'TOGGLE_FAVORITO') return state;
  const exists = state.favoritos.some(f => f.url === action.prod.url);
  return {
    ...state,
    favoritos: exists
      ? state.favoritos.filter(f => f.url !== action.prod.url)
      : [...state.favoritos, { url: action.prod.url }],
  };
}

// Mirrors AppLayout's CatalogoRoute AFTER the T2 fix: dispatch is stable
// (useReducer), so useCallback here is exactly what makes onOpenDetail /
// onToggleComparar / onToggleFavorito / onDeleteProducto stable references
// (AppLayout.jsx:277-279 handleOpenDetail/handleToggleComparar/
// handleToggleFavorito/handleDelete). What this test exercises unmodified is
// the real ProductGrid component — including ProductGrid.jsx:165's per-card
// onDelete wiring, which is the part of the defect that lives inside
// ProductGrid itself, independent of how stable the caller's handlers are.
function CatalogHarness({ prods }) {
  const [state, dispatch] = useReducer(harnessReducer, { favoritos: [] });
  const onOpenDetail      = useCallback(prod => dispatch({ type: 'OPEN_DETAIL', prod }), [dispatch]);
  const onToggleComparar  = useCallback(prod => dispatch({ type: 'TOGGLE_COMPARAR', prod }), [dispatch]);
  const onToggleFavorito  = useCallback(prod => dispatch({ type: 'TOGGLE_FAVORITO', prod }), [dispatch]);
  const onDeleteProducto  = useCallback(() => {}, []);
  return (
    <ProductGrid
      prods={prods}
      view="grid"
      meta={EMPTY_META}
      catStats={EMPTY_CAT_STATS}
      hasMore={false}
      total={prods.length}
      comparar={EMPTY_COMPARAR}
      favoritos={state.favoritos}
      onOpenDetail={onOpenDetail}
      onToggleComparar={onToggleComparar}
      onToggleFavorito={onToggleFavorito}
      onLoadMore={() => {}}
      onDeleteProducto={onDeleteProducto}
    />
  );
}

describe('ProductGrid — ProductCard memo containment', () => {
  let renderCounts;
  let originalType;

  // React.memo(Component) returns { type: Component, compare, $$typeof }.
  // Swapping .type for a counting wrapper (keeping the same memo object, so
  // the same default shallow-compare still runs) counts actual renders of
  // the memoized component without touching production code.
  beforeEach(() => {
    renderCounts = {};
    originalType = ProductCardMemo.type;
    ProductCardMemo.type = function CountedProductCard(props) {
      renderCounts[props.product.url] = (renderCounts[props.product.url] || 0) + 1;
      return originalType(props);
    };
  });

  afterEach(() => {
    ProductCardMemo.type = originalType;
  });

  it('re-renders only the card whose favorito changed, not the other N-1 cards', () => {
    const prods = buildProducts(5);
    const { container } = render(<CatalogHarness prods={prods} />);

    Object.values(renderCounts).forEach(count => expect(count).toBe(1));

    const target = prods[2];
    const cards = container.querySelectorAll('.card');
    fireEvent.click(cards[2].querySelector('.card-fav-btn'));

    expect(renderCounts[target.url]).toBe(2);
    prods
      .filter(p => p.url !== target.url)
      .forEach(p => expect(renderCounts[p.url]).toBe(1));
  });
});
