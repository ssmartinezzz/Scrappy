import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('electro and TV / audio sub-category chips', () => {
  it('lists the stored values V128 writes, in each category of its rubro', () => {
    expect(subChipsDe('hogar', 'Pequeño Electrodoméstico')).toContain('Cafetera');
    expect(subChipsDe('hogar', 'Pequeño Electrodoméstico')).toHaveLength(13);
    expect(subChipsDe('tecnologia', 'TV y Audio')).toContain('Barra de Sonido');
    expect(subChipsDe('tecnologia', 'TV y Audio')).toHaveLength(10);
  });

  it('finds a chip from the slug of its stored value', () => {
    expect(chipPorSlug(subChipsDe('tecnologia', 'TV y Audio'), 'barra-de-sonido')).toBe('Barra de Sonido');
    expect(chipPorSlug(subChipsDe('hogar', 'Pequeño Electrodoméstico'), 'licuadora-y-exprimidor')).toBe('Licuadora y Exprimidor');
  });
});
