import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { extname, resolve } from 'node:path';

import { LOGO_FILES_BY_NAME } from './brandLogos.generated.js';
import { SITIO_LOGO_MARCA, brandLogoFile } from './brandLogos.js';
import { LABELS } from './sitios.js';

const LOGOS = resolve(__dirname, '../../public/brand-logos');
const CONFIG = resolve(__dirname, '../../../scraper/src/main/resources/config.properties');
const ARREGLAR = 'Run `node scripts/fetch-site-logos.mjs` from the repo root (see public/brand-logos/README.md).';

// Stores whose WAF answers 403 to a script: the logo has to be dropped in by hand. Remove the key once it has one.
const SIN_LOGO_POR_WAF = new Set(['fullh4rd', 'venex']);

const tieneLogo = key => Boolean(brandLogoFile(SITIO_LOGO_MARCA[key] ?? key));

function sitiosDeConfig() {
  return [...readFileSync(CONFIG, 'utf8').matchAll(/^sitio\.([a-z0-9_]+)\.url=/gm)].map(m => m[1]);
}

describe('every site has a logo', () => {
  it('each key in LABELS resolves to a logo file', () => {
    const sin = Object.keys(LABELS).filter(k => !tieneLogo(k) && !SIN_LOGO_POR_WAF.has(k));

    expect(sin, `Sites without a logo: ${sin.join(', ')}. ${ARREGLAR}`).toEqual([]);
  });

  it('each site in config.properties has a label and a logo', () => {
    const sitios = sitiosDeConfig();
    const sinLabel = sitios.filter(k => !LABELS[k]);
    const sinLogo = sitios.filter(k => !tieneLogo(k) && !SIN_LOGO_POR_WAF.has(k));

    expect(sinLabel, `Add a LABELS entry in src/lib/sitios.js for: ${sinLabel.join(', ')}`).toEqual([]);
    expect(sinLogo, `Sites without a logo: ${sinLogo.join(', ')}. ${ARREGLAR}`).toEqual([]);
  });

  it('the allowlist only holds sites that still lack a logo', () => {
    const yaTienen = [...SIN_LOGO_POR_WAF].filter(tieneLogo);

    expect(yaTienen, `Remove from SIN_LOGO_POR_WAF: ${yaTienen.join(', ')}`).toEqual([]);
  });

  it('the generated manifest lists exactly the files in public/brand-logos', () => {
    const enDisco = readdirSync(LOGOS).filter(f => extname(f) && f !== 'README.md').sort();

    expect(Object.values(LOGO_FILES_BY_NAME).sort(), `Manifest out of date. ${ARREGLAR}`).toEqual(enDisco);
  });
});
