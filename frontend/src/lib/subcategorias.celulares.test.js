import { describe, expect, it } from 'vitest';
import { chipPorSlug, subChipsDe } from './subcategorias';

describe('phone sub-category chips', () => {
  it('lists the stored values of Celular under the celulares rubro, power banks included', () => {
    expect(subChipsDe('celulares', 'Celular')).toEqual(
      ['Smartphone', 'Funda', 'Protector', 'Cargador', 'Batería Externa', 'Cable', 'Soporte', 'Repuesto']);
  });

  it('has no Celular chips under tecnologia: the category left that rubro', () => {
    expect(subChipsDe('tecnologia', 'Celular')).toBeUndefined();
  });

  it('finds a chip from the slug of its stored value', () => {
    expect(chipPorSlug(subChipsDe('celulares', 'Celular'), 'bateria-externa')).toBe('Batería Externa');
  });
});
