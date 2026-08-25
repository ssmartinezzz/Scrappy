import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { PRECIO_MIN_DEFAULT, PRECIO_MAX_DEFAULT, CONFIG_DEFAULT } from './scrapeDefaults';

describe('scrapeDefaults', () => {
  it('la banda por defecto expone las claves que usa la UI', () => {
    expect(CONFIG_DEFAULT).toEqual({ precioMin: 0, precioMax: 5000000 });
  });

  it('es inmutable: nadie puede pisar el default compartido por accidente', () => {
    expect(Object.isFrozen(CONFIG_DEFAULT)).toBe(true);
  });

  // El test que importa: el número del frontend y el de config.properties son
  // el MISMO valor escrito en dos lenguajes distintos. Si se separan, la UI
  // lanza scrapes con una banda y el backend filtra con otra — sin error, sólo
  // productos que el scraper trae y el filtro descarta.
  it('coincide con precio.maximo/precio.minimo de config.properties', () => {
    // vitest corre con cwd = frontend/, así que el backend está un nivel arriba.
    const ruta = resolve(process.cwd(), '../scraper/src/main/resources/config.properties');
    expect(existsSync(ruta), `config.properties encontrado en ${ruta}`).toBe(true);
    const props = readFileSync(ruta, 'utf8');
    const leer = (clave) => {
      const m = props.match(new RegExp(`^${clave.replace('.', '\\.')}=(\\S+)$`, 'm'));
      expect(m, `${clave} presente en config.properties`).toBeTruthy();
      return Number(m[1]);
    };
    expect(leer('precio.maximo')).toBe(PRECIO_MAX_DEFAULT);
    expect(leer('precio.minimo')).toBe(PRECIO_MIN_DEFAULT);
  });
});
