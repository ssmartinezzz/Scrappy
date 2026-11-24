import { describe, expect, it } from 'vitest';

import { chipPorSlug, subChipsDe } from './subcategorias';

describe('gaming sub-category chips', () => {
  it('lists the platforms V135 writes for Consola and Videojuego', () => {
    expect(subChipsDe('tecnologia', 'Consola').map(chip => chip.value)).toEqual(
      ['playstation', 'xbox', 'nintendo switch', 'nintendo', 'pc portátil', 'emulador portátil', 'emulador tv', 'retro']);
    expect(subChipsDe('tecnologia', 'Videojuego').map(chip => chip.value)).toEqual(
      ['ps5', 'ps4', 'playstation retro', 'switch 2', 'switch', 'xbox', 'nintendo 3ds', 'wii', 'pc', 'retro']);
  });

  it('finds a chip from the slug of its stored value', () => {
    expect(chipPorSlug(subChipsDe('tecnologia', 'Consola'), 'pc-portatil')?.label).toBe('PC portátil');
    expect(chipPorSlug(subChipsDe('tecnologia', 'Videojuego'), 'switch-2')?.label).toBe('Switch 2');
  });

  it('keeps the two Joystick chips: the arcade stick sub was not worth a chip (3 rows)', () => {
    expect(subChipsDe('tecnologia', 'Joystick').map(chip => chip.value)).toEqual(['gamepad', 'volante']);
  });
});
