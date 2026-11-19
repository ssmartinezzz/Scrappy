import { describe, expect, it } from 'vitest';

import { CABELLO_SUBCATEGORIAS, MAQUILLAJE_SUBCATEGORIAS, PERFUME_SUBCATEGORIAS, PIEL_SUBCATEGORIAS } from './belleza';

describe('belleza sub-category chips', () => {
  it('lists the stored values the classifier writes (V113)', () => {
    expect(PERFUME_SUBCATEGORIAS).toContain('Extracto');
    expect(MAQUILLAJE_SUBCATEGORIAS).toEqual(['Labios', 'Ojos', 'Rostro', 'Uñas']);
    expect(PIEL_SUBCATEGORIAS).toEqual(['Corporal', 'Facial', 'Protector Solar']);
    expect(CABELLO_SUBCATEGORIAS).toEqual(['Acondicionador', 'Shampoo', 'Tintura', 'Tratamiento']);
  });
});
