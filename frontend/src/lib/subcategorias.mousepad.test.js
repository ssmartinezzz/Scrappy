import { describe, expect, it } from 'vitest';
import { chipPorSlug, subChipsDe } from './subcategorias';

describe('Mousepad y Mat and Escritorio sub-category chips', () => {
  it('lists the stored values of Mousepad y Mat under oficina, in the order of its rules', () => {
    expect(subChipsDe('oficina', 'Mousepad y Mat')).toEqual(
      ['Antifatiga', 'Apoyamuñecas', 'Mousepad Ergonómico', 'Mat de Escritorio', 'Mousepad RGB', 'Mousepad XL', 'Mousepad']);
  });

  it('has no chips under tecnologia or under the old names: the categories were merged and moved', () => {
    expect(subChipsDe('tecnologia', 'Mousepad y Mat')).toBeUndefined();
    expect(subChipsDe('tecnologia', 'Mousepad')).toBeUndefined();
    expect(subChipsDe('oficina', 'Mat Escritorio')).toBeUndefined();
  });

  it('gives Escritorio only the Motorizado chip', () => {
    expect(subChipsDe('oficina', 'Escritorio')).toEqual(['Motorizado']);
  });

  it('finds a chip from the slug of its stored value', () => {
    expect(chipPorSlug(subChipsDe('oficina', 'Mousepad y Mat'), 'apoyamunecas')).toBe('Apoyamuñecas');
    expect(chipPorSlug(subChipsDe('oficina', 'Escritorio'), 'motorizado')).toBe('Motorizado');
  });
});
