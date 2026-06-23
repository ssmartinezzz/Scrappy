import { useState, useEffect } from 'react';
import { fetchMlResultado, fetchMlEstado, fetchStatus } from '../api';
import { fmt } from '../api';
import { cn } from '@/lib/utils';

const RUBRO_DEF = [
  { key: '',              icon: '🛍', label: 'Todos'        },
  { key: 'indumentaria',  icon: '👕', label: 'Indumentaria' },
  { key: 'tecnologia',    icon: '💻', label: 'Tecnología'   },
  { key: 'suplementos',   icon: '💊', label: 'Suplementos'  },
];

export default function Topbar({
  meta, facets, sitioFiltro, rubroFiltro,
  onSitioChange, onRubroChange, onReScrape,
  gymrat, onGymratToggle,
}) {
  const [collapsed, setCollapsed] = useState(false);
  const sitioMap  = meta?.marcas        || {};
  const rubrosMap = facets?.rubros      || {};
  const total     = meta?.total         || 0;

  // Solo mostrar rubros que tienen productos (o todos por defecto)
  const rubros = RUBRO_DEF.filter(r =>
    r.key === '' || (rubrosMap[r.key] || 0) > 0
  );

  // Polling estado ML
  const [mlStatus, setMlStatus] = useState(null);
  useEffect(() => {
    let alive = true;
    const poll = async () => {
      const r = await fetchMlResultado().catch(() => null);
      if (alive) setMlStatus(r);
      if (alive && r?.running) setTimeout(poll, 4000);
    };
    poll();
    return () => { alive = false; };
  }, []);

  // IPC widget data
  const [ipcData, setIpcData] = useState(null);
  useEffect(() => {
    fetch('/api/inflacion')
      .then(r => r.ok ? r.json() : null)
      .then(d => setIpcData(d))
      .catch(() => {});
  }, []);

  // ML banner data
  const [mlBanner, setMlBanner] = useState(null);
  useEffect(() => {
    Promise.all([
      fetchStatus().catch(() => null),
      fetchMlEstado().catch(() => null),
    ]).then(([st, ml]) => setMlBanner({ st, ml }));
  }, []);

  // Sitios: todos los que tienen productos, ordenados desc
  const sitios = Object.entries(sitioMap)
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1]);

  const gymratCount = facets?.gymratCount || 0;

  return (
    <div className="flex-shrink-0 bg-s1 border-b border-border">
      {/* Row 1: Logo + Rubro tabs */}
      <div className="flex items-center gap-1 px-2 py-[.45rem] border-b border-s3">
        <span className="text-base mr-[4px]">🛒</span>
        <strong className="text-[.85rem] text-primary2 mr-[12px]">Scraper AR</strong>

        {/* Rubro tabs */}
        <div className="flex gap-[3px]">
          {rubros.map(r => {
            const count = r.key ? rubrosMap[r.key] || 0 : total;
            const active = rubroFiltro === r.key;
            return (
              <button key={r.key} onClick={() => onRubroChange(r.key)}
                className={cn(
                  'flex items-center gap-[4px] rounded-full border-none px-[10px] py-[4px]',
                  'cursor-pointer text-[.72rem] font-bold transition-all duration-150',
                  active ? 'bg-primary text-white' : 'bg-transparent text-t4'
                )}>
                {r.icon} {r.label}
                {count > 0 && (
                  <span className="opacity-70 text-[.62rem]">
                    {count.toLocaleString('es-AR')}
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {/* ML Training indicator */}
        {mlStatus?.running && (
          <div className="flex items-center gap-[5px] ml-1 rounded-full px-[10px] py-[3px]
                          bg-[color-mix(in_srgb,var(--p)_18%,transparent)] border border-[color-mix(in_srgb,var(--p)_40%,transparent)]
                          text-[.68rem] text-primary animate-[mlpulse_1.5s_ease-in-out_infinite]">
            <span className="text-[.75rem]">🤖</span>
            Entrenando ML...
          </div>
        )}
        {mlStatus?.done && !mlStatus?.running && (
          <div className="flex items-center gap-[5px] ml-1 rounded-full px-[10px] py-[3px]
                          bg-[color-mix(in_srgb,var(--sem-positive)_15%,transparent)] border border-[color-mix(in_srgb,var(--sem-positive)_40%,transparent)]
                          text-[.68rem] text-success cursor-default"
            title='Modelo ML entrenado y activo'>
            🤖 Modelo actualizado
          </div>
        )}
        {meta?.mlModeloActivo && !mlStatus?.running && !mlStatus?.done && (
          <div className="flex items-center gap-[4px] ml-1 rounded-full px-[10px] py-[3px]
                          bg-[color-mix(in_srgb,var(--sem-positive)_10%,transparent)] border border-[color-mix(in_srgb,var(--sem-positive)_25%,transparent)]
                          text-[.65rem] text-success"
            title={`Categorías refinadas por ML: ${(meta?.mlRefinadas||0).toLocaleString('es-AR')}`}>
            🤖 ML {(meta?.mlRefinadas||0) > 0 ? `·${meta.mlRefinadas} refinadas` : 'activo'}
          </div>
        )}
        <style>{`@keyframes mlpulse{0%,100%{opacity:1}50%{opacity:.55}}`}</style>

        {/* IPC Widget */}
        {ipcData?.mensual != null && (
          <div className="ipc-widget">
            <span>📊 IPC: <strong>{ipcData.mensual.toFixed(1)}%</strong>/mes</span>
            {ipcData.interanual != null && (
              <span className="text-t4">| Anual: <strong className="text-t2">{Math.round(ipcData.interanual)}%</strong></span>
            )}
          </div>
        )}

        {/* ML Banner */}
        {mlBanner && (
          <div className="ml-banner">
            {mlBanner.ml?.hasTextModel && mlBanner.ml?.textMeta
              ? `🧠 ML ${(mlBanner.ml.textMeta.accuracy != null ? (mlBanner.ml.textMeta.accuracy * 100).toFixed(1) : '?')}% acc · ${mlBanner.st?.mlRefinadas || 0} ref.`
              : '🧠 ML estadístico'}
          </div>
        )}

        {/* Re-scrape button */}
        <div className="ml-auto">
          <button onClick={onReScrape}
            className="rounded-full border-[1.5px] border-border bg-transparent px-[10px] py-[4px]
                       text-[.7rem] text-t4 cursor-pointer">
            ↺ Nuevo scraping
          </button>
        </div>
      </div>

      {/* Row 2: Sitio breadcrumbs */}
      {sitios.length > 0 && (
        <div className="flex items-center gap-[4px] px-2 py-[.3rem] overflow-x-auto flex-nowrap [scrollbar-width:none]">
          <button
            onClick={() => onSitioChange('')}
            className={cn(
              'flex-shrink-0 rounded-xl border-none px-[9px] py-[2px]',
              'cursor-pointer text-[.68rem] font-bold',
              !sitioFiltro ? 'bg-primary text-white' : 'bg-s2 text-t4'
            )}>
            Todas {total > 0 && <span className="opacity-70">{total.toLocaleString('es-AR')}</span>}
          </button>

          {gymratCount > 0 && (
            <button
              onClick={onGymratToggle}
              className={cn(
                'flex-shrink-0 whitespace-nowrap rounded-xl border-none px-[9px] py-[2px]',
                'cursor-pointer text-[.67rem] transition-all duration-150',
                gymrat ? 'font-bold bg-[var(--sem-gym)] text-white' : 'font-normal bg-s2 text-t4'
              )}>
              🏋️ Gym <span className="opacity-80">{gymratCount.toLocaleString('es-AR')}</span>
            </button>
          )}

          {sitios.map(([nombre, count]) => {
            const active = sitioFiltro === nombre;
            return (
              <button key={nombre}
                onClick={() => onSitioChange(active ? '' : nombre)}
                className={cn(
                  'flex-shrink-0 whitespace-nowrap rounded-xl px-[9px] py-[2px]',
                  'cursor-pointer text-[.67rem] transition-all duration-150 border',
                  active ? 'font-bold border-primary bg-[color-mix(in_srgb,var(--p)_15%,transparent)] text-primary2'
                         : 'font-normal border-border bg-transparent text-t4'
                )}>
                {nombre} <span className="opacity-60">{count}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
