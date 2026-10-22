import { describe, expect, it } from 'vitest';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

import { BRAND_LOGO_FILES, getBrandLogoUrl, logoSobreOscuro } from './brandLogos';

const PUBLIC_LOGOS = resolve(__dirname, '../../public/brand-logos');

describe('getBrandLogoUrl', () => {
  it('serves a brand logo from the app itself, never from a third-party host', () => {
    const url = getBrandLogoUrl('Nike');

    expect(url).toMatch(/\/brand-logos\/nike\.(svg|png|webp)$/);
    expect(url).not.toMatch(/^https?:/);
  });

  it('matches the brand name case- and space-insensitively', () => {
    expect(getBrandLogoUrl('  under ARMOUR ')).toBe(getBrandLogoUrl('Under Armour'));
  });

  it('resolves an own-label store, whose brand is the store name as-is', () => {
    expect(getBrandLogoUrl('Monkyforce')).toMatch(/\/brand-logos\/monkyforce\./);
  });

  it('returns null for a brand without a logo, so the card falls back to initials', () => {
    expect(getBrandLogoUrl('Marca Que No Existe')).toBeNull();
    expect(getBrandLogoUrl('')).toBeNull();
    expect(getBrandLogoUrl(null)).toBeNull();
  });

  it('every mapped file exists in public/brand-logos', () => {
    const faltantes = Object.values(BRAND_LOGO_FILES).filter(f => !existsSync(resolve(PUBLIC_LOGOS, f)));

    expect(faltantes).toEqual([]);
  });
});

describe('logoSobreOscuro', () => {
  it('flags white marks for a dark plate and leaves dark ones on light', () => {
    expect(logoSobreOscuro('Labs Nutrition')).toBe(true);
    expect(logoSobreOscuro('Nike')).toBe(false);
  });
});
