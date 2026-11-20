import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('lighting and furniture sub-category chips', () => {
  it('lists the stored values V129 writes, in the rubro of each category', () => {
    expect(subChipsDe('construccion', 'Iluminación')).toContain('Lámpara de Pie');
    expect(subChipsDe('construccion', 'Iluminación')).toHaveLength(14);
    expect(subChipsDe('hogar', 'Mueble')).toContain('Bajo Mesada');
    expect(subChipsDe('hogar', 'Mueble')).toHaveLength(13);
  });

  it('finds a chip from the slug of its stored value, accents and spaces included', () => {
    expect(chipPorSlug(subChipsDe('construccion', 'Iluminación'), 'lampara-de-pie')).toBe('Lámpara de Pie');
    expect(chipPorSlug(subChipsDe('construccion', 'Iluminación'), 'plafon')).toBe('Plafón');
    expect(chipPorSlug(subChipsDe('hogar', 'Mueble'), 'rack-de-tv')).toBe('Rack de TV');
  });
});
