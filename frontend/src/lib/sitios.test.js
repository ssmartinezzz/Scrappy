import { describe, it, expect } from 'vitest';
import {
  sitioLabel, sitioLogoMarca, sitioLogoUrl, ordenarSitios, agruparPorRubro, filtrarSitios,
} from './sitios';

const S = (nombre, rubro) => ({ nombre, rubro });

describe('sitioLabel', () => {
  it('maps known keys', () => {
    expect(sitioLabel('dcshoes')).toBe('DC Shoes');
    expect(sitioLabel('burtonargentina')).toBe('Burton');
  });
  it('capitalizes unknown lowercase keys and keeps pretty names', () => {
    expect(sitioLabel('nuevositio')).toBe('Nuevositio');
    expect(sitioLabel('Mi Sitio')).toBe('Mi Sitio');
    expect(sitioLabel('MiSitio')).toBe('MiSitio');
  });
});

describe('logo lookup', () => {
  it('maps site keys to brand keys', () => {
    expect(sitioLogoMarca('underarmour')).toBe('under armour');
    expect(sitioLogoMarca('dcshoes')).toBe('dc');
    expect(sitioLogoMarca('nike')).toBe('nike');
  });
  it('returns null when the brand has no logo', () => {
    expect(sitioLogoUrl('sitio-sin-logo')).toBeNull();
  });
});

describe('ordenarSitios', () => {
  it('sorts by display label, accent/case-insensitively, without mutating', () => {
    const input = [S('vcp'), S('armytech'), S('ayrun'), S('Zentra')];
    const out = ordenarSitios(input);
    expect(out.map((s) => s.nombre)).toEqual(['armytech', 'ayrun', 'vcp', 'Zentra']);
    expect(input[0].nombre).toBe('vcp');
  });
});

describe('agruparPorRubro', () => {
  it('follows RUBROS order, unknown last, default indumentaria, sorted inside', () => {
    const out = agruparPorRubro([
      S('venex', 'tecnologia'), S('zzz', 'raro'), S('nike'), S('armytech', 'tecnologia'),
      S('entreno', 'suplementos'), S('ayrun', 'indumentaria'),
    ]);
    expect(out.map(([r]) => r)).toEqual(['indumentaria', 'tecnologia', 'suplementos', 'raro']);
    expect(out[0][1].map((s) => s.nombre)).toEqual(['ayrun', 'nike']);
    expect(out[1][1].map((s) => s.nombre)).toEqual(['armytech', 'venex']);
  });
});

describe('filtrarSitios', () => {
  const all = [S('dcshoes', 'indumentaria'), S('venex', 'tecnologia'), S('nike'), S('kingofthekongo')];
  it('matches label and key ignoring case and accents', () => {
    expect(filtrarSitios(all, { q: 'dc sho' }).map((s) => s.nombre)).toEqual(['dcshoes']);
    expect(filtrarSitios(all, { q: 'KINGOFTHE' }).map((s) => s.nombre)).toEqual(['kingofthekongo']);
    expect(filtrarSitios(all, { q: 'king of the' }).map((s) => s.nombre)).toEqual(['kingofthekongo']);
  });
  it('filters by vista', () => {
    const selected = ['nike', 'venex'];
    expect(filtrarSitios(all, { vista: 'elegidas', selected }).map((s) => s.nombre)).toEqual(['venex', 'nike']);
    expect(filtrarSitios(all, { vista: 'no-elegidas', selected }).map((s) => s.nombre)).toEqual(['dcshoes', 'kingofthekongo']);
  });
  it('filters by rubro, treating a missing rubro as indumentaria', () => {
    expect(filtrarSitios(all, { rubro: 'indumentaria' }).map((s) => s.nombre)).toEqual(['dcshoes', 'nike', 'kingofthekongo']);
    expect(filtrarSitios(all, { rubro: 'tecnologia' }).map((s) => s.nombre)).toEqual(['venex']);
  });
});
