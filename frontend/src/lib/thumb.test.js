import { describe, expect, it } from 'vitest';

import { thumbSrcSet, thumbUrl } from '@/lib/thumb';

describe('thumbUrl', () => {
  it('asks Shopify for the width, keeping the cache-busting version', () => {
    // Vcp serves 2500x3750 originals (~0.9 MB) into a ~180px card.
    expect(thumbUrl('https://cdn.shopify.com/s/files/1/0045/files/18.jpg?v=1741375298', 480))
      .toBe('https://cdn.shopify.com/s/files/1/0045/files/18.jpg?v=1741375298&width=480');
  });

  it('replaces a width Shopify already carries instead of adding a second one', () => {
    expect(thumbUrl('https://cdn.shopify.com/s/files/1/a.png?width=1200&v=1', 240))
      .toBe('https://cdn.shopify.com/s/files/1/a.png?width=240&v=1');
  });

  it('puts the VTEX size in the image id, with auto height', () => {
    expect(thumbUrl('https://sporting.vteximg.com.br/arquivos/ids/1005141/4FA0066-632-1.jpg?v=638374590180070000', 360))
      .toBe('https://sporting.vteximg.com.br/arquivos/ids/1005141-360-auto/4FA0066-632-1.jpg?v=638374590180070000');
  });

  it('overwrites a VTEX id that is already sized', () => {
    expect(thumbUrl('https://x.vteximg.com.br/arquivos/ids/77-1000-1000/a.jpg', 240))
      .toBe('https://x.vteximg.com.br/arquivos/ids/77-240-auto/a.jpg');
  });

  it('swaps the Tiendanube size suffix for width-by-auto', () => {
    expect(thumbUrl('https://acdn-us.mitiendanube.com/stores/006/011/728/products/shaker-1c616d-1024-1024.webp', 480))
      .toBe('https://acdn-us.mitiendanube.com/stores/006/011/728/products/shaker-1c616d-480-0.webp');
  });

  it('keeps a Tiendanube url that is already at the asked width', () => {
    // Some stores already publish their listing image as -480-0.
    expect(thumbUrl('https://acdn-us.mitiendanube.com/stores/1/products/a-480-0.webp', 480))
      .toBe('https://acdn-us.mitiendanube.com/stores/1/products/a-480-0.webp');
  });

  it('keeps a VTEX url that is already at the asked width', () => {
    expect(thumbUrl('https://x.vteximg.com.br/arquivos/ids/77-480-auto/a.jpg', 480))
      .toBe('https://x.vteximg.com.br/arquivos/ids/77-480-auto/a.jpg');
  });

  it('returns null for a width Tiendanube does not publish (it answers 403)', () => {
    // Its CDN only renders 50, 100, 240, 320, 480 and 640 wide.
    expect(thumbUrl('https://acdn-us.mitiendanube.com/stores/1/products/a-1024-1024.webp', 360)).toBeNull();
    expect(thumbUrl('https://acdn-us.mitiendanube.com/stores/1/products/a-1024-1024.webp', 720)).toBeNull();
  });

  it('leaves the Tiendanube no-photo placeholder alone: it has no sized variants', () => {
    expect(thumbUrl('https://acdn-us.mitiendanube.com/assets/stores/img/no-photo-1024-1024.webp', 480)).toBeNull();
  });

  it('returns null for hosts that cannot resize, so the caller keeps the original', () => {
    expect(thumbUrl('https://fullh4rd.com.ar/img/productos/1/micro-0.jpg', 480)).toBeNull();
    expect(thumbUrl('https://www.maximus.com.ar/Temp/App_WebSite/App_PictureFiles/Items/06-006-3M_600.jpg', 480)).toBeNull();
  });

  it('returns null for a missing or unparseable url', () => {
    expect(thumbUrl(undefined, 480)).toBeNull();
    expect(thumbUrl('', 480)).toBeNull();
    expect(thumbUrl('not a url', 480)).toBeNull();
  });
});

describe('thumbSrcSet', () => {
  it('lists every thumbnail width with its descriptor', () => {
    expect(thumbSrcSet('https://cdn.shopify.com/s/files/1/a.jpg'))
      .toBe('https://cdn.shopify.com/s/files/1/a.jpg?width=240 240w, '
        + 'https://cdn.shopify.com/s/files/1/a.jpg?width=360 360w, '
        + 'https://cdn.shopify.com/s/files/1/a.jpg?width=480 480w, '
        + 'https://cdn.shopify.com/s/files/1/a.jpg?width=720 720w');
  });

  it('offers Tiendanube only the widths its CDN publishes', () => {
    expect(thumbSrcSet('https://acdn-us.mitiendanube.com/stores/1/products/a-1024-1024.webp'))
      .toBe('https://acdn-us.mitiendanube.com/stores/1/products/a-240-0.webp 240w, '
        + 'https://acdn-us.mitiendanube.com/stores/1/products/a-320-0.webp 320w, '
        + 'https://acdn-us.mitiendanube.com/stores/1/products/a-480-0.webp 480w, '
        + 'https://acdn-us.mitiendanube.com/stores/1/products/a-640-0.webp 640w');
  });

  it('is undefined for a host that cannot resize, so React renders no srcset', () => {
    expect(thumbSrcSet('https://fullh4rd.com.ar/img/a.jpg')).toBeUndefined();
  });
});
