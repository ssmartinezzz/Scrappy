import { describe, expect, it } from 'vitest';
import { chipPorSlug, subChipsDe } from './subcategorias';

describe('Reloj sub-category chips', () => {
  it('lists the four stored values under tecnologia, lowercase as SubcategoryResolver writes them', () => {
    expect(subChipsDe('tecnologia', 'Reloj').map((chip) => chip.value)).toEqual(['deportivo', 'malla', 'smartwatch', 'analógico']);
    expect(subChipsDe('tecnologia', 'Reloj').map((chip) => chip.label)).toEqual(['Deportivo', 'Malla', 'Smartwatch', 'Analógico']);
  });

  it('gives the watches V143 sends to indumentaria their one chip', () => {
    expect(subChipsDe('indumentaria', 'Reloj').map((chip) => chip.value)).toEqual(['analógico']);
  });

  it('finds a chip from the slug of its stored value, accent included', () => {
    expect(chipPorSlug(subChipsDe('tecnologia', 'Reloj'), 'analogico').value).toBe('analógico');
    expect(chipPorSlug(subChipsDe('tecnologia', 'Reloj'), 'malla').value).toBe('malla');
  });
});
