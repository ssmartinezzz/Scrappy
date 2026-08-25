import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

import { resetSession } from '../lib/authSession';

// React Testing Library does not auto-clean when `globals` is on in some
// setups; unmounting explicitly keeps one test's DOM out of the next one's
// queries, which otherwise produces "found multiple elements" failures that
// look like component bugs.
//
// resetSession() belongs here too: authSession.js's token/nonce/identity are
// module-scoped `let`s (frontend-auth-ui design D3), and `vi.restoreAllMocks()`
// does NOT reset a module-level `let` — without this, a token seeded by one
// test file leaks into the next and the leak looks like a bug somewhere else
// entirely.
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  resetSession();
});

// jsdom no implementa ResizeObserver, y `ResponsiveContainer` de Recharts lo
// usa para medirse. Sin esto, cualquier test que renderice un gráfico tira
// "ResizeObserver is not defined" desde un passive effect — un error que no
// señala al componente y se lee como si el gráfico estuviera roto.
//
// No alcanza con un stub vacío: jsdom no hace layout, así que el contenedor
// mide 0x0 y `ResponsiveContainer` decide, correctamente, que no hay lugar
// para dibujar — el gráfico queda montado pero VACÍO, y el test falla igual
// aunque el componente esté bien. El stub reporta un tamaño fijo para que
// Recharts tenga dónde renderizar.
//
// Ese tamaño es una convención del entorno de test, no una aserción: los tests
// afirman que el gráfico SE MONTA con la serie que le pasaron, nunca cómo se
// ve. Medir píxeles acá sería medir la ficción, no el componente.
const TEST_CHART_SIZE = { width: 800, height: 300 };

if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    constructor(callback) { this.callback = callback; }
    observe(target) {
      this.callback([{ target, contentRect: { ...TEST_CHART_SIZE, top: 0, left: 0 } }], this);
    }
    unobserve() {}
    disconnect() {}
  };
}

// jsdom doesn't implement IntersectionObserver either — ProductGrid's
// infinite-scroll sentinel uses it (frontend-auth-ui Phase 7: the first test
// suite to mount AppLayout/ProductGrid together surfaced this gap). A no-op
// stub is correct here: these tests assert role-aware visibility, not scroll
// behaviour, so the sentinel simply never fires.
if (typeof globalThis.IntersectionObserver === 'undefined') {
  globalThis.IntersectionObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

// ResponsiveContainer también consulta el box del nodo directamente.
if (!HTMLElement.prototype.getBoundingClientRect.__chartStub) {
  const stub = function () {
    return { ...TEST_CHART_SIZE, top: 0, left: 0, right: TEST_CHART_SIZE.width,
             bottom: TEST_CHART_SIZE.height, x: 0, y: 0, toJSON: () => ({}) };
  };
  stub.__chartStub = true;
  HTMLElement.prototype.getBoundingClientRect = stub;
}
