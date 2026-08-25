import { describe, it, expect } from 'vitest';
import { RUBROS, RUBRO_KEYS, rubroLabel } from './rubros';

// add-inpro-office-store: la lista de rubros vivía copiada, palabra por palabra,
// en Topbar.jsx, PicksPanel.jsx y MarcasPanel.jsx. Con tres copias, agregar un
// rubro y olvidarse de una deja un filtro que existe en el backend y es
// inalcanzable desde media UI — sin ningún error, sólo productos que no
// aparecen. Este test existe para que la lista tenga un solo dueño.

describe('RUBROS', () => {
  it('incluye el rubro vacío como "todos" y los cuatro rubros reales', () => {
    expect(RUBRO_KEYS).toEqual(['', 'indumentaria', 'tecnologia', 'suplementos', 'oficina']);
  });

  it('el primero es el neutro: sin clave, para no filtrar nada', () => {
    expect(RUBROS[0].key).toBe('');
  });

  it('cada rubro tiene clave, ícono y etiqueta', () => {
    for (const r of RUBROS) {
      expect(typeof r.key).toBe('string');
      expect(r.icon).toBeTruthy();
      expect(r.label).toBeTruthy();
    }
  });

  it('las claves coinciden EXACTAMENTE con el dominio CHECK de productos.rubro', () => {
    // V27 abrió chk_productos_rubro_domain a estos cuatro valores. Si el
    // frontend manda otra cosa a /api/data?rubro=, filtra por un valor que
    // ninguna fila tiene y la vista sale vacía.
    const conValor = RUBRO_KEYS.filter(Boolean);
    expect(conValor).toEqual(['indumentaria', 'tecnologia', 'suplementos', 'oficina']);
  });

  it('rubroLabel devuelve la etiqueta, y la clave cruda si no la conoce', () => {
    expect(rubroLabel('oficina')).toBe('Oficina');
    expect(rubroLabel('indumentaria')).toBe('Indumentaria');
    expect(rubroLabel('')).toBe('Todos');
    expect(rubroLabel('inexistente')).toBe('inexistente');
  });
});
