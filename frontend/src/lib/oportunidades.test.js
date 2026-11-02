import { describe, expect, it } from 'vitest';
import { EXCLUIR_CATEGORIAS, rubroDeQuery, rutaOportunidades } from './oportunidades';

describe('oportunidades helpers', () => {
  it('the fixed junk exclusion is Otros and Alimentos', () => {
    expect(EXCLUIR_CATEGORIAS).toEqual(['Otros', 'Alimentos']);
  });

  it('rubroDeQuery accepts only the rubro vocabulary', () => {
    expect(rubroDeQuery(new URLSearchParams('rubro=tecnologia'))).toBe('tecnologia');
    expect(rubroDeQuery(new URLSearchParams('rubro=bogus'))).toBe('');
    expect(rubroDeQuery(new URLSearchParams(''))).toBe('');
  });

  it('rutaOportunidades keeps ?rubro= only when a rubro is selected', () => {
    expect(rutaOportunidades('')).toBe('/analisis/oportunidades');
    expect(rutaOportunidades('hogar')).toBe('/analisis/oportunidades?rubro=hogar');
    expect(rutaOportunidades('hogar', 'all_time_low')).toBe('/analisis/oportunidades/all_time_low?rubro=hogar');
    expect(rutaOportunidades('', 'all_time_low')).toBe('/analisis/oportunidades/all_time_low');
  });
});
