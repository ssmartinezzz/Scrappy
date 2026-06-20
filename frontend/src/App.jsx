import { useEffect, useState } from 'react';
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { fetchStatus } from './api';
import SplashPanel from './components/SplashPanel';
import AppLayout, {
  CatalogoPanelRoute,
  PicksPanelRoute,
  MarcasPanelRoute,
  GruposPanelRoute,
  TrendsPanelRoute,
  FavoritosPanelRoute,
  OutfitsPanelRoute,
  FinanPanelRoute,
} from './components/AppLayout';
import RouteFallback from './components/RouteFallback';
import NotFound from './components/NotFound';

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
function SplashRoute() {
  const navigate = useNavigate();
  const [scrapeStatus, setScrapeStatus] = useState('IDLE');
  const [scrapeMsg, setScrapeMsg] = useState('');
  const [progreso, setProgreso] = useState(null);
  const [prods, setProds] = useState([]);
  const [totalProds, setTotalProds] = useState(0);
  const config = { precioMin: 0, precioMax: 300000 };

  useEffect(() => {
    fetchStatus().then(st => {
      setScrapeStatus(st?.status || 'IDLE');
      setScrapeMsg(st?.mensaje || '');
    });
  }, []);

  const pollingRef = { current: null };
  const startPolling = (onDone) => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    pollingRef.current = setInterval(async () => {
      const st = await fetchStatus();
      if (!st) return;
      setScrapeStatus(st.status); setScrapeMsg(st.mensaje); setProgreso(st.progreso);
      if (st.status === 'RUNNING' && st.tieneData) setTotalProds(st.progreso?.total || 0);
      if (st.status === 'DONE' || st.status === 'ERROR') {
        clearInterval(pollingRef.current); pollingRef.current = null;
        onDone?.();
      }
    }, 1800);
  };

  return (
    <SplashPanel
      config={config}
      scrapeStatus={scrapeStatus}
      scrapeMsg={scrapeMsg}
      progreso={progreso}
      onScrapeStart={() => setScrapeStatus('RUNNING')}
      onStartPolling={startPolling}
      onGoToApp={() => navigate('/catalogo')}
      prods={prods}
      totalProds={totalProds}
    />
  );
}

// ─── App (Routes) ────────────────────────────────────────────────────────────
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<RootGate/>}/>
      <Route path="/splash" element={<SplashRoute/>}/>
      <Route path="/" element={<AppLayout/>}>
        <Route path="catalogo"   element={<CatalogoPanelRoute/>}/>
        <Route path="picks"      element={<PicksPanelRoute/>}/>
        <Route path="marcas"     element={<MarcasPanelRoute/>}/>
        <Route path="grupos"     element={<GruposPanelRoute/>}/>
        <Route path="tendencias" element={<TrendsPanelRoute/>}/>
        <Route path="favoritos"  element={<FavoritosPanelRoute/>}/>
        <Route path="outfits"    element={<OutfitsPanelRoute/>}/>
        <Route path="financiacion" element={<FinanPanelRoute/>}/>
        <Route path="*" element={<NotFound/>}/>
      </Route>
    </Routes>
  );
}
