import { useReducer, useEffect, useLayoutEffect, useCallback, useRef, useState, lazy, Suspense } from 'react';
import { useNavigate, NavLink, Outlet, useOutletContext } from 'react-router-dom';
import { fetchData, fetchStatus, fetchFacets, fetchFavoritos, addFavorito, removeFavorito, deleteProducto,
         fetchMlEstado, fetchMlResultado, startMlTraining, renormalizarCatalogo,
         fetchSavedOutfits, saveOutfit, deleteSavedOutfit, renameOutfit } from '../api';
import { sortByCountDesc } from '../lib/utils';
import Topbar        from './Topbar';
import Sidebar       from './Sidebar';
import SearchHero    from './SearchHero';
import ProductGrid   from './ProductGrid';
import DetailPanel   from './DetailPanel';
import RouteFallback from './RouteFallback';
import GpuTrainingOverlay from './GpuTrainingOverlay';
import { CompareBar }   from './CompareComponents';
import { CompareModal } from './CompareComponents';

const TrendsPanel    = lazy(() => import('./TrendsPanel'));
const GruposPanel    = lazy(() => import('./GruposPanel'));
const PicksPanel     = lazy(() => import('./PicksPanel'));
const MarcasPanel    = lazy(() => import('./MarcasPanel'));
const FavoritosPanel = lazy(() => import('./FavoritosPanel'));
const OutfitsPanel   = lazy(() => import('./OutfitsPanel'));
const FinanPanel     = lazy(() => import('./FinanPanel'));
const RecomendadosPanel = lazy(() => import('./RecomendadosPanel'));
const SuplementosPanel  = lazy(() => import('./SuplementosPanel'));

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
  pack:         false,     // Packs/combos filter — mirrors gymrat (boolean toggle)
  subCategoria: [],        // activity/sport sub-dimension multi-select (server-side OR filter)
  precioMin:    undefined, // Sidebar price-range filter (server-side, /api/data param)
  precioMax:    undefined,
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
  savedOutfits: [],   // array of saved outfit objects {id, nombre, slots, suplementos, totalEstimado, createdAt}
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
      gymSubcats:{}, gymSubcatFiltro:null, pack:false, subCategoria:[],
      precioMin:undefined, precioMax:undefined,
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
    case 'TOGGLE_SUBCAT': {
      const sc = state.subCategoria;
      return { ...state, subCategoria: sc.includes(action.v)?sc.filter(x=>x!==action.v):[...sc,action.v], pag:1, prods:[], hasMore:true };
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
        img: action.prod.img, precio: action.prod.precio,
        descontinuado: false,
      }] };
    }
    case 'SET_FAVORITOS':      return { ...state, favoritos: action.payload || [] };
    case 'SET_SAVED_OUTFITS':  return { ...state, savedOutfits: action.payload || [] };
    case 'ADD_SAVED_OUTFIT':
      return { ...state, savedOutfits: [action.payload, ...state.savedOutfits] };
    case 'REMOVE_SAVED_OUTFIT':
      return { ...state, savedOutfits: state.savedOutfits.filter(o => o.id !== action.id) };
    case 'RENAME_SAVED_OUTFIT':
      return {
        ...state,
        savedOutfits: state.savedOutfits.map(o =>
          o.id === action.id ? { ...o, nombre: action.nombre } : o),
      };
    case 'ADD_FAVORITO':
      return {
        ...state,
        favoritos: state.favoritos.some(f => f.url === action.payload.url)
          ? state.favoritos
          : [...state.favoritos, action.payload],
      };
    case 'REMOVE_PROD': {
      return {
        ...state,
        prods: state.prods.filter(p => p.url !== action.url),
        totalProds: Math.max(0, state.totalProds - 1),
      };
    }
    case 'APPEND_PRODS': {  // Infinite scroll: append, cap at 300
      const MAX_PRODS = 300;
      // Dedup por url: la página actual puede solaparse con lo ya acumulado
      // (p.ej. dos efectos de montaje disparando loadFirstPage casi a la vez,
      // o un re-fetch de la misma página) — sin esto, React renderiza keys
      // duplicadas y queda en un estado de reconciliación indefinido.
      const seenUrls  = new Set(state.prods.map(p => p.url));
      const incoming  = (action.payload.prods || []).filter(p => !seenUrls.has(p.url));
      const newProds  = [...state.prods, ...incoming];
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
  const { S, set, setFilter, dispatch, loadNextPage, gpuTraining, triggerGpuTraining } = useOutletContext();

  // Client-side gym subcat filter (ADR-3): filter after fetch when subcat is active
  const visibleProds = S.gymSubcatFiltro
    ? S.prods.filter(p => {
        if (!p.gymrat) return false;
        const sub = (p.categoria ? p.categoria : 'Ropa') + ' Gym';
        return sub === S.gymSubcatFiltro;
      })
    : S.prods;

  async function handleDelete(prod) {
    const ok = await deleteProducto(prod.url);
    if (ok) dispatch({ type: 'REMOVE_PROD', url: prod.url });
  }

  const gpuRunning = !!gpuTraining?.running;

  return (
    <>
      <div className="gpu-fab">
        <button
          onClick={triggerGpuTraining}
          disabled={gpuRunning}
          style={{
            padding:'12px 20px', borderRadius:999,
            border:'1px solid var(--p)',
            background: gpuRunning ? 'var(--s3)' : 'var(--p)',
            color: gpuRunning ? 'var(--t3)' : '#fff',
            fontWeight:800, fontSize:'.82rem',
            cursor: gpuRunning ? 'not-allowed' : 'pointer',
            boxShadow:'0 4px 18px color-mix(in srgb, var(--p) 35%, transparent)',
            animation: gpuRunning ? 'none' : 'gpu-btn-pulse 2.2s ease-in-out infinite',
            transition:'opacity .15s',
          }}
        >
          {gpuRunning ? 'Entrenando...' : '⚡ Re-entrenar IA con GPU'}
        </button>
      </div>{/* .gpu-fab */}

      <SearchHero
        busq={S.busq} view={S.view} orden={S.orden} total={S.totalProds}
        topMarcas={sortByCountDesc(S.facets?.marcas||{})}
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
        onDeleteProducto={handleDelete}
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
      savedOutfits={S.savedOutfits || []}
      scrapeStatus={S.scrapeStatus}
      onOpenDetail={prod => dispatch({ type:'OPEN_DETAIL', prod })}
      onStartPolling={startPolling}
      onRefreshFavoritos={loadFavoritos}
      onSetScraping={() => set({ scrapeStatus:'RUNNING' })}
      onDeleteFavorito={async (url) => {
        await removeFavorito(url);
        dispatch({ type: 'TOGGLE_FAVORITO', prod: { url } });
      }}
      onDeleteSavedOutfit={async (id) => {
        await deleteSavedOutfit(id);
        dispatch({ type: 'REMOVE_SAVED_OUTFIT', id });
      }}
      onRenameSavedOutfit={async (id, nombre) => {
        await renameOutfit(id, nombre);
        dispatch({ type: 'RENAME_SAVED_OUTFIT', id, nombre });
      }}
    />
  );
}

function OutfitsRoute() {
  const { S, dispatch } = useOutletContext();
  return (
    <OutfitsPanel
      favoritos={S.favoritos || []}
      savedOutfits={S.savedOutfits || []}
      onAddFavorito={(item) => {
        addFavorito(item);
        dispatch({ type: 'ADD_FAVORITO', payload: item });
      }}
      onSaveOutfit={async (payload) => {
        const result = await saveOutfit(payload);
        if (result?.ok) {
          dispatch({ type: 'ADD_SAVED_OUTFIT', payload: {
            id: result.id,
            nombre: result.nombre,
            totalEstimado: result.totalEstimado,
            slots: payload.slots || [],
            suplementos: payload.suplementos || [],
            createdAt: new Date().toISOString().replace('T', ' ').slice(0, 19),
          }});
        }
      }}
    />
  );
}

function FinanRoute() {
  return <FinanPanel/>;
}

function RecomendadosRoute() {
  return <RecomendadosPanel/>;
}

function SuplementosRoute() {
  return <SuplementosPanel/>;
}

export {
  CatalogoRoute as CatalogoPanelRoute, PicksRoute as PicksPanelRoute,
  MarcasRoute as MarcasPanelRoute, GruposRoute as GruposPanelRoute,
  RecomendadosRoute as RecomendadosPanelRoute,
  TrendsRoute as TrendsPanelRoute, FavoritosRoute as FavoritosPanelRoute,
  OutfitsRoute as OutfitsPanelRoute, FinanRoute as FinanPanelRoute,
  SuplementosRoute as SuplementosPanelRoute,
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
  const topbarRef = useRef(null);
  const tabbarRef  = useRef(null);

  // GPU fine-tuning blocking overlay state — separate useState (not the main
  // reducer) so it stays isolated from the catalog/filter state machine above.
  const [gpuTraining, setGpuTraining] = useState(null);
  const gpuPollingRef = useRef(null);

  const stopGpuPolling = useCallback(() => {
    if (gpuPollingRef.current) { clearInterval(gpuPollingRef.current); gpuPollingRef.current = null; }
  }, []);

  // Polling pattern mirrors MlStatusPanel.jsx lines 43-76: poll fetchMlEstado
  // + fetchMlResultado every 2s while training is running, stop when
  // !training.running, differentiate success vs error via phase==='error'.
  const startGpuPolling = useCallback(() => {
    stopGpuPolling();
    gpuPollingRef.current = setInterval(async () => {
      const [e, res] = await Promise.all([
        fetchMlEstado().catch(() => null),
        fetchMlResultado().catch(() => null),
      ]);
      if (!e) return;
      const ts = e.training || {};
      if (ts.running) {
        setGpuTraining(prev => ({
          ...prev, running:true, phase:ts.phase, pct:ts.pct,
          msg:ts.msg, startedAt:ts.startedAt, error:null, success:false,
        }));
        return;
      }
      // Training finished — differentiate success vs error
      stopGpuPolling();
      const isError = res?.phase === 'error' || ts.phase === 'error';
      if (isError) {
        setGpuTraining(prev => ({
          ...prev, running:false, phase:ts.phase || res?.phase,
          msg: res?.msg || ts.msg, error: res?.msg || ts.msg || 'Error desconocido', success:false,
        }));
      } else {
        setGpuTraining(prev => ({
          ...prev, running:false, phase:ts.phase, msg: res?.msg || ts.msg,
          error:null, success:true,
        }));
      }
    }, 2000);
  }, [stopGpuPolling]);

  const triggerGpuTraining = useCallback(async () => {
    if (gpuTraining?.running) return; // guard against double-trigger; backend also rejects with 400
    setGpuTraining({ running:true, phase:'renormalizando', pct:0, msg:'Actualizando categorías y marcas con las reglas más recientes...', startedAt:new Date().toISOString(), error:null, success:false });
    try {
      // Re-normaliza el catálogo ya persistido (sin re-scrapear) ANTES de
      // entrenar, para que el clasificador de imagen no aprenda de etiquetas
      // categoria/marca stale dejadas por reglas viejas de NormalizerService.
      await renormalizarCatalogo();
      setGpuTraining(prev => ({ ...prev, phase:'starting', msg:'' }));
      await startMlTraining(true, 8);
      startGpuPolling();
    } catch {
      // Network failure on renormalización o en el POST inicial — surface the
      // error escape hatch instead of leaving the blocking overlay stuck.
      setGpuTraining(prev => ({ ...prev, running:false, error:'No se pudo iniciar el entrenamiento (error de red).' }));
    }
  }, [gpuTraining, startGpuPolling]);

  const closeGpuOverlay = useCallback(() => {
    stopGpuPolling();
    setGpuTraining(null);
  }, [stopGpuPolling]);

  // Auto-hide overlay shortly after a successful run completes (no click needed)
  useEffect(() => {
    if (gpuTraining?.success) {
      const t = setTimeout(() => setGpuTraining(null), 2500);
      return () => clearTimeout(t);
    }
  }, [gpuTraining?.success]);

  useEffect(() => () => stopGpuPolling(), [stopGpuPolling]);

  // ResizeObserver — keeps --topbar-h / --tabbar-h / --sticky-offset in sync before paint
  // useLayoutEffect fires synchronously after DOM mutations and before the browser paints,
  // eliminating the first-frame flash where sticky elements use the CSS fallback value.
  useLayoutEffect(() => {
    const topbarNode = topbarRef.current;
    const tabbarNode = tabbarRef.current;
    if (!topbarNode || !tabbarNode) return;
    const update = () => {
      const th = topbarNode.offsetHeight;
      const tb = tabbarNode.offsetHeight;
      document.documentElement.style.setProperty('--topbar-h', th + 'px');
      document.documentElement.style.setProperty('--tabbar-h', tb + 'px');
      document.documentElement.style.setProperty('--sticky-offset', (th + tb) + 'px');
    };
    const ro = new ResizeObserver(update);
    ro.observe(topbarNode);
    ro.observe(tabbarNode);
    update(); // initial measurement before first resize event
    return () => ro.disconnect();
  }, []);

  // TASK-4: --compare-bar-h lifts the GPU FAB above the CompareBar when visible
  useEffect(() => {
    const val = S.comparar.length > 0 ? '64px' : '0px';
    document.documentElement.style.setProperty('--compare-bar-h', val);
  }, [S.comparar.length]);

  // Load saved outfits once on mount (independent of scrape data)
  const loadSavedOutfits = useCallback(async () => {
    const data = await fetchSavedOutfits();
    dispatch({ type: 'SET_SAVED_OUTFITS', payload: data || [] });
  }, []);

  // On mount: check if we already have data → load facets/favoritos/first page
  useEffect(() => {
    loadSavedOutfits();
    fetchStatus().then(st => {
      if (st?.tieneData) {
        set({ scrapeStatus:st.status, scrapeMsg:st.mensaje });
        loadFirstPage();
        loadFacets();
        loadFavoritos();
      }
    });
    // Recover an in-progress GPU training across page refreshes — don't lose it
    fetchMlEstado().then(e => {
      if (e?.training?.running) {
        const ts = e.training;
        setGpuTraining({ running:true, phase:ts.phase, pct:ts.pct, msg:ts.msg, startedAt:ts.startedAt, error:null, success:false });
        startGpuPolling();
      }
    }).catch(() => {});
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
      S.segment, S.genero, S.categorias, S.talles, S.gymrat, S.pack,
      S.precioMin, S.precioMax, S.orden]);

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
    ...(S.pack        && { pack:       true }),
    ...(S.precioMin !== undefined && { precioMin: S.precioMin }),
    ...(S.precioMax !== undefined && { precioMax: S.precioMax }),
    ...(S.subCategoria.length && { subCategoria: S.subCategoria }),
  }), [S.busq, S.sitioFiltro, S.rubroFiltro, S.marca, S.badge, S.segment,
       S.genero, S.categorias, S.talles, S.gymrat, S.pack,
       S.precioMin, S.precioMax, S.orden, S.subCategoria]);

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
    <div className="app-shell">
      <div ref={topbarRef}>
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
      </div>{/* topbarRef wrapper */}
      <div className="layout">
        <Sidebar
          open={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
          onOpen={() => setSidebarOpen(true)}
          facets={S.facets}
          meta={S.meta}
          filters={{ busq:S.busq, marca:S.marca, badge:S.badge, segment:S.segment,
                     genero:S.genero, categorias:S.categorias, talles:S.talles,
                     gymrat:S.gymrat, gymSubcats:S.gymSubcats, gymSubcatFiltro:S.gymSubcatFiltro,
                     pack:S.pack, precioMin:S.precioMin, precioMax:S.precioMax }}
          onFilter={payload => {
            // gymSubcatFiltro is client-side only — do not reset pagination
            if ('gymSubcatFiltro' in payload) { set(payload); }
            else { setFilter(payload); }
          }}
          onToggleCat={v => dispatch({ type:'TOGGLE_CAT', v })}
          onToggleSubcat={v => dispatch({ type:'TOGGLE_SUBCAT', v })}
          onToggleTalle={v => dispatch({ type:'TOGGLE_TALLE', v })}
          onReset={() => dispatch({ type:'RESET_FILTERS' })}
        />
        <div className="content">
          <div className="tab-bar" ref={tabbarRef}>
            <button className="sidebar-toggle" onClick={() => setSidebarOpen(o=>!o)}>☰</button>
            <NavLink to="/catalogo"  className={({isActive}) => `tab ${isActive?'active':''}`} title="Catálogo" aria-label="Catálogo">🛍 <span className="tab-label">Catálogo</span></NavLink>
            <NavLink to="/picks"     className={({isActive}) => `tab ${isActive?'active':''}`} title="Picks" aria-label="Picks">🏆 <span className="tab-label">Picks</span></NavLink>
            <NavLink to="/marcas"    className={({isActive}) => `tab ${isActive?'active':''}`} title="Marcas" aria-label="Marcas">🏷 <span className="tab-label">Marcas</span></NavLink>
            <NavLink to="/grupos"    className={({isActive}) => `tab ${isActive?'active':''}`} title="Comparar" aria-label="Comparar">⚖ <span className="tab-label">Comparar</span></NavLink>
            <NavLink to="/tendencias" className={({isActive}) => `tab ${isActive?'active':''}`} title="Tendencias" aria-label="Tendencias">📈 <span className="tab-label">Tendencias</span></NavLink>
            <NavLink to="/favoritos" className={({isActive}) => `tab ${isActive?'active':''}`} title="Favoritos" aria-label="Favoritos">⭐ <span className="tab-label">Favoritos</span></NavLink>
            <NavLink to="/outfits"      className={({isActive}) => `tab ${isActive?'active':''}`} title="Outfits" aria-label="Outfits">👕 <span className="tab-label">Outfits</span></NavLink>
            <NavLink to="/suplementos"  className={({isActive}) => `tab ${isActive?'active':''}`} title="Suplementos" aria-label="Suplementos">💊 <span className="tab-label">Suplementos</span></NavLink>
            <NavLink to="/recomendados" className={({isActive}) => `tab ${isActive?'active':''}`} title="Para ti" aria-label="Para ti">✨ <span className="tab-label">Para ti</span></NavLink>
            <NavLink to="/financiacion" className={({isActive}) => `tab ${isActive?'active':''}`} title="Cuotas" aria-label="Cuotas">💳 <span className="tab-label">Cuotas</span></NavLink>
          </div>

          <Suspense fallback={<RouteFallback/>}>
            <Outlet context={{
              S, set, setFilter, dispatch, loadNextPage, startPolling,
              loadFavoritos, sidebarOpen, setSidebarOpen, onClusterClick,
              gpuTraining, triggerGpuTraining,
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

      {gpuTraining && (
        <GpuTrainingOverlay training={gpuTraining} onClose={closeGpuOverlay}/>
      )}
    </div>
  );
}
