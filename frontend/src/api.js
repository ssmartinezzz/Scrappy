// decouple-services-postgres, Batch 3 (task 3.5, design D6): the backend is
// now an API-only service on its own origin (SpaController/static/ removed),
// so the frontend can no longer assume same-origin '' relative paths in
// production. VITE_API_BASE_URL is the env-driven base; it defaults to ''
// (relative) so local dev keeps using Vite's `/api` proxy (vite.config.js)
// unless the var is explicitly set.
import { authedFetch } from './lib/authedFetch';

const BASE = import.meta.env.VITE_API_BASE_URL || '';

export async function fetchStatus() {
  const r = await authedFetch(`${BASE}/api/status`);
  return r.ok ? r.json() : null;
}

export async function startScrape({ precioMin, precioMax, sitios, forceRetrain = false }) {
  const p = new URLSearchParams({ precioMin, precioMax });
  sitios.forEach(s => p.append('sitios', s));
  if (forceRetrain) p.set('forceRetrain', 'true');
  const r = await authedFetch(`${BASE}/api/scrape?${p}`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

// scrape-run-persistence-and-resume slice 6. Both routes are ADMIN in
// ApiRoutePolicy.TABLE, so a VIEWER gets 403 and an expired token 401 —
// neither is an interrupted run, and neither may take down the page the
// banner sits on. Null means "no offer to show", same as an empty one.
export async function fetchInterrumpida() {
  const r = await authedFetch(`${BASE}/api/scrape/interrupted`);
  return r.ok ? r.json() : null;
}

// Answers 200 with `retomando:false` when there is nothing to resume or a
// scrape is already running. Reading only r.ok would send the user to a
// progress screen for a run that never started.
export async function retomarScrape() {
  const r = await authedFetch(`${BASE}/api/scrape/resume`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

export async function limpiarCatalogo() {
  return authedFetch(`${BASE}/api/db/productos`, { method: 'DELETE' });
}

export async function limpiarMl() {
  return authedFetch(`${BASE}/api/db/ml`, { method: 'DELETE' });
}

export async function fetchData(filters) {
  const p = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (Array.isArray(v)) v.forEach(vi => p.append(k, vi));
    else if (v !== '' && v !== null && v !== undefined) p.set(k, String(v));
  });
  const r = await authedFetch(`${BASE}/api/data?${p}`);
  return r.ok ? r.json() : null;
}

export async function deleteProducto(url) {
  const r = await authedFetch(`${BASE}/api/data?url=${encodeURIComponent(url)}`, { method: 'DELETE' });
  return r.ok;
}

export async function fetchFacets() {
  const r = await authedFetch(`${BASE}/api/facets`);
  return r.ok ? r.json() : null;
}

export async function fetchTendencias() {
  try {
    const r = await authedFetch(`${BASE}/api/tendencias`);
    if (r.status === 204) return { state: 'empty', data: null };
    if (r.status === 503) return { state: 'failed', data: null }; // pipeline ML falló
    if (r.ok) return { state: 'ok', data: await r.json() };
    console.error('[fetchTendencias] respuesta inesperada:', r.status);
    return { state: 'failed', data: null }; // cualquier otro no-ok
  } catch (err) {
    console.error('[fetchTendencias] error de red:', err);
    return { state: 'error', data: null }; // fetch rechazado (offline, DNS, CORS)
  }
}

export async function fetchHistorial(url) {
  const r = await authedFetch(`${BASE}/api/historial?url=${encodeURIComponent(url)}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

/**
 * Producto + su historial en una sola respuesta, para la vista dedicada.
 *
 * Entra por el handle corto (`key`, 16 hex) que viene en cada fila del
 * catalogo, no por la URL entera: una URL de producto como query param es
 * ilegible y hay que encodearla en cada borde. El handle es un alias de
 * presentacion — la identidad del producto sigue siendo su url.
 *
 * Distinto de fetchHistorial: ese endpoint responde 204 cuando no hay puntos,
 * lo que sirve para un sparkline (sin datos, no dibuja) pero no para una
 * pagina que igual tiene que renderizar el producto. Aca un 404 significa que
 * el producto no existe, y eso si es "no hay nada que mostrar".
 */
export async function fetchProductoDetalle(key) {
  const r = await authedFetch(`${BASE}/api/producto/${encodeURIComponent(key)}`);
  return r.ok ? r.json() : null;
}

export async function fetchInflacion() {
  const r = await authedFetch(`${BASE}/api/inflacion`);
  return r.ok ? r.json() : null;
}

export async function fetchRecomendacion(url) {
  const r = await authedFetch(`${BASE}/api/recomendacion?url=${encodeURIComponent(url)}`);
  return r.ok ? r.json() : null;
}

export async function fetchSitios() {
  const r = await authedFetch(`${BASE}/api/sitios`);
  return r.ok ? r.json() : null;
}

export async function addSitio(body) {
  const r = await authedFetch(`${BASE}/api/sitios`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  return r.ok;
}

export async function deleteSitio(nombre) {
  const r = await authedFetch(`${BASE}/api/sitios/${encodeURIComponent(nombre)}`, { method: 'DELETE' });
  return r.ok;
}

export async function updateConfig(cfg) {
  const r = await authedFetch(`${BASE}/api/config`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg)
  });
  return r.ok;
}

// ─── Presets de financiación ─────────────────────────────────────────────────

export async function fetchFinanciacionPresets() {
  const r = await authedFetch(`${BASE}/api/financiacion/presets`);
  return r.ok ? r.json() : null;
}

export async function crearFinanciacionPreset({ label, recargoPct, cuotas }) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label, recargoPct, cuotas }),
  });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function editarFinanciacionPreset(id, { label, recargoPct, cuotas }) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label, recargoPct, cuotas }),
  });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function activarFinanciacionPreset(id) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets/${id}/activar`, { method: 'PUT' });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function eliminarFinanciacionPreset(id) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets/${id}`, { method: 'DELETE' });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export function fmt(n) {
  if (!n && n !== 0) return '—';
  return Number(n).toLocaleString('es-AR', { maximumFractionDigits: 0 });
}

// Re-exported from lib/colors.js's BADGE_META (single source of truth for
// the 7 badge keys — badges-oportunidades-revamp) so existing `import {
// BADGE_LABELS } from '../api'` call sites keep working unchanged.
export { BADGE_LABELS } from './lib/colors';

export async function buscarExterno(nombre, productoUrl) {
  const p = new URLSearchParams({ q: nombre });
  if (productoUrl) p.set('url', productoUrl);
  const r = await authedFetch(`${BASE}/api/buscar-externo?${p}`);
  if (!r.ok) return { resultados: [], searchUrl: EXTERNAL_SEARCH.mercadolibre(nombre), queryUsada: nombre };
  const data = await r.json();
  // Compatibilidad: si el backend devuelve array (legacy) o el nuevo objeto
  if (Array.isArray(data)) return { resultados: data, searchUrl: EXTERNAL_SEARCH.mercadolibre(nombre), queryUsada: nombre };
  return data;
}

export const EXTERNAL_SEARCH = {
  mercadolibre: q => `https://listado.mercadolibre.com.ar/${encodeURIComponent(q.toLowerCase().replace(/\s+/g,'-').replace(/[^a-z0-9-]/g,''))}`,
  amazon:       q => `https://www.amazon.com.ar/s?k=${encodeURIComponent(q)}`,
  google:       q => `https://www.google.com.ar/search?q=${encodeURIComponent(q)}+precio+argentina&tbm=shop`,
};

// Note (decouple-services-postgres, task 4.10): the file-based DB
// export/import helpers (exportarDB/importarDB) were removed — persistence
// moved to PostgreSQL (no scraper.db file to download/upload). The backend
// endpoints they called (`GET /api/db/export`, `POST /api/db/import`) now
// answer `410 Gone`. Use `pg_dump`/`pg_restore` directly against
// `DATABASE_URL` for backup/restore.

export async function fetchGrupos(filters = {}) {
  const p = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== '' && v != null) p.set(k, v);
  });
  const r = await authedFetch(`${BASE}/api/grupos?${p}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

export async function fetchMejores(rubro = '') {
  const p = new URLSearchParams();
  if (rubro) p.set('rubro', rubro);
  const r = await authedFetch(`${BASE}/api/mejores?${p}`);
  if (r.status === 204) return [];
  return r.ok ? r.json() : [];
}

export async function fetchMarcasBrowser(params = {}) {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v) p.set(k, v); });
  const r = await authedFetch(`${BASE}/api/marcas-browser?${p}`);
  if (r.status === 204) return [];
  return r.ok ? r.json() : [];
}

// ─── ML Training ─────────────────────────────────────────────────────────────
export async function fetchMlEstado() {
  const r = await authedFetch(`${BASE}/api/ml/estado`);
  return r.ok ? r.json() : null;
}

export async function startMlTraining(images = false, epochs = 8) {
  const p = new URLSearchParams({ images, epochs });
  const r = await authedFetch(`${BASE}/api/ml/entrenar?${p}`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

export async function fetchMlResultado() {
  const r = await authedFetch(`${BASE}/api/ml/resultado`);
  return r.ok ? r.json() : null;
}

export async function aplicarModeloML() {
  const r = await authedFetch(`${BASE}/api/ml/aplicar`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

export async function renormalizarCatalogo() {
  const r = await authedFetch(`${BASE}/api/ml/renormalizar`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

// ─── Favoritos ─────────────────────────────────────────────────────────────

export async function fetchFavoritos() {
  const r = await authedFetch(`${BASE}/api/favoritos`);
  return r.ok ? r.json() : [];
}

export async function addFavorito({ url, sitio, nombre }) {
  const r = await authedFetch(`${BASE}/api/favoritos`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, sitio, nombre })
  });
  return r.ok;
}

export async function removeFavorito(url) {
  const r = await authedFetch(`${BASE}/api/favoritos?url=${encodeURIComponent(url)}`, { method: 'DELETE' });
  return r.ok;
}

// ─── Outfits (armador Gym) ───────────────────────────────────────────────────

export async function fetchOutfit(genero, presupuesto = 0, excluirUrls = [], presupuestoSuplementos = 0) {
  const p = new URLSearchParams();
  if (genero) p.set('genero', genero);
  if (presupuesto > 0) p.set('presupuesto', presupuesto);
  if (excluirUrls.length) p.set('excluir', excluirUrls.join(','));
  if (presupuestoSuplementos > 0) p.set('presupuestoSuplementos', presupuestoSuplementos);
  const r = await authedFetch(`${BASE}/api/outfits?${p}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

// ─── Budget-Aware Outfit Builder ─────────────────────────────────────────────

/**
 * Calls GET /api/outfits/builder to find the globally-optimal product
 * combination across the requested categories within the budget ceiling.
 *
 * @param {Object} params
 * @param {string[]} params.categorias  canonical category names (1–10)
 * @param {number}   params.presupuesto hard budget ceiling (must be > 0)
 * @param {string}   [params.genero]    optional gender filter
 * @param {string}   [params.estilo]    'gym' (default) or 'casual' — selects the
 *                                       torso/piernas eligibility gate on the backend
 * @returns {Promise<Object|null>} builder result or null on error
 */
export async function fetchOutfitBuilder({ categorias, presupuesto, genero, excluir = [], greedy = false, pin = [], estilo = 'gym' }) {
  const p = new URLSearchParams();
  if (categorias && categorias.length) p.set('categorias', categorias.join(','));
  // presupuesto=0 or empty means no limit → send a large ceiling so the API accepts it
  const budget = presupuesto > 0 ? presupuesto : 100_000_000;
  p.set('presupuesto', budget);
  if (genero) p.set('genero', genero);
  if (excluir && excluir.length) p.set('excluir', excluir.join(','));
  if (pin && pin.length) p.set('pin', pin.join(','));
  if (greedy) p.set('greedy', 'true');
  if (estilo && estilo !== 'gym') p.set('estilo', estilo);
  const r = await authedFetch(`${BASE}/api/outfits/builder?${p}`);
  if (r.status === 204) return null;
  if (!r.ok) return null;
  return r.json();
}

// ─── Saved Outfits ───────────────────────────────────────────────────────────

export async function saveOutfit(body) {
  const r = await authedFetch(`${BASE}/api/outfits/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return r.ok ? r.json() : null;
}

export async function fetchSavedOutfits() {
  const r = await authedFetch(`${BASE}/api/outfits/saved`);
  return r.ok ? r.json() : [];
}

export async function deleteSavedOutfit(id) {
  const r = await authedFetch(`${BASE}/api/outfits/saved/${id}`, { method: 'DELETE' });
  return r.ok;
}

export async function renameOutfit(id, nombre) {
  const r = await authedFetch(`${BASE}/api/outfits/saved/${id}/nombre`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nombre }),
  });
  return r.ok;
}

// Resets only the given style's like/dislike history (gym | casual). The feed's
// shared "catalog" signal is never cleared here.
export async function resetOutfitFeedback(estilo = 'gym') {
  const p = new URLSearchParams();
  if (estilo) p.set('estilo', estilo);
  const r = await authedFetch(`${BASE}/api/outfits/feedback?${p}`, { method: 'DELETE' });
  return r.ok;
}

// body shape: { genero, items: [{ slot, url, liked }] } — one POST per rated item (per-item feedback contract).
export async function sendOutfitFeedback(body) {
  const r = await authedFetch(`${BASE}/api/outfits/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  return r.ok ? r.json() : null;
}

// ─── Recomendados ("Para ti" feed) ───────────────────────────────────────────

export async function fetchRecomendados(page = 1, size = 24, filters = {}) {
  const p = new URLSearchParams({ page, size });
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== '' && v !== null && v !== undefined) p.set(k, String(v));
  });
  const r = await authedFetch(`${BASE}/api/recomendados?${p}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

// body shape: { genero, items: [{ url, liked }] } — per-card like/dislike,
// writes to the same shared taste signal store as sendOutfitFeedback().
export async function sendRecomendadosFeedback(genero, items) {
  const r = await authedFetch(`${BASE}/api/recomendados/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ genero, items })
  });
  return r.ok ? r.json() : null;
}

export async function dismissCategoria(categoria) {
  const r = await authedFetch(`${BASE}/api/recomendados/dismiss-categoria`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ categoria })
  });
  return r.ok ? r.json() : null;
}

export async function undismissCategoria(categoria) {
  const r = await authedFetch(`${BASE}/api/recomendados/dismiss-categoria?categoria=${encodeURIComponent(categoria)}`,
    { method: 'DELETE' });
  return r.ok ? r.json() : null;
}

// ─── Supplement Builder ───────────────────────────────────────────────────────

/**
 * Supplement subtypes the builder can offer, in combo-assembly order.
 * Returns [] on failure so the caller can fall back rather than crash the panel.
 */
export async function fetchSuplementosTipos() {
  const r = await authedFetch(`${BASE}/api/suplementos/tipos`);
  if (!r.ok) return [];
  const body = await r.json();
  return Array.isArray(body?.tipos) ? body.tipos : [];
}

export async function fetchSuplementosBuilder({ tipos, presupuesto = 0, excluir = [] }) {
  const p = new URLSearchParams();
  p.set('tipos', tipos.join(','));
  if (presupuesto > 0) p.set('presupuesto', presupuesto);
  // URLs ya mostradas: el pick del servidor es determinístico, así que sin esto
  // "Regenerar" repite la misma respuesta.
  if (excluir.length > 0) p.set('excluir', excluir.join(','));
  const r = await authedFetch(`${BASE}/api/suplementos/builder?${p}`);
  if (r.status === 204) return null;
  if (!r.ok) return null;
  return r.json();
}

// ─── Cron Jobs (panel de administración /cronjobs) ───────────────────────────
// No hay endpoint de detalle de ejecución individual — /executions ya trae
// logOutput embebido por fila (ver ar.scraper.web.CronApiController).

export async function listCronJobs() {
  const r = await authedFetch(`${BASE}/api/cron`);
  return r.ok ? r.json() : null;
}

export async function getCronJob(id) {
  const r = await authedFetch(`${BASE}/api/cron/${id}`);
  return r.ok ? r.json() : null;
}

export async function createCronJob(job) {
  const r = await authedFetch(`${BASE}/api/cron`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(job),
  });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function updateCronJob(id, job) {
  const r = await authedFetch(`${BASE}/api/cron/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(job),
  });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function deleteCronJob(id) {
  const r = await authedFetch(`${BASE}/api/cron/${id}`, { method: 'DELETE' });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

// Fire-and-forget trigger — backend dispatches on a virtual thread and
// responds immediately: 202 {ok:true,...} started, 409 {ok:false,...} scraper
// busy or job already in-flight, 404 {ok:false,...} job no longer exists.
export async function runCronNow(id) {
  const r = await authedFetch(`${BASE}/api/cron/${id}/run-now`, { method: 'POST' });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function fetchCronExecutions(id, limit = 50) {
  const p = new URLSearchParams({ limit });
  const r = await authedFetch(`${BASE}/api/cron/${id}/executions?${p}`);
  return r.ok ? r.json() : null;
}

// ─── LLM Catalog Agent (llm-catalog-nlp) ─────────────────────────────────────
// Read-only chat/model-discovery + the single out-of-loop write endpoint
// (applyProposal). askAgent/fetchAgentModels surface a 409 as
// { scraping: true } instead of throwing — the panel shows a clear
// "wait for the scrape to finish" message instead of a generic error.

export async function fetchAgentModels() {
  const r = await authedFetch(`${BASE}/api/agent/models`);
  return r.ok ? r.json() : null;
}

export async function askAgent(messages, model) {
  const r = await authedFetch(`${BASE}/api/agent/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(model ? { messages, model } : { messages }),
  });
  if (r.status === 409) return { scraping: true };
  if (!r.ok) {
    const body = await r.json().catch(() => ({}));
    return {
      error: true,
      mensaje: body.mensaje || 'No se pudo consultar al agente.',
      codigo: body.codigo,
    };
  }
  return r.json();
}

export async function applyProposal(proposal) {
  const r = await authedFetch(`${BASE}/api/agent/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(proposal),
  });
  if (r.status === 409) return { scraping: true };
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}
