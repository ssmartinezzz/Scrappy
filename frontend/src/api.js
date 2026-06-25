const BASE = '';

export async function fetchStatus() {
  const r = await fetch(`${BASE}/api/status`);
  return r.ok ? r.json() : null;
}

export async function startScrape({ precioMin, precioMax, sitios, forceRetrain = false }) {
  const p = new URLSearchParams({ precioMin, precioMax });
  sitios.forEach(s => p.append('sitios', s));
  if (forceRetrain) p.set('forceRetrain', 'true');
  const r = await fetch(`${BASE}/api/scrape?${p}`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

export async function limpiarCatalogo() {
  return fetch(`${BASE}/api/db/productos`, { method: 'DELETE' });
}

export async function limpiarMl() {
  return fetch(`${BASE}/api/db/ml`, { method: 'DELETE' });
}

export async function fetchData(filters) {
  const p = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (Array.isArray(v)) v.forEach(vi => p.append(k, vi));
    else if (v !== '' && v !== null && v !== undefined) p.set(k, String(v));
  });
  const r = await fetch(`${BASE}/api/data?${p}`);
  return r.ok ? r.json() : null;
}

export async function deleteProducto(url) {
  const r = await fetch(`${BASE}/api/data?url=${encodeURIComponent(url)}`, { method: 'DELETE' });
  return r.ok;
}

export async function fetchFacets() {
  const r = await fetch(`${BASE}/api/facets`);
  return r.ok ? r.json() : null;
}

export async function fetchTendencias() {
  try {
    const r = await fetch(`${BASE}/api/tendencias`);
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
  const r = await fetch(`${BASE}/api/historial?url=${encodeURIComponent(url)}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

export async function fetchSitios() {
  const r = await fetch(`${BASE}/api/sitios`);
  return r.ok ? r.json() : null;
}

export async function addSitio(body) {
  const r = await fetch(`${BASE}/api/sitios`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  return r.ok;
}

export async function deleteSitio(nombre) {
  const r = await fetch(`${BASE}/api/sitios/${encodeURIComponent(nombre)}`, { method: 'DELETE' });
  return r.ok;
}

export async function updateConfig(cfg) {
  const r = await fetch(`${BASE}/api/config`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg)
  });
  return r.ok;
}

// ─── Presets de financiación ─────────────────────────────────────────────────

export async function fetchFinanciacionPresets() {
  const r = await fetch(`${BASE}/api/financiacion/presets`);
  return r.ok ? r.json() : null;
}

export async function crearFinanciacionPreset({ label, recargoPct, cuotas }) {
  const r = await fetch(`${BASE}/api/financiacion/presets`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label, recargoPct, cuotas }),
  });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function editarFinanciacionPreset(id, { label, recargoPct, cuotas }) {
  const r = await fetch(`${BASE}/api/financiacion/presets/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ label, recargoPct, cuotas }),
  });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function activarFinanciacionPreset(id) {
  const r = await fetch(`${BASE}/api/financiacion/presets/${id}/activar`, { method: 'PUT' });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export async function eliminarFinanciacionPreset(id) {
  const r = await fetch(`${BASE}/api/financiacion/presets/${id}`, { method: 'DELETE' });
  return r.json().catch(() => ({ ok: false, mensaje: 'Error de red' }));
}

export function fmt(n) {
  if (!n && n !== 0) return '—';
  return Number(n).toLocaleString('es-AR', { maximumFractionDigits: 0 });
}

export const BADGE_LABELS = {
  precio_historico_bajo: '🏆 Mínimo histórico',
  precio_bajo:          '💚 Precio Bajo',
  oferta_real:          '✅ Oferta Real',
  tendencia:            '🔥 Tendencia',
  precio_bajando:       '📉 Bajando',
  descuento_cosmetico:  '⚠️ Desc. Cosmético',
  precio_alto:          '📈 Precio Alto',
};

export async function buscarExterno(nombre, productoUrl) {
  const p = new URLSearchParams({ q: nombre });
  if (productoUrl) p.set('url', productoUrl);
  const r = await fetch(`/api/buscar-externo?${p}`);
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

// ─── DB backup ───────────────────────────────────────────────────────────────

export function exportarDB() {
  // Trigger browser download of scraper.db via anchor
  const a = document.createElement('a');
  a.href = '/api/db/export';
  a.download = `scraper-${new Date().toISOString().slice(0,10)}.db`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

export async function importarDB(file) {
  const form = new FormData();
  form.append('file', file);
  const r = await fetch('/api/db/import', { method: 'POST', body: form });
  return r.ok ? r.json() : null;
}

export async function fetchGrupos(filters = {}) {
  const p = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== '' && v != null) p.set(k, v);
  });
  const r = await fetch(`/api/grupos?${p}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

export async function fetchMejores(rubro = '') {
  const p = new URLSearchParams();
  if (rubro) p.set('rubro', rubro);
  const r = await fetch(`/api/mejores?${p}`);
  if (r.status === 204) return [];
  return r.ok ? r.json() : [];
}

export async function fetchMarcasBrowser(params = {}) {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v) p.set(k, v); });
  const r = await fetch(`/api/marcas-browser?${p}`);
  if (r.status === 204) return [];
  return r.ok ? r.json() : [];
}

// ─── ML Training ─────────────────────────────────────────────────────────────
export async function fetchMlEstado() {
  const r = await fetch('/api/ml/estado');
  return r.ok ? r.json() : null;
}

export async function startMlTraining(images = false, epochs = 8) {
  const p = new URLSearchParams({ images, epochs });
  const r = await fetch(`/api/ml/entrenar?${p}`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

export async function fetchMlResultado() {
  const r = await fetch('/api/ml/resultado');
  return r.ok ? r.json() : null;
}

export async function aplicarModeloML() {
  const r = await fetch('/api/ml/aplicar', { method: 'POST' });
  return r.ok ? r.json() : null;
}

export async function renormalizarCatalogo() {
  const r = await fetch('/api/ml/renormalizar', { method: 'POST' });
  return r.ok ? r.json() : null;
}

// ─── Favoritos ─────────────────────────────────────────────────────────────

export async function fetchFavoritos() {
  const r = await fetch(`${BASE}/api/favoritos`);
  return r.ok ? r.json() : [];
}

export async function addFavorito({ url, sitio, nombre }) {
  const r = await fetch(`${BASE}/api/favoritos`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, sitio, nombre })
  });
  return r.ok;
}

export async function removeFavorito(url) {
  const r = await fetch(`${BASE}/api/favoritos?url=${encodeURIComponent(url)}`, { method: 'DELETE' });
  return r.ok;
}

export async function rescrapeFavoritos() {
  const r = await fetch(`${BASE}/api/favoritos/rescrape`, { method: 'POST' });
  return r.ok ? r.json() : null;
}

// ─── Outfits (armador Gym) ───────────────────────────────────────────────────

export async function fetchOutfit(genero) {
  const p = new URLSearchParams();
  if (genero) p.set('genero', genero);
  const r = await fetch(`${BASE}/api/outfits?${p}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

// body shape: { genero, items: [{ slot, url, liked }] } — one POST per rated item (per-item feedback contract).
export async function sendOutfitFeedback(body) {
  const r = await fetch(`${BASE}/api/outfits/feedback`, {
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
  const r = await fetch(`${BASE}/api/recomendados?${p}`);
  if (r.status === 204) return null;
  return r.ok ? r.json() : null;
}

// body shape: { genero, items: [{ url, liked }] } — per-card like/dislike,
// writes to the same shared taste signal store as sendOutfitFeedback().
export async function sendRecomendadosFeedback(genero, items) {
  const r = await fetch(`${BASE}/api/recomendados/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ genero, items })
  });
  return r.ok ? r.json() : null;
}

export async function dismissCategoria(categoria) {
  const r = await fetch(`${BASE}/api/recomendados/dismiss-categoria`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ categoria })
  });
  return r.ok ? r.json() : null;
}

export async function undismissCategoria(categoria) {
  const r = await fetch(`${BASE}/api/recomendados/dismiss-categoria?categoria=${encodeURIComponent(categoria)}`,
    { method: 'DELETE' });
  return r.ok ? r.json() : null;
}
