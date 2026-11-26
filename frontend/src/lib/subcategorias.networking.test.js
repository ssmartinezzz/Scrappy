import { describe, expect, it } from 'vitest';
import { chipPorSlug, subChipsDe } from './subcategorias';

describe('Networking sub-category chips', () => {
  it('lists the stored values of Networking under tecnologia, in the order of its rules', () => {
    expect(subChipsDe('tecnologia', 'Networking')).toEqual(
      ['Router', 'Mesh', 'Access Point', 'Switch', 'Repetidor', 'Placa y Adaptador', 'Cable de Red', 'Conector']);
  });

  it('has no Red chips: the category was renamed', () => {
    expect(subChipsDe('tecnologia', 'Red')).toBeUndefined();
  });

  it('Cable lost its red chip, the network cables are Networking now', () => {
    expect(subChipsDe('tecnologia', 'Cable').map(chip => chip.value)).toEqual(['poder', 'usb', 'video']);
  });

  it('finds a chip from the slug of its stored value', () => {
    expect(chipPorSlug(subChipsDe('tecnologia', 'Networking'), 'placa-y-adaptador')).toBe('Placa y Adaptador');
  });
});
