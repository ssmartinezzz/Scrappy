import { useEffect, useState } from 'react';
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { fetchStatus } from './api';
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
  CronjobsPanelRoute,
  UsuariosAdminPanelRoute,
} from './components/AppLayout';
import RouteFallback from './components/RouteFallback';
import NotFound from './components/NotFound';
import { CONFIG_DEFAULT } from './lib/scrapeDefaults';
import { useScrapeStatusPolling } from './hooks/useScrapeStatusPolling';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import AuthGate from './auth/AuthGate';
import RequireRole from './auth/RequireRole';
import Login from './pages/Login';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';

// ─── RootGate ───────────────────────────────────────────────────────────────
// Initial-load gate for "/" only: checking | toSplash | toCatalogo.
// Decides where a fresh visit lands based on whether data already exists.
// /splash itself (explicit "re-scrape" navigation) never redirects — see SplashRoute.
function RootGate() {
  const [gate, setGate] = useState('checking');

  useEffect(() => {
    fetchStatus().then(st => {
      setGate(st?.tieneData ? 'toCatalogo' : 'toSplash');
    });
  }, []);

  if (gate === 'checking') return <RouteFallback/>;
  return <Navigate to={gate === 'toCatalogo' ? '/catalogo' : '/splash'} replace/>;
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
  // scrape-run-persistence-and-resume slice 0: the whole status state machine
  // (mount read, interval, unreachable backend) lives in the hook now. What
  // was here recreated `pollingRef = {current:null}` on EVERY render, so the
  // clearInterval that was supposed to replace an interval read a fresh null
  // and left the old one running; nothing cleaned up on unmount either.
  const {
    status: scrapeStatus, mensaje: scrapeMsg, progreso, totalProds,
    backendUnreachable, pollingNeeded, startPolling, markRunning,
  } = useScrapeStatusPolling();
  const [prods] = useState([]);
  const config = CONFIG_DEFAULT;

  // slice 6 (task 6.3): a run this tab never launched — landed on after a
  // resume, or after a reload mid-run. Only handleScrape used to arm the
  // poller, so the mount read wrote RUNNING to the screen and stopped there.
  // `pollingNeeded` is raised once by that mount read and never again, so
  // this cannot re-arm the interval on every render that sees a live run.
  useEffect(() => {
    if (pollingNeeded) startPolling(() => navigate('/catalogo'));
  }, [pollingNeeded, startPolling, navigate]);

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
      onScrapeStart={markRunning}
      onStartPolling={startPolling}
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
        <Routes>
          <Route path="/login" element={<Login/>}/>
          <Route path="/forgot-password" element={<ForgotPassword/>}/>
          <Route path="/reset-password" element={<ResetPassword/>}/>
          <Route path="/" element={<RootGate/>}/>
          <Route path="/splash" element={<SplashRoute/>}/>
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
      </AuthGate>
    </AuthProvider>
  );
}
