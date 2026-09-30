import { useEffect, useState, lazy, Suspense } from 'react';
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import SplashPanel from './components/SplashPanel';
import AppLayout, {
  CatalogoPanelRoute,
  PicksPanelRoute,
  CategoryPicksPageRoute,
  MarcasPanelRoute,
  GruposPanelRoute,
  MercadoPanelRoute,
  HistorialPanelRoute,
  OportunidadesPanelRoute,
  OportunidadesBadgePanelRoute,
  FavoritosPanelRoute,
  OutfitsPanelRoute,
  FinanPanelRoute,
  RecomendadosPanelRoute,
  SuplementosPanelRoute,
  PcsPanelRoute,
  CronjobsPanelRoute,
  UsuariosAdminPanelRoute,
} from './components/AppLayout';
import RouteFallback from './components/RouteFallback';
import NotFound from './components/NotFound';
import { CONFIG_DEFAULT } from './lib/scrapeDefaults';
import { EventStreamProvider } from './hooks/EventStreamProvider';
import { useScrapeStatusPolling } from './hooks/useScrapeStatusPolling';
import { readStatus } from './lib/readStatus';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import AuthGate from './auth/AuthGate';
import RequireRole from './auth/RequireRole';
import Login from './pages/Login';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';

// The API console is a STANDALONE page, not a panel inside the app shell:
// swagger-ui gets the whole viewport, so it is routed below as a sibling of
// /splash and /login rather than as a child of AppLayout. AppLayout knows
// nothing about it. Still lazy — swagger-ui-react is a heavy dependency no
// other screen pulls in.
const ApiDocsPanel = lazy(() => import('./components/ApiDocsPanel'));

// ─── RootGate ───────────────────────────────────────────────────────────────
// Initial-load gate for "/" only: checking | toSplash | toCatalogo.
// Decides where a fresh visit lands based on whether data already exists.
// /splash itself (explicit "re-scrape" navigation) never redirects — see SplashRoute.
function RootGate() {
  const [gate, setGate] = useState('checking');
  // frontend-perf T5: AppLayout used to call readStatus() again on its own
  // mount to decide whether to start loadFirstPage/loadFacets/loadFavoritos —
  // the exact fact this gate just read. Handed through router `state` on the
  // toCatalogo Navigate below, and consumed at most once (see AppLayout).
  const [status, setStatus] = useState(null);

  useEffect(() => {
    // `readStatus`, not `fetchStatus`: a backend that is not listening REJECTS,
    // and a bare `.then()` never ran its callback — `gate` stayed 'checking' and
    // `/` rendered the fallback forever. A read that failed cannot claim there
    // is a catalogue, so it goes to splash, which already knows how to say the
    // backend is unreachable.
    readStatus().then(st => {
      setStatus(st);
      setGate(st?.tieneData ? 'toCatalogo' : 'toSplash');
    });
  }, []);

  if (gate === 'checking') return <RouteFallback/>;
  if (gate === 'toCatalogo') return <Navigate to="/catalogo" state={{ status }} replace/>;
  return <Navigate to="/splash" replace/>;
}

// ─── SplashRoute ────────────────────────────────────────────────────────────
// Always renders SplashPanel — reachable both on first load with no data
// (via RootGate) and explicitly via "nuevo scraping" even when data exists.
export function SplashRoute() {
  const navigate = useNavigate();
  // frontend-auth-ui Phase 7 (design D5 consequence, tasks-part2 7.9/7.10):
  // RootGate sends a first-time visitor with no data straight here, and
  // SplashPanel's only action is POST /api/scrape (ADMIN). A VIEWER on a
  // fresh install would otherwise land on a screen whose one button 403s.
  const { isAdmin } = useAuth();
  const {
    status: scrapeStatus, mensaje: scrapeMsg, progreso, totalProds,
    backendUnreachable, tieneData, runInFlightAtMount, watchRun, markRunning,
  } = useScrapeStatusPolling();
  const [prods] = useState([]);
  const config = CONFIG_DEFAULT;

  // A run this tab never launched (landed on after a resume or a reload mid-run):
  // nobody called watchRun for it, so the hook reports it once at mount.
  useEffect(() => {
    if (runInFlightAtMount) watchRun(() => navigate('/catalogo'));
  }, [runInFlightAtMount, watchRun, navigate]);

  if (!isAdmin) {
    return (
      <div className="fixed inset-0 flex flex-col items-center justify-center gap-3 bg-bg p-6 text-center">
        <div className="text-[2.8rem] leading-none">🛍</div>
        <h1 className="text-xl font-semibold text-t1">Sin datos todavía</h1>
        <p className="max-w-sm text-sm text-t3">
          Todavía no hay datos — pedile a un administrador que corra un scraping.
        </p>
      </div>
    );
  }

  return (
    <SplashPanel
      config={config}
      scrapeStatus={scrapeStatus}
      scrapeMsg={scrapeMsg}
      progreso={progreso}
      backendUnreachable={backendUnreachable}
      tieneData={tieneData}
      onScrapeStart={markRunning}
      onWatchRun={done => watchRun(done, { reconcile: true })}
      onGoToApp={() => navigate('/catalogo')}
      prods={prods}
      totalProds={totalProds}
    />
  );
}

// ─── App (Routes) ────────────────────────────────────────────────────────────
// frontend-auth-ui, Phase 5 (design D5): AuthGate sits ABOVE this Routes tree
// so RootGate/SplashRoute — which call fetchStatus(), an AUTHENTICATED
// endpoint (ApiRoutePolicy.java:150) — cannot mount before auth has settled.
// RootGate itself is untouched; AuthGate just stops it mounting early.
export default function App() {
  return (
    <AuthProvider>
      <AuthGate>
        <EventStreamProvider>
        <Routes>
          <Route path="/login" element={<Login/>}/>
          <Route path="/forgot-password" element={<ForgotPassword/>}/>
          <Route path="/reset-password" element={<ResetPassword/>}/>
          <Route path="/" element={<RootGate/>}/>
          <Route path="/splash" element={<SplashRoute/>}/>
          {/* Standalone public console — outside AppLayout on purpose (see the
              lazy import above), and with no role guard: the backend serves a
              document filtered down to the PERMIT + AUTHENTICATED operations,
              so there is no ADMIN surface here to gate. /apidocs is in
              AuthGate's PUBLIC_ROUTES so an anonymous visitor reaches it. */}
          <Route path="/apidocs" element={
            <Suspense fallback={<RouteFallback/>}><ApiDocsPanel/></Suspense>
          }/>
          <Route path="/" element={<AppLayout/>}>
            <Route path="catalogo"   element={<CatalogoPanelRoute/>}/>
            <Route path="picks"      element={<PicksPanelRoute/>}/>
            <Route path="picks/:categoria" element={<CategoryPicksPageRoute/>}/>
            <Route path="marcas"     element={<MarcasPanelRoute/>}/>
            <Route path="grupos"     element={<GruposPanelRoute/>}/>
            {/* /tendencias retired (spec "Old route retired") -> redirect to /analisis/mercado */}
            <Route path="tendencias" element={<Navigate to="/analisis/mercado" replace/>}/>
            <Route path="analisis/mercado" element={<MercadoPanelRoute/>}/>
            <Route path="historial/:key" element={<HistorialPanelRoute/>}/>
            <Route path="analisis/oportunidades" element={<OportunidadesPanelRoute/>}/>
            <Route path="analisis/oportunidades/:badge" element={<OportunidadesBadgePanelRoute/>}/>
            <Route path="favoritos"  element={<FavoritosPanelRoute/>}/>
            <Route path="outfits"    element={<OutfitsPanelRoute/>}/>
            <Route path="suplementos" element={<SuplementosPanelRoute/>}/>
            <Route path="pcs"        element={<PcsPanelRoute/>}/>
            <Route path="recomendados" element={<RecomendadosPanelRoute/>}/>
            <Route path="financiacion" element={<FinanPanelRoute/>}/>
            {/* frontend-auth-ui Phase 7 (design D6, tasks-part2 7.8): explicit
                AccessDenied screen for a VIEWER, never a silent redirect —
                the whole surface is ADMIN in ApiRoutePolicy.TABLE. */}
            <Route path="cronjobs"   element={<RequireRole role="ADMIN"><CronjobsPanelRoute/></RequireRole>}/>
            {/* ABM de cuentas. Toda /api/usuarios/** es ADMIN en
                ApiRoutePolicy.TABLE, así que el gate de ruta espeja la
                política del backend en vez de esconder un botón. */}
            <Route path="admin/manage/users" element={<RequireRole role="ADMIN"><UsuariosAdminPanelRoute/></RequireRole>}/>
            <Route path="*" element={<NotFound/>}/>
          </Route>
        </Routes>
        </EventStreamProvider>
      </AuthGate>
    </AuthProvider>
  );
}
