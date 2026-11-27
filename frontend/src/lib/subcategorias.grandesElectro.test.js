import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('V144 appliance sub-category chips', () => {
  it('lists the stored values of each appliance category, under electrodomesticos', () => {
    expect(subChipsDe('electrodomesticos', 'Lavarropas')).toEqual(['Lavarropas', 'Lavavajillas', 'Secarropas']);
    expect(subChipsDe('electrodomesticos', 'Cocina y Horno')).toEqual(['Cocina', 'Horno', 'Microondas', 'Anafe']);
    expect(subChipsDe('electrodomesticos', 'Heladera')).toEqual(['Heladera', 'Freezer', 'Cava', 'Exhibidora']);
    expect(subChipsDe('electrodomesticos', 'Calefactor')).toEqual(
      ['Caloventor', 'Convector', 'Estufa', 'Panel', 'Radiador', 'Tiro Balanceado', 'Sin Salida']);
    expect(subChipsDe('electrodomesticos', 'Termotanque')).toEqual(['Eléctrico', 'A Gas']);
  });

  it('has no chips for them under hogar, where they lived before V139, nor for the categories that carry no sub', () => {
    for (const categoria of ['Lavarropas', 'Cocina y Horno', 'Heladera', 'Calefactor', 'Termotanque']) {
      expect(subChipsDe('hogar', categoria)).toBeUndefined();
    }
    expect(subChipsDe('juguetes', 'Artículo de Bebé')).toBeUndefined();
    expect(subChipsDe('belleza', 'Salud')).toBeUndefined();
    expect(subChipsDe('oficina', 'Librería')).toBeUndefined();
  });

  it('finds a chip from the slug of its stored value, accents and spaces included', () => {
    expect(chipPorSlug(subChipsDe('electrodomesticos', 'Calefactor'), 'tiro-balanceado')).toBe('Tiro Balanceado');
    expect(chipPorSlug(subChipsDe('electrodomesticos', 'Termotanque'), 'electrico')).toBe('Eléctrico');
    expect(chipPorSlug(subChipsDe('electrodomesticos', 'Termotanque'), 'a-gas')).toBe('A Gas');
  });
});
