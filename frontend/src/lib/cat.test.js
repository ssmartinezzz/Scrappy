import { describe, expect, it, vi } from 'vitest';

import { canonicalFromSlug, normCat, slugify } from '@/lib/cat';

describe('normCat', () => {
  it('mirrors the ML pipeline key format: lowercase and accent-stripped', () => {
    // catStats keys arrive from ml_pipeline.py already normalised. A mismatch
    // here means every lookup silently misses and the price bar never renders.
    expect(normCat('Medias')).toBe('medias');
    expect(normCat('Camperas de Algodón')).toBe('camperas de algodon');
    expect(normCat('Niños')).toBe('ninos');
  });

  it('strips every accented vowel the pipeline handles', () => {
    expect(normCat('á é í ó ú ü')).toBe('a e i o u u');
  });

  it('falls back to "general" for empty input rather than an empty key', () => {
    expect(normCat('')).toBe('general');
    expect(normCat(null)).toBe('general');
    expect(normCat(undefined)).toBe('general');
  });

  it('accepts non-string input without throwing', () => {
    expect(normCat(42)).toBe('42');
  });
});

describe('slugify', () => {
  it('collapses non-alphanumeric runs into a single dash', () => {
    expect(slugify('Ropa Interior / Medias')).toBe('ropa-interior-medias');
  });

  it('does not leave leading or trailing dashes', () => {
    expect(slugify('  ¡Ofertas!  ')).toBe('ofertas');
  });

  it('stays consistent with normCat, since both feed the same lookup', () => {
    expect(slugify('Camperas de Algodón')).toBe('camperas-de-algodon');
  });
});

describe('canonicalFromSlug', () => {
  const cats = [{ categoria: 'Medias' }, { categoria: 'Camperas de Algodón' }];

  it('resolves a slug back to its canonical entry', () => {
    expect(canonicalFromSlug('camperas-de-algodon', cats)).toEqual({
      categoria: 'Camperas de Algodón',
    });
  });

  it('returns null for an unknown slug', () => {
    expect(canonicalFromSlug('no-existe', cats)).toBeNull();
  });

  it('returns null when the category list is missing or not an array', () => {
    expect(canonicalFromSlug('medias', null)).toBeNull();
    expect(canonicalFromSlug('medias', undefined)).toBeNull();
    expect(canonicalFromSlug('', cats)).toBeNull();
  });

  it('warns on a slug collision instead of resolving it silently', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const colliding = [{ categoria: 'Buzos/Hoodies' }, { categoria: 'Buzos Hoodies' }];

    expect(canonicalFromSlug('buzos-hoodies', colliding))
      .toEqual({ categoria: 'Buzos/Hoodies' });
    expect(warn).toHaveBeenCalledOnce();
  });
});
