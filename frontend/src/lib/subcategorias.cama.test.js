import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('bedding and mattress sub-category chips', () => {
  it('lists the stored values V130 writes, in the hogar rubro', () => {
    expect(subChipsDe('hogar', 'Ropa de Cama')).toContain('Juego de Sábanas');
    expect(subChipsDe('hogar', 'Ropa de Cama')).toHaveLength(8);
    expect(subChipsDe('hogar', 'Colchón')).toEqual(['1 Plaza', '1 1/2 Plaza', '2 Plazas', '2 1/2 Plazas', 'Queen', 'King']);
  });

  it('gives each size its own slug, so 1 Plaza and 1 1/2 Plaza do not collide', () => {
    const chips = subChipsDe('hogar', 'Colchón');
    expect(new Set(chips.map(chip => chip.toLowerCase().replace(/[^a-z0-9]+/g, '-'))).size).toBe(chips.length);
    expect(chipPorSlug(chips, '1-plaza')).toBe('1 Plaza');
    expect(chipPorSlug(chips, '1-1-2-plaza')).toBe('1 1/2 Plaza');
    expect(chipPorSlug(subChipsDe('hogar', 'Ropa de Cama'), 'frazada-y-manta')).toBe('Frazada y Manta');
  });
});
