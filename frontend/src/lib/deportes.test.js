import { describe, expect, it } from 'vitest';

import { deporteLabel } from './deportes';

describe('deporteLabel', () => {
  it('maps every backend code to its Spanish label', () => {
    expect(deporteLabel('futbol')).toBe('Fútbol');
    expect(deporteLabel('natacion')).toBe('Natación');
    expect(deporteLabel('basquet')).toBe('Básquet');
    expect(deporteLabel('voley')).toBe('Vóley');
    expect(deporteLabel('combate')).toBe('Combate');
  });

  it('falls back to the raw code for an unknown sport', () => {
    expect(deporteLabel('surf')).toBe('surf');
  });
});
