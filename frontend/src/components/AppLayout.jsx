import { useReducer, useEffect, useCallback, useRef, useState, lazy, Suspense } from 'react';
import { useNavigate, NavLink, Outlet, useOutletContext } from 'react-router-dom';
import { fetchData, fetchStatus, fetchFacets, fetchFavoritos } from '../api';
import Topbar        from './Topbar';
import Sidebar       from './Sidebar';
import SearchHero    from './SearchHero';
import ProductGrid   from './ProductGrid';
import DetailPanel   from './DetailPanel';
import RouteFallback from './RouteFallback';
import { CompareBar }   from './CompareComponents';
import { CompareModal } from './CompareComponents';

const TrendsPanel    = lazy(() => import('./TrendsPanel'));
const GruposPanel    = lazy(() => import('./GruposPanel'));
const PicksPanel     = lazy(() => import('./PicksPanel'));
const MarcasPanel    = lazy(() => import('./MarcasPanel'));
const FavoritosPanel = lazy(() => import('./FavoritosPanel'));
const OutfitsPanel   = lazy(() => import('./OutfitsPanel'));

// ─── State ───────────────────────────────────────────────────────────────────
const PAGE_SIZE = 48;

const init = {
  view:         'grid',
  // Filters
  busq:         '',
  sitioFiltro:  '',
  rubroFiltro:  '',
  marca:        '',
  badge:        '',
  segment:      '',
  genero:       '',
  categorias:   [],
  talles:       [],
  gymrat:       false,
  gymSubcats:   {},        // { [subcatLabel]: count } — derived from gymrat products
  gymSubcatFiltro: null,   // active gymrat sub-category filter (client-side)
  orden:        'precio_asc',
  // Pagination / infinite scroll
  pag:          1,
  hasMore:      true,
  // Data
  prods:        [],
  totalProds:   0,
  meta:         {},
  facets:       {},    // from /api/facets — badges, generos, categorias, marcas
  catStats:     {},
  // UI
  detailProd:   null,
  comparar:     [],
  compareOpen:  false,
  favoritos:    [],   // array of favorito objects {url, sitio, nombre, addedAt, lastCheckedAt, descontinuado}
  // Scraping
  scrapeStatus: 'IDLE',
  scrapeMsg:    '',
  progreso:     null,
  config:       { precioMin: 0, precioMax: 300000 },
};

function reducer(state, action) {
  switch (action.type) {
    case 'SET':          return { ...state, ...action.payload };
    // SET_FILTER resets page and clears the accumulated prods list
    case 'SET_FILTER':   return { ...state, ...action.payload, pag: 1, prods: [], hasMore: true };
    case 'RESET_FILTERS': return {
      ...state,
      busq:'', sitioFiltro:'', rubroFiltro:'', marca:'', badge:'',
      segment:'', genero:'', categorias:[], talles:[], gymrat:false,
      gymSubcats:{}, gymSubcatFiltro:null,
      pag:1, prods:[], hasMore:true,
    };
    case 'TOGGLE_TALLE': {
      const t = state.talles;
      return { ...state, talles: t.includes(action.v)?t.filter(x=>x!==action.v):[...t,action.v], pag:1, prods:[], hasMore:true };
    }
    case 'TOGGLE_CAT': {
      const c = state.categorias;
      return { ...state, categorias: c.includes(action.v)?c.filter(x=>x!==action.v):[...c,action.v], pag:1, prods:[], hasMore:true };
    }
    case 'TOGGLE_COMPARAR': {
      const exists = state.comparar.find(p => p.url === action.prod.url);
      if (exists) return { ...state, comparar: state.comparar.filter(p=>p.url!==action.prod.url) };
      if (state.comparar.length >= 4) return state;
      return { ...state, comparar: [...state.comparar, action.prod] };
    }
    case 'TOGGLE_FAVORITO': {
      const exists = state.favoritos.find(f => f.url === action.prod.url);
      if (exists) return { ...state, favoritos: state.favoritos.filter(f=>f.url!==action.prod.url) };
      return { ...state, favoritos: [...state.favoritos, {
        url: action.prod.url, sitio: action.prod.sitio, nombre: action.prod.nombre,
        descontinuado: false,
      }] };
    }
    case 'SET_FAVORITOS': return { ...state, favoritos: action.payload || [] };
    case 'APPEND_PRODS': {  // Infinite scroll: append, cap at 300
      const MAX_PRODS = 300;
      const newProds  = [...state.prods, ...(action.payload.prods||[])];
      const capped    = newProds.length > MAX_PRODS ? newProds.slice(-MAX_PRODS) : newProds;
      const total     = action.payload.total || state.totalProds;

      // Derive gym subcategory counts from all accumulated gymrat products
      const gymSubcats = {};
      capped.filter(p => p.gymrat).forEach(p => {
        const sub = (p.categoria ? p.categoria : 'Ropa') + ' Gym';
        gymSubcats[sub] = (gymSubcats[sub] || 0) + 1;
      });

      return {
        ...state,
        prods:      capped,
        totalProds: total,
        meta:       action.payload.meta  || state.meta,
        gymSubcats,
        pag:        state.pag + 1,
        hasMore:    newProds.length < total,
      };
    }
    case 'OPEN_DETAIL':   return { ...state, detailProd: action.prod };
    case 'CLOSE_DETAIL':  return { ...state, detailProd: null };
    default: return state;
  }
}

// ─── CatalogoRoute (eager) ─────────────────────────────────────────────────────
function CatalogoRoute() {
  const { S, set, setFilter, dispatch, loadNextPage } = useOutletContext();

  // Client-side gym subcat filter (ADR-3): filter after fetch when subcat is active
  const visibleProds = S.gymSubcatFiltro
    ? S.prods.filter(p => {
        if (!p.gymrat) return false;
        const sub = (p.categoria ? p.categoria : 'Ropa') + ' Gym';
        return sub === S.gymSubcatFiltro;
      })
    : S.prods;

  return (
    <>
      <SearchHero
        busq={S.busq} view={S.view} orden={S.orden} total={S.totalProds}
        topMarcas={Object.entries(S.facets?.marcas||{}).sort((a,b)=>b[1]-a[1])}
        marca={S.marca}
        onBusq={v => setFilter({ busq:v })}
        onView={v => set({ view:v })}
        onOrden={v => setFilter({ orden:v })}
        onMarca={v => setFilter({ marca:v })}
      />
      <ProductGrid
        prods={visibleProds}
        view={S.view}
        meta={S.meta}
        catStats={S.catStats}
        hasMore={S.gymSubcatFiltro ? false : S.hasMore}
        total={S.gymSubcatFiltro ? visibleProds.length : S.totalProds}
        comparar={S.comparar}
        favoritos={S.favoritos}
        onOpenDetail={prod => dispatch({ type:'OPEN_DETAIL', prod })}
        onToggleComparar={prod => dispatch({ type:'TOGGLE_COMPARAR', prod })}
        onToggleFavorito={prod => dispatch({ type:'TOGGLE_FAVORITO', prod })}
        onLoadMore={loadNextPage}
      />
    </>
  );
}

// ─── Lazy route wrappers (consume outlet context) ──────────────────────────────
function PicksRoute() {
  const { dispatch } = useOutletContext();
  return <PicksPanel onProductClick={prod => dispatch({ type:'OPEN_DETAIL', prod })}/>;
}

function MarcasRoute() {
  const { dispatch } = useOutletContext();
  return <MarcasPanel onProductClick={prod => dispatch({ type:'OPEN_DETAIL', prod })}/>;
}

function GruposRoute() {
  const { dispatch } = useOutletContext();
  return <GruposPanel onOpenDetail={prod => dispatch({ type:'OPEN_DETAIL', prod })}/>;
}

function TrendsRoute() {
  const { S, dispatch, onClusterClick } = useOutletContext();
  return (
    <TrendsPanel
      catStats={S.catStats}
      onClusterClick={onClusterClick}
      onProductClick={prod => dispatch({ type:'OPEN_DETAIL', prod })}
    />
  );
}

function FavoritosRoute() {
  const { S, dispatch, startPolling, loadFavoritos, set } = useOutletContext();
  return (
    <FavoritosPanel
      favoritos={S.favoritos}
      scrapeStatus={S.scrapeStatus}
      onOpenDetail={prod => dispatch({ type:'OPEN_DETAIL', prod })}
      onStartPolling={startPolling}
      onRefreshFavoritos={loadFavoritos}
      onSetScraping={() => set({ scrapeStatus:'RUNNING' })}
    />
  );
}

function OutfitsRoute() {
  return <OutfitsPanel/>;
}

export {
  CatalogoRoute as CatalogoPanelRoute, PicksRoute as PicksPanelRoute,
  MarcasRoute as MarcasPanelRoute, GruposRoute as GruposPanelRoute,
  TrendsRoute as TrendsPanelRoute, FavoritosRoute as FavoritosPanelRoute,
  OutfitsRoute as OutfitsPanelRoute,
};

// ─── AppLayout ───────────────────────────────────────────────────────────────
export default function AppLayout() {
  const [S, dispatch] = useReducer(reducer, init);
  const set      = payload => dispatch({ type:'SET', payload });
  const setFilter = payload => dispatch({ type:'SET_FILTER', payload });
  const pollingRef = useRef(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const loadingRef = useRef(false);
  const navigate = useNavigate();

  // On mount: check if we already have data → load facets/favoritos/first page
  useEffect(() => {
    fetchStatus().then(st => {
      if (st?.tieneData) {
        set({ scrapeStatus:st.status, scrapeMsg:st.mensaje });
        loadFirstPage();
        loadFacets();
        loadFavoritos();
      }
    });
  }, []);

  // Load facets once on mount
  const loadFacets = useCallback(async () => {
    const f = await fetchFacets();
    if (f) set({ facets: f });
  }, []);

  // Load favoritos list (used for heart state on cards + Favoritos tab)
  const loadFavoritos = useCallback(async () => {
    const f = await fetchFavoritos();
    dispatch({ type:'SET_FAVORITOS', payload: f || [] });
  }, []);

  // Load first page whenever filters change (prods already reset by SET_FILTER)
  useEffect(() => {
    if (S.pag !== 1) return; // only on reset
    loadFirstPage();
  }, [S.busq, S.sitioFiltro, S.rubroFiltro, S.marca, S.badge,
      S.segment, S.genero, S.categorias, S.talles, S.gymrat, S.orden]);

  const buildParams = useCallback((page) => ({
    page, size: PAGE_SIZE, orden: S.orden,
    ...(S.busq        && { q:          S.busq }),
    ...(S.sitioFiltro && { sitio:      S.sitioFiltro }),
    ...(S.rubroFiltro && { rubro:      S.rubroFiltro }),
    ...(S.marca       && { marca:      S.marca }),
    ...(S.badge       && { badge:      S.badge }),
    ...(S.segment     && { segment:    S.segment }),
    ...(S.genero      && { genero:     S.genero }),
    ...(S.categorias.length && { categorias: S.categorias }),
    ...(S.talles.length     && { talle:      S.talles }),
    ...(S.gymrat      && { gymrat:     true }),
  }), [S.busq, S.sitioFiltro, S.rubroFiltro, S.marca, S.badge, S.segment,
       S.genero, S.categorias, S.talles, S.gymrat, S.orden]);

  const loadFirstPage = useCallback(async () => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    const data = await fetchData(buildParams(1));
    loadingRef.current = false;
    if (data) {
      dispatch({ type:'APPEND_PRODS', payload:{
        prods: data.productos||[], total: data.meta?.total||0, meta: data.meta||{}
      }});
    }
  }, [buildParams]);

  const loadNextPage = useCallback(async () => {
    if (loadingRef.current || !S.hasMore) return;
    loadingRef.current = true;
    const data = await fetchData(buildParams(S.pag));
    loadingRef.current = false;
    if (data) {
      dispatch({ type:'APPEND_PRODS', payload:{
        prods: data.productos||[], total: data.meta?.total||0, meta: data.meta||{}
      }});
    }
  }, [buildParams, S.pag, S.hasMore]);

  // Polling during scraping
  const startPolling = useCallback((onDone) => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    pollingRef.current = setInterval(async () => {
      const st = await fetchStatus();
      if (!st) return;
      set({ scrapeStatus:st.status, scrapeMsg:st.mensaje, progreso:st.progreso });
      if (st.status === 'RUNNING' && st.tieneData) loadFirstPage();
      if (st.status === 'DONE' || st.status === 'ERROR') {
        clearInterval(pollingRef.current); pollingRef.current = null;
        loadFirstPage(); loadFacets(); onDone?.();
      }
    }, 1800);
  }, [loadFirstPage, loadFacets]);

  // Cross-navigation: TrendsPanel cluster click → filter + switch to catalog (ADR 6)
  const onClusterClick = useCallback(label => {
    setFilter({ busq: label });   // reducer: resets page, clears prods
    navigate('/catalogo');        // router: switch route
  }, [navigate]);

  // Topbar re-scrape → navigate to /splash + reset scrape status
  const onReScrape = useCallback(() => {
    set({ scrapeStatus:'IDLE' });
    navigate('/splash');
  }, [navigate]);

  return (
    <div style={{ display:'flex', flexDirection:'column', height:'100vh' }}>
      <Topbar
        meta={S.meta}
        facets={S.facets}
        sitioFiltro={S.sitioFiltro}
        rubroFiltro={S.rubroFiltro}
        onSitioChange={v => setFilter({ sitioFiltro: v })}
        onRubroChange={v => setFilter({ rubroFiltro:v, sitioFiltro:'' })}
        onReScrape={onReScrape}
        gymrat={S.gymrat}
        onGymratToggle={() => setFilter({ gymrat: !S.gymrat })}
      />
      <div className="layout">
        <Sidebar
          open={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
          facets={S.facets}
          meta={S.meta}
          filters={{ busq:S.busq, marca:S.marca, badge:S.badge, segment:S.segment,
                     genero:S.genero, categorias:S.categorias, talles:S.talles,
                     gymrat:S.gymrat, gymSubcats:S.gymSubcats, gymSubcatFiltro:S.gymSubcatFiltro }}
          onFilter={payload => {
            // gymSubcatFiltro is client-side only — do not reset pagination
            if ('gymSubcatFiltro' in payload) { set(payload); }
            else { setFilter(payload); }
          }}
          onToggleCat={v => dispatch({ type:'TOGGLE_CAT', v })}
          onToggleTalle={v => dispatch({ type:'TOGGLE_TALLE', v })}
          onReset={() => dispatch({ type:'RESET_FILTERS' })}
        />
        <div className="content">
          <div className="tab-bar">
            <button className="sidebar-toggle" onClick={() => setSidebarOpen(o=>!o)}>☰</button>
            <NavLink to="/catalogo"  className={({isActive}) => `tab ${isActive?'active':''}`}>🛍</NavLink>
            <NavLink to="/picks"     className={({isActive}) => `tab ${isActive?'active':''}`}>🏆</NavLink>
            <NavLink to="/marcas"    className={({isActive}) => `tab ${isActive?'active':''}`}>🏷</NavLink>
            <NavLink to="/grupos"    className={({isActive}) => `tab ${isActive?'active':''}`}>⚖</NavLink>
            <NavLink to="/tendencias" className={({isActive}) => `tab ${isActive?'active':''}`}>📈</NavLink>
            <NavLink to="/favoritos" className={({isActive}) => `tab ${isActive?'active':''}`}>⭐</NavLink>
            <NavLink to="/outfits"   className={({isActive}) => `tab ${isActive?'active':''}`}>👕 Outfits</NavLink>
          </div>

          <Suspense fallback={<RouteFallback/>}>
            <Outlet context={{
              S, set, setFilter, dispatch, loadNextPage, startPolling,
              loadFavoritos, sidebarOpen, setSidebarOpen, onClusterClick,
            }}/>
          </Suspense>
        </div>
      </div>

      {S.detailProd && (
        <DetailPanel product={S.detailProd} catStats={S.catStats}
                     onClose={() => dispatch({ type:'CLOSE_DETAIL' })}/>
      )}
      {S.comparar.length > 0 && (
        <CompareBar items={S.comparar}
          onRemove={prod => dispatch({ type:'TOGGLE_COMPARAR', prod })}
          onClear={() => set({ comparar:[] })}
          onCompare={() => set({ compareOpen:true })}/>
      )}
      {S.compareOpen && (
        <CompareModal items={S.comparar} onClose={() => set({ compareOpen:false })}/>
      )}
    </div>
  );
}
