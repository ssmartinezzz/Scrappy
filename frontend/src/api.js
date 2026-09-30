import { authedFetch } from './lib/authedFetch';

// Runtime first, build-time second. `window.__API_BASE__` is set by /config.js,
// which the launcher rewrites per run — so one dist/ serves localhost, a LAN
// origin behind TLS, or a deployment, without rebuilding. A blank value means
// "not configured" (an untouched placeholder included), never a literal host.
// Exported so loadContract.js reuses this exact expression instead of a
// third copy (a miscopy of this precedence order is a recorded prod bug).
export const BASE =
  (typeof window !== 'undefined' && window.__API_BASE__) ||
  import.meta.env.VITE_API_BASE_URL ||
  '';

export class ApiError extends Error {
  constructor({ code, message, details, status }) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.details = details;
    this.status = status;
  }
}

async function readBody(response) {
  try { return await response.json(); } catch { return null; }
}

async function parse(response) {
  const body = await readBody(response);
  if (!response.ok) {
    const e = body?.error;
    throw new ApiError({
      code: e?.code ?? `http_${response.status}`,
      message: e?.message ?? `HTTP ${response.status}`,
      details: e?.details,
      status: response.status,
    });
  }
  return body;
}

/** `body.data` of a JSON envelope; null for 204 or an empty body. Throws ApiError on a non-ok status. */
export async function unwrap(response) {
  if (response.status === 204) return null;
  return (await parse(response))?.data ?? null;
}

/** Like unwrap, for lists: `{ data, page }` where page is `{number,size,total,totalPages}` (0-based). */
export async function unwrapPage(response) {
  if (response.status === 204) return { data: null, page: null };
  const body = await parse(response);
  return { data: body?.data ?? null, page: body?.page ?? null };
}

// Callers that treat any failed call as "nothing to show" get `fallback`; network errors still reject.
async function softUnwrap(response, fallback = null) {
  try {
    return (await unwrap(response)) ?? fallback;
  } catch (e) {
    if (e instanceof ApiError) return fallback;
    throw e;
  }
}

// Mutations whose callers read `{ ok, mensaje }`: failures become `{ ok:false, mensaje }`.
async function opResult(response) {
  try {
    return { ok: true, ...((await unwrap(response)) ?? {}) };
  } catch (e) {
    return { ok: false, mensaje: e instanceof ApiError ? e.message : 'Error de red' };
  }
}

async function unwrapCatalogPage(response) {
  try {
    const { data, page } = await unwrapPage(response);
    return { data, page };
  } catch (e) {
    if (e instanceof ApiError) return { data: null, page: null };
    throw e;
  }
}

export async function fetchStatus() {
  const r = await authedFetch(`${BASE}/api/status`);
  return softUnwrap(r);
}

// Raw Response on purpose: the caller reads the body as a stream. `init.signal`
// aborts it; the Bearer header is added by authedFetch, which is why this is not an EventSource.
export function openEventStream(init) {
  return authedFetch(`${BASE}/api/events`, init);
}

export async function startScrape({ precioMin, precioMax, sitios, forceRetrain = false }) {
  const p = new URLSearchParams({ precioMin, precioMax });
  sitios.forEach(s => p.append('sitios', s));
  if (forceRetrain) p.set('forceRetrain', 'true');
  const r = await authedFetch(`${BASE}/api/scrape?${p}`, { method: 'POST' });
  return softUnwrap(r);
}

// Both routes are ADMIN: a VIEWER gets 403 and an expired token 401 — neither is an
// interrupted run, and neither may take down the page the banner sits on.
export async function fetchInterrumpida() {
  const r = await authedFetch(`${BASE}/api/scrape/interrupted`);
  return softUnwrap(r);
}

// Answers 200 with `retomando:false` when there is nothing to resume or a scrape is
// already running; callers must read `retomando`, not just success.
export async function retomarScrape() {
  const r = await authedFetch(`${BASE}/api/scrape/resume`, { method: 'POST' });
  return softUnwrap(r);
}

export async function descartarInterrumpida() {
  const r = await authedFetch(`${BASE}/api/scrape/discard`, { method: 'POST' });
  return softUnwrap(r);
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
  const { data, page } = await unwrapCatalogPage(r);
  if (!data) return null;
  return {
    ...data,
    meta: {
      ...data.meta,
      total: page?.total ?? 0,
      pagina: page?.number ?? 0,
      pageSize: page?.size ?? 0,
      totalPaginas: page?.totalPages ?? 0,
    },
  };
}

export async function deleteProducto(url) {
  const r = await authedFetch(`${BASE}/api/data?url=${encodeURIComponent(url)}`, { method: 'DELETE' });
  return r.ok;
}

export async function fetchFacets() {
  const r = await authedFetch(`${BASE}/api/facets`);
  return softUnwrap(r);
}

export async function fetchTendencias() {
  try {
    const r = await authedFetch(`${BASE}/api/tendencias`);
    if (r.status === 204) return { state: 'empty', data: null };
    if (r.status === 503) return { state: 'failed', data: null }; // pipeline ML falló
    if (r.ok) return { state: 'ok', data: await unwrap(r) };
    console.error('[fetchTendencias] respuesta inesperada:', r.status);
    return { state: 'failed', data: null }; // cualquier otro no-ok
  } catch (err) {
    console.error('[fetchTendencias] error de red:', err);
    return { state: 'error', data: null }; // fetch rechazado (offline, DNS, CORS)
  }
}

export async function fetchHistorial(url) {
  const r = await authedFetch(`${BASE}/api/historial?url=${encodeURIComponent(url)}`);
  return softUnwrap(r);
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
  return softUnwrap(r);
}

// { ipc: Resumen, usd: Resumen, actualizado }
export async function fetchIndices() {
  const r = await authedFetch(`${BASE}/api/indices`);
  return softUnwrap(r);
}

export async function fetchRecomendacion(url) {
  const r = await authedFetch(`${BASE}/api/recomendacion?url=${encodeURIComponent(url)}`);
  return softUnwrap(r);
}

export async function fetchSitios() {
  const r = await authedFetch(`${BASE}/api/sitios`);
  return softUnwrap(r);
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
  return softUnwrap(r);
}

export async function crearFinanciacionPreset({ label, recargoPct, cuotas }) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label, recargoPct, cuotas }),
  });
  return opResult(r);
}

export async function editarFinanciacionPreset(id, { label, recargoPct, cuotas }) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label, recargoPct, cuotas }),
  });
  return opResult(r);
}

export async function activarFinanciacionPreset(id) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets/${id}/activar`, { method: 'PUT' });
  return opResult(r);
}

export async function eliminarFinanciacionPreset(id) {
  const r = await authedFetch(`${BASE}/api/financiacion/presets/${id}`, { method: 'DELETE' });
  return opResult(r);
}

export function fmt(n) {
  if (!n && n !== 0) return '—';
  return Number(n).toLocaleString('es-AR', { maximumFractionDigits: 0 });
}

// Re-exported so existing `import { BADGE_LABELS } from '../api'` call sites keep working.
export { BADGE_LABELS } from './lib/colors';

export async function buscarExterno(nombre, productoUrl) {
  const p = new URLSearchParams({ q: nombre });
  if (productoUrl) p.set('url', productoUrl);
  const r = await authedFetch(`${BASE}/api/buscar-externo?${p}`);
  const data = await softUnwrap(r);
  if (!data) return { resultados: [], searchUrl: EXTERNAL_SEARCH.mercadolibre(nombre), queryUsada: nombre };
  // Compatibilidad: si el backend devuelve array (legacy) o el nuevo objeto
  if (Array.isArray(data)) return { resultados: data, searchUrl: EXTERNAL_SEARCH.mercadolibre(nombre), queryUsada: nombre };
  return data;
}

export const EXTERNAL_SEARCH = {
  mercadolibre: q => `https://listado.mercadolibre.com.ar/${encodeURIComponent(q.toLowerCase().replace(/\s+/g,'-').replace(/[^a-z0-9-]/g,''))}`,
  amazon:       q => `https://www.amazon.com.ar/s?k=${encodeURIComponent(q)}`,
  google:       q => `https://www.google.com.ar/search?q=${encodeURIComponent(q)}+precio+argentina&tbm=shop`,
};

export async function fetchGrupos(filters = {}) {
  const p = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== '' && v != null) p.set(k, v);
  });
  const r = await authedFetch(`${BASE}/api/grupos?${p}`);
  const { data, page } = await unwrapCatalogPage(r);
  if (!data) return null;
  return { grupos: data, total: page?.total ?? 0, page: page?.number ?? 0, size: page?.size ?? 0 };
}

export async function fetchMejores(rubro = '') {
  const p = new URLSearchParams();
  if (rubro) p.set('rubro', rubro);
  const r = await authedFetch(`${BASE}/api/mejores?${p}`);
  return softUnwrap(r, []);
}

export async function fetchMarcasBrowser(params = {}) {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v) p.set(k, v); });
  const r = await authedFetch(`${BASE}/api/marcas-browser?${p}`);
  return softUnwrap(r, []);
}

// ─── ML Training ─────────────────────────────────────────────────────────────
export async function fetchMlEstado() {
  const r = await authedFetch(`${BASE}/api/ml/estado`);
  return softUnwrap(r);
}

export async function startMlTraining(images = false, epochs = 8) {
  const p = new URLSearchParams({ images, epochs });
  const r = await authedFetch(`${BASE}/api/ml/entrenar?${p}`, { method: 'POST' });
  return softUnwrap(r);
}

export async function fetchMlResultado() {
  const r = await authedFetch(`${BASE}/api/ml/resultado`);
  return softUnwrap(r);
}

export async function aplicarModeloML() {
  const r = await authedFetch(`${BASE}/api/ml/aplicar`, { method: 'POST' });
  return softUnwrap(r);
}

export async function renormalizarCatalogo() {
  const r = await authedFetch(`${BASE}/api/ml/renormalizar`, { method: 'POST' });
  return softUnwrap(r);
}

// ─── Favoritos ─────────────────────────────────────────────────────────────

export async function fetchFavoritos() {
  const r = await authedFetch(`${BASE}/api/favoritos`);
  return softUnwrap(r, []);
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
  return softUnwrap(r);
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
  return softUnwrap(r);
}

// ─── Saved Outfits ───────────────────────────────────────────────────────────

export async function saveOutfit(body) {
  const r = await authedFetch(`${BASE}/api/outfits/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return softUnwrap(r);
}

export async function fetchSavedOutfits() {
  const r = await authedFetch(`${BASE}/api/outfits/saved`);
  return softUnwrap(r, []);
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
  return softUnwrap(r);
}

// ─── Recomendados ("Para ti" feed) ───────────────────────────────────────────

export async function fetchRecomendados(page = 0, size = 24, filters = {}) {
  const p = new URLSearchParams({ page, size });
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== '' && v !== null && v !== undefined) p.set(k, String(v));
  });
  const r = await authedFetch(`${BASE}/api/recomendados?${p}`);
  const { data, page: meta } = await unwrapCatalogPage(r);
  if (!data) return null;
  return { items: data, total: meta?.total ?? 0, page: meta?.number ?? 0, size: meta?.size ?? 0 };
}

// body shape: { genero, items: [{ url, liked }] } — per-card like/dislike,
// writes to the same shared taste signal store as sendOutfitFeedback().
export async function sendRecomendadosFeedback(genero, items) {
  const r = await authedFetch(`${BASE}/api/recomendados/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ genero, items })
  });
  return softUnwrap(r);
}

export async function dismissCategoria(categoria) {
  const r = await authedFetch(`${BASE}/api/recomendados/dismiss-categoria`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ categoria })
  });
  return softUnwrap(r);
}

export async function undismissCategoria(categoria) {
  const r = await authedFetch(`${BASE}/api/recomendados/dismiss-categoria?categoria=${encodeURIComponent(categoria)}`,
    { method: 'DELETE' });
  return softUnwrap(r);
}

// ─── Supplement Builder ───────────────────────────────────────────────────────

/**
 * Supplement subtypes the builder can offer, in combo-assembly order.
 * Returns [] on failure so the caller can fall back rather than crash the panel.
 */
export async function fetchSuplementosTipos() {
  const r = await authedFetch(`${BASE}/api/suplementos/tipos`);
  const body = await softUnwrap(r);
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
  return softUnwrap(r);
}

// ─── PC Builder ───────────────────────────────────────────────────────────────

export async function fetchPcsBuilder({
  presupuesto = 0, conGpu = false, excluir = [], gama = '',
  ddr = '', marcaCpu = '', marcaGpu = '', tipoAlmacenamiento = '', ramDual = false, wifi = false,
  // Fase 9. Los dos pisos viajan sólo si son > 0: el servidor RECHAZA un 0
  // (un filtro que no filtra no es un pedido), así que "sin pedido" es
  // ausencia del parámetro, nunca `capacidadMinimaGb=0`.
  capacidadMinimaGb = 0, tamanioGabinete = '', tipoCooler = '', wattsMinimos = 0,
  // pc-builder-homelab (D3): '' es GAMING, el default de siempre — igual que
  // gama, sólo viaja cuando el pedido difiere del default (UsoWire.parse).
  uso = '',
} = {}) {
  const p = new URLSearchParams();
  if (presupuesto > 0) p.set('presupuesto', presupuesto);
  if (conGpu) p.set('conGpu', 'true');
  if (excluir.length > 0) p.set('excluir', excluir.join(','));
  if (gama) p.set('gama', gama);
  if (ddr) p.set('ddr', ddr);
  if (marcaCpu) p.set('marcaCpu', marcaCpu);
  if (marcaGpu) p.set('marcaGpu', marcaGpu);
  if (tipoAlmacenamiento) p.set('tipoAlmacenamiento', tipoAlmacenamiento);
  if (ramDual) p.set('ramDual', 'true');
  if (wifi) p.set('wifi', 'true');
  if (capacidadMinimaGb > 0) p.set('capacidadMinimaGb', capacidadMinimaGb);
  if (tamanioGabinete) p.set('tamanioGabinete', tamanioGabinete);
  if (tipoCooler) p.set('tipoCooler', tipoCooler);
  if (wattsMinimos > 0) p.set('wattsMinimos', wattsMinimos);
  if (uso) p.set('uso', uso);
  const qs = p.toString();
  const r = await authedFetch(`${BASE}/api/pcs/builder${qs ? `?${qs}` : ''}`);
  return softUnwrap(r);
}

/** null when the user never saved one (204) or on error. */
export async function fetchPcPreferencia() {
  const r = await authedFetch(`${BASE}/api/pcs/preferencia`);
  return softUnwrap(r);
}

export async function savePcPreferencia(body) {
  const r = await authedFetch(`${BASE}/api/pcs/preferencia`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return softUnwrap(r);
}

// ─── Saved PCs ──────────────────────────────────────────────────────────────

export async function savePc(body) {
  const r = await authedFetch(`${BASE}/api/pcs/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return softUnwrap(r);
}

export async function fetchSavedPcs() {
  const r = await authedFetch(`${BASE}/api/pcs/saved`);
  return softUnwrap(r, []);
}

export async function deleteSavedPc(id) {
  const r = await authedFetch(`${BASE}/api/pcs/saved/${id}`, { method: 'DELETE' });
  return r.ok;
}

export async function renamePc(id, nombre) {
  const r = await authedFetch(`${BASE}/api/pcs/saved/${id}/nombre`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nombre }),
  });
  return r.ok;
}

// ─── Cron Jobs (panel de administración /cronjobs) ───────────────────────────
// /executions ya trae logOutput embebido por fila.

export async function listCronJobs() {
  const r = await authedFetch(`${BASE}/api/cron`);
  return softUnwrap(r);
}

export async function getCronJob(id) {
  const r = await authedFetch(`${BASE}/api/cron/${id}`);
  return softUnwrap(r);
}

export async function createCronJob(job) {
  const r = await authedFetch(`${BASE}/api/cron`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(job),
  });
  return opResult(r);
}

export async function updateCronJob(id, job) {
  const r = await authedFetch(`${BASE}/api/cron/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(job),
  });
  return opResult(r);
}

export async function deleteCronJob(id) {
  const r = await authedFetch(`${BASE}/api/cron/${id}`, { method: 'DELETE' });
  return opResult(r);
}

// Fire-and-forget: 202 started, 409 scraper busy or job in flight, 404 job gone.
export async function runCronNow(id) {
  const r = await authedFetch(`${BASE}/api/cron/${id}/run-now`, { method: 'POST' });
  return opResult(r);
}

export async function fetchCronExecutions(id, limit = 50) {
  const p = new URLSearchParams({ limit });
  const r = await authedFetch(`${BASE}/api/cron/${id}/executions?${p}`);
  return softUnwrap(r);
}

// ─── LLM Catalog Agent (llm-catalog-nlp) ─────────────────────────────────────
// Read-only chat/model-discovery + the single out-of-loop write endpoint
// (applyProposal). askAgent/fetchAgentModels surface a 409 as
// { scraping: true } instead of throwing — the panel shows a clear
// "wait for the scrape to finish" message instead of a generic error.

export async function fetchAgentModels() {
  const r = await authedFetch(`${BASE}/api/agent/models`);
  return softUnwrap(r);
}

export async function askAgent(messages, model) {
  const r = await authedFetch(`${BASE}/api/agent/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(model ? { messages, model } : { messages }),
  });
  if (r.status === 409) return { scraping: true };
  try {
    return await unwrap(r);
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    return { error: true, mensaje: e.message || 'No se pudo consultar al agente.', codigo: e.code };
  }
}

export async function applyProposal(proposal) {
  const r = await authedFetch(`${BASE}/api/agent/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(proposal),
  });
  if (r.status === 409) return { scraping: true };
  try {
    return { ok: true, ...((await unwrap(r)) ?? {}) };
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    return { ok: false, mensaje: e.message, codigo: e.code, actual: e.details?.actual };
  }
}

// ─── Administración de cuentas (ADMIN) ──────────────────────────────────────
// Estas devuelven `body` en el éxito Y en el error (`{ error: code, mensaje }`), a
// diferencia del resto del archivo: el 409 `ultimo_admin` trae un mensaje que explica
// por qué se negó y qué hacer antes de reintentar; colapsarlo a null lo volvería mudo.
async function usuariosFetch(path, init) {
  const r = await authedFetch(`${BASE}/api/usuarios${path}`, init);
  try {
    return { ok: true, status: r.status, body: await unwrap(r) };
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    return { ok: false, status: r.status, body: { error: e.code, mensaje: e.message } };
  }
}

const JSON_HEADERS = { 'Content-Type': 'application/json' };

/** → `[{ id, username, email, activo, esServicio, roles: [...] }]`, o null. */
export async function fetchUsuarios() {
  const { ok, body } = await usuariosFetch('', undefined);
  return ok && Array.isArray(body) ? body : null;
}

export function crearUsuario({ username, password, email, role }) {
  return usuariosFetch('', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ username, password, email, role }),
  });
}

export function cambiarRolUsuario(username, role) {
  return usuariosFetch(`/${encodeURIComponent(username)}/rol`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ role }),
  });
}

/** Desactiva — el backend NO borra, para no arrastrar la auditoría. */
export function desactivarUsuario(username) {
  return usuariosFetch(`/${encodeURIComponent(username)}`, { method: 'DELETE' });
}

export function reactivarUsuario(username) {
  return usuariosFetch(`/${encodeURIComponent(username)}/activar`, { method: 'PUT' });
}
