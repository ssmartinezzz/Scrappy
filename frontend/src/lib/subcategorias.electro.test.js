import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('electro and TV / audio sub-category chips', () => {
  it('lists the stored values V128 writes, in each category of its rubro', () => {
    expect(subChipsDe('electrodomesticos', 'Pequeño Electrodoméstico')).toContain('Cafetera');
    expect(subChipsDe('electrodomesticos', 'Pequeño Electrodoméstico')).toHaveLength(13);
    expect(subChipsDe('tecnologia', 'TV y Audio')).toContain('Barra de Sonido');
    expect(subChipsDe('tecnologia', 'TV y Audio')).toHaveLength(10);
  });

  it('moved the small appliances out of hogar and eight TV / audio subs into electrodomesticos', () => {
    expect(subChipsDe('hogar', 'Pequeño Electrodoméstico')).toBeUndefined();
    expect(subChipsDe('electrodomesticos', 'TV y Audio')).toEqual([
      'Barra de Sonido', 'Equipo de Música', 'Parlante', 'Proyector', 'Radio', 'Soporte', 'Tocadiscos', 'Televisor']);
  });

  it('keeps Handy and Consola y Mixer only under tecnologia, where the stores that still hold them are', () => {
    expect(subChipsDe('tecnologia', 'TV y Audio')).toEqual(expect.arrayContaining(['Handy', 'Consola y Mixer']));
    expect(subChipsDe('electrodomesticos', 'TV y Audio')).not.toEqual(expect.arrayContaining(['Handy']));
    expect(subChipsDe('electrodomesticos', 'TV y Audio')).not.toEqual(expect.arrayContaining(['Consola y Mixer']));
  });

  it('finds a chip from the slug of its stored value', () => {
    expect(chipPorSlug(subChipsDe('tecnologia', 'TV y Audio'), 'barra-de-sonido')).toBe('Barra de Sonido');
    expect(chipPorSlug(subChipsDe('electrodomesticos', 'Pequeño Electrodoméstico'), 'licuadora-y-exprimidor')).toBe('Licuadora y Exprimidor');
  });
});
