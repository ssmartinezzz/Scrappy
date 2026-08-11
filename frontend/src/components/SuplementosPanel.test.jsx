import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import SuplementosPanel from '@/components/SuplementosPanel';
import { fetchSuplementosBuilder, fetchSuplementosTipos } from '@/api';

// Mocking the api module (not global.fetch) pins the component to the seam that
// carries VITE_API_BASE_URL — same reasoning as BuySignal.test.jsx.
vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchSuplementosTipos: vi.fn(),
  fetchSuplementosBuilder: vi.fn(),
}));

const TIPOS = [
  { tipo: 'Proteína en Polvo', grupo: 'Proteína' },
  { tipo: 'Creatina', grupo: null },
];

const pick = (tipo, nombre) => ({
  tipo, nombre, sitio: 'entreno', precio: 50000,
  url: `https://test/${nombre}`, img: '', marca: 'ENA',
});

/** The exclusions the component sent on the Nth call to the builder. */
function excluirDeLaLlamada(n) {
  return fetchSuplementosBuilder.mock.calls[n][0].excluir ?? [];
}

beforeEach(() => {
  vi.clearAllMocks();
  fetchSuplementosTipos.mockResolvedValue(TIPOS);
  fetchSuplementosBuilder.mockResolvedValue({
    picks: [pick('Proteína en Polvo', 'Whey A')],
    sinStock: [],
  });
});

describe('SuplementosPanel — Regenerar', () => {
  async function armar(user) {
    await waitFor(() => expect(fetchSuplementosTipos).toHaveBeenCalled());
    await user.click(screen.getByRole('button', { name: 'Generar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(1));
  }

  it('el primer armado no excluye nada', async () => {
    const user = userEvent.setup();
    render(<SuplementosPanel />);
    await armar(user);

    expect(excluirDeLaLlamada(0)).toEqual([]);
  });

  it('Regenerar manda lo ya mostrado, para que el servidor ofrezca otro', async () => {
    // El bug: el pick del servidor es determinístico, así que sin mandar lo visto
    // la petición de Regenerar era idéntica y la respuesta también.
    const user = userEvent.setup();
    render(<SuplementosPanel />);
    await armar(user);

    fetchSuplementosBuilder.mockResolvedValue({
      picks: [pick('Proteína en Polvo', 'Whey B')],
      sinStock: [],
    });
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(2));

    expect(excluirDeLaLlamada(1)).toEqual(['https://test/Whey A']);
  });

  it('Regenerar acumula a lo largo de varios clicks', async () => {
    const user = userEvent.setup();
    render(<SuplementosPanel />);
    await armar(user);

    for (const nombre of ['Whey B', 'Whey C']) {
      fetchSuplementosBuilder.mockResolvedValue({
        picks: [pick('Proteína en Polvo', nombre)], sinStock: [],
      });
      await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    }
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(3));

    expect(excluirDeLaLlamada(2))
      .toEqual(['https://test/Whey A', 'https://test/Whey B']);
  });

  it('Armar arranca de cero aunque ya se haya regenerado', async () => {
    // Regresión del bug de wiring: con onClick={generar} React pasa el EVENTO como
    // primer argumento, que es truthy — así que "Armar" habría acumulado también y
    // nunca habría vuelto a mostrar el mejor del catálogo.
    const user = userEvent.setup();
    render(<SuplementosPanel />);
    await armar(user);
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Generar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(3));

    expect(excluirDeLaLlamada(2)).toEqual([]);
  });

  it('cuando el servidor recicla el pool, la lista de vistos se reinicia', async () => {
    // El servidor vuelve al mejor cuando se agotan los candidatos. Si el cliente
    // siguiera acumulando, mandaría para siempre URLs que ya no excluyen nada.
    const user = userEvent.setup();
    render(<SuplementosPanel />);
    await armar(user);

    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(3));

    // La 2da y 3ra respuesta repitieron "Whey A" (pool agotado), así que la 3ra
    // llamada no debe arrastrar un historial que crece sin excluir nada.
    expect(excluirDeLaLlamada(2)).toEqual(['https://test/Whey A']);
  });

  it('un subtipo con un solo candidato no borra el historial de los demás', async () => {
    // El reinicio del pool es por subtipo, no global. La creatina de este catálogo
    // tiene un único candidato, así que se repite en cada respuesta; si esa
    // repetición reiniciara la lista entera, la proteína alternaría para siempre
    // entre sus dos primeros candidatos y nunca llegaría al tercero.
    const user = userEvent.setup();
    render(<SuplementosPanel />);

    fetchSuplementosBuilder.mockResolvedValue({
      picks: [pick('Proteína en Polvo', 'Whey A'), pick('Creatina', 'Crea Única')],
      sinStock: [],
    });
    await armar(user);

    fetchSuplementosBuilder.mockResolvedValue({
      picks: [pick('Proteína en Polvo', 'Whey B'), pick('Creatina', 'Crea Única')],
      sinStock: [],
    });
    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole('button', { name: 'Regenerar' }));
    await waitFor(() => expect(fetchSuplementosBuilder).toHaveBeenCalledTimes(3));

    expect(excluirDeLaLlamada(2)).toEqual(expect.arrayContaining([
      'https://test/Whey A', 'https://test/Whey B',
    ]));
  });
});
