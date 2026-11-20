import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('hand tool sub-category chips', () => {
  it('lists the stored values V131 writes, in the rubro of the category', () => {
    expect(subChipsDe('construccion', 'Herramienta Manual')).toContain('Pinza y Alicate');
    expect(subChipsDe('construccion', 'Herramienta Manual')).toHaveLength(12);
  });

  it('finds a chip from the slug of its stored value, accents included', () => {
    const chips = subChipsDe('construccion', 'Herramienta Manual');
    expect(chipPorSlug(chips, 'albanileria')).toBe('Albañilería');
    expect(chipPorSlug(chips, 'medicion')).toBe('Medición');
    expect(chipPorSlug(chips, 'llave-y-tubo')).toBe('Llave y Tubo');
  });
});
