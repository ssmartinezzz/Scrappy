// Store CDNs that can serve a resized product image. Any other host returns
// null and the caller keeps the original.

const ANY_WIDTH = [240, 360, 480, 720];

const RESIZERS = [
  {
    host: h => h === 'cdn.shopify.com',
    widths: ANY_WIDTH,
    resize: (u, w) => { u.searchParams.set('width', w); return u; },
  },
  {
    // /arquivos/ids/<id>[-W-H]/file.jpg
    host: h => h.endsWith('.vteximg.com.br'),
    widths: ANY_WIDTH,
    resize: (u, w) => {
      const id = /(\/arquivos\/ids\/\d+)(-\d+-[\da-z]+)?\//;
      if (!id.test(u.pathname)) return null;
      u.pathname = u.pathname.replace(id, `$1-${w}-auto/`);
      return u;
    },
  },
  {
    // /stores/.../products/<name>-1024-1024.webp; the 0 keeps the aspect ratio
    host: h => h.endsWith('.mitiendanube.com'),
    // Only these are published; any other width answers 403.
    widths: [240, 320, 480, 640],
    resize: (u, w) => {
      const size = /-\d+-\d+(\.\w+)$/;
      if (!u.pathname.includes('/products/') || !size.test(u.pathname)) return null;
      u.pathname = u.pathname.replace(size, `-${w}-0$1`);
      return u;
    },
  },
];

function resizerFor(url) {
  if (!url) return null;
  try { return RESIZERS.find(x => x.host(new URL(url).hostname)) ?? null; } catch { return null; }
}

export function thumbUrl(url, width) {
  const r = resizerFor(url);
  if (!r?.widths.includes(width)) return null;
  return r.resize(new URL(url), width)?.toString() ?? null;
}

export function thumbSrcSet(url) {
  const r = resizerFor(url);
  if (!r || !thumbUrl(url, r.widths[0])) return undefined;
  return r.widths.map(w => `${thumbUrl(url, w)} ${w}w`).join(', ');
}
