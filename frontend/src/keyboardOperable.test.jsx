import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import MarcasPanel from './components/MarcasPanel';
import OportunidadesPanel from './components/OportunidadesPanel';
import PickCard from './components/PickCard';
import { fetchData, fetchMarcasBrowser, fetchTendencias } from './api';

vi.mock('./api', async importOriginal => ({
  ...(await importOriginal()),
  fetchData: vi.fn(),
  fetchMarcasBrowser: vi.fn(),
  fetchTendencias: vi.fn(),
}));

const PICK = { tipo: 'valor', nombre: 'Zapatilla Runner', sitio: 'freres', marca: 'Nike', precio: 1000, url: 'https://x/1' };
const PROD = { url: 'https://x/2', nombre: 'Remera Basic', sitio: 'vcp', marca: 'Adidas', precio: 500, img: '' };

beforeEach(() => {
  vi.clearAllMocks();
  fetchMarcasBrowser.mockResolvedValue([{ marca: 'Nike', count: 3, precioMin: 100, precioMax: 900 }]);
  fetchData.mockResolvedValue({ productos: [PROD] });
  fetchTendencias.mockResolvedValue({
    state: 'ok',
    data: { badgeCounts: {}, totalProductos: 1, topProductos: [{ ...PROD, badges: ['all_time_low'] }] },
  });
});

/** Tab to the control, then activate it with `key`: the path a keyboard-only user takes. */
async function activateWithKeyboard(user, control, key) {
  await user.tab();
  while (document.activeElement !== control && document.activeElement !== document.body) await user.tab();
  expect(control).toHaveFocus();
  await user.keyboard(key);
}

describe.each([['{Enter}'], [' ']])('clickable cards are keyboard-operable (key %j)', key => {
  it('PickCard', async () => {
    const onClick = vi.fn();
    render(<PickCard pick={PICK} mediana={1500} onClick={onClick} />);

    await activateWithKeyboard(userEvent.setup(), screen.getByRole('button', { name: /Zapatilla Runner/ }), key);

    expect(onClick).toHaveBeenCalledWith(PICK);
  });

  it('OportunidadesPanel product preview', async () => {
    const onProductClick = vi.fn();
    render(<MemoryRouter><OportunidadesPanel onProductClick={onProductClick} /></MemoryRouter>);

    await activateWithKeyboard(userEvent.setup(), await screen.findByRole('button', { name: /Remera Basic/ }), key);

    expect(onProductClick).toHaveBeenCalledWith(expect.objectContaining({ url: PROD.url }));
  });

  it('MarcasPanel brand card and the product cards of its detail', async () => {
    const onProductClick = vi.fn();
    const user = userEvent.setup();
    render(<MarcasPanel onProductClick={onProductClick} />);
    // The panel re-reads once more after its 350 ms search debounce and swaps the grid for a
    // spinner while it does; interacting before that settles would lose focus to the remount.
    await waitFor(() => expect(fetchMarcasBrowser).toHaveBeenCalledTimes(2), { timeout: 2000 });

    await activateWithKeyboard(user, await screen.findByRole('button', { name: /Nike/ }), key);
    const product = await screen.findByRole('button', { name: /Remera Basic/ });
    await waitFor(() => expect(fetchData).toHaveBeenCalled());
    await activateWithKeyboard(user, product, key);

    expect(onProductClick).toHaveBeenCalledWith(expect.objectContaining({ url: PROD.url }));
  });
});
