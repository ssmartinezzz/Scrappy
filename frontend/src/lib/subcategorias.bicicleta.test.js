import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('bicycle sub-category chips', () => {
  it('lists the stored values V132 writes, in the deporte rubro', () => {
    const chips = subChipsDe('deporte', 'Bicicleta');
    expect(chips).toContain('Mountain Bike');
    expect(chips).toHaveLength(10);
  });

  it('finds a chip from the slug of its stored value, slashes and accents included', () => {
    const chips = subChipsDe('deporte', 'Bicicleta');
    expect(chipPorSlug(chips, 'urbana-fixie')).toBe('Urbana / Fixie');
    expect(chipPorSlug(chips, 'electrica')).toBe('Eléctrica');
    expect(chipPorSlug(chips, 'paseo-y-playera')).toBe('Paseo y Playera');
  });
});
