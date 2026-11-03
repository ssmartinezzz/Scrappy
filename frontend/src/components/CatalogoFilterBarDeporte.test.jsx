// The Deporte chips live in the "Más filtros" sheet and ride on AppLayout's
// real filter state, so the tests go through AppLayout and watch /api/data.
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import AppLayout from './AppLayout';
import CatalogoFilterBar from './CatalogoFilterBar';
import { useAuth } from '../auth/AuthProvider';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

const json = (body) => ({ ok: true, status: 200, json: async () => body });

function router(facets) {
  return vi.fn().mockImplementation((url) => {
    const u = String(url);
    if (u.includes('/api/status'))        return Promise.resolve(json({ data: { tieneData: true, status: 'DONE', mensaje: '' } }));
    if (u.includes('/api/ml/estado'))     return Promise.resolve(json({ training: { running: false } }));
    if (u.includes('/api/ml/resultado'))  return Promise.resolve(json({ running: false, done: false }));
    if (u.includes('/api/indices'))       return Promise.resolve(json({ ipc: {}, usd: {}, actualizado: null }));
    if (u.includes('/api/outfits/saved') || u.includes('/api/pcs/saved') || u.includes('/api/favoritos')) return Promise.resolve(json([]));
    if (u.includes('/api/tendencias'))    return Promise.resolve(json({}));
    if (u.includes('/api/facets'))        return Promise.resolve(json({ data: facets }));
    if (u.includes('/api/data'))          return Promise.resolve(json({ productos: [], meta: {} }));
    throw new Error(`unexpected fetch: ${u}`);
  });
}

const dataCalls = () => global.fetch.mock.calls.map(c => String(c[0])).filter(u => u.includes('/api/data'));
const lastDataCall = () => dataCalls().at(-1);

async function renderBar(facets) {
  useAuth.mockReturnValue({ isAdmin: false, identity: null, logout: vi.fn() });
  global.fetch = router(facets);
  render(
    <MemoryRouter initialEntries={['/catalogo']}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/catalogo" element={<CatalogoFilterBar />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(dataCalls().length).toBeGreaterThan(0));
  fireEvent.click(await screen.findByText(/Más filtros/));
}

describe('CatalogoFilterBar — Deporte chips', () => {
  afterEach(() => vi.restoreAllMocks());

  it('renders a chip per sport with its label and count', async () => {
    await renderBar({ deportes: { futbol: 12, natacion: 3 } });

    expect(await screen.findByText('Deporte')).toBeTruthy();
    const futbol = screen.getByText('Fútbol').closest('button');
    expect(futbol.textContent).toContain('12');
    expect(screen.getByText('Natación').closest('button').textContent).toContain('3');
  });

  it('shows no Deporte section when the facet is empty', async () => {
    await renderBar({ deportes: {} });

    await screen.findByText('Más filtros', { selector: '.cfb-sheet-title' });
    expect(screen.queryByText('Deporte')).toBeNull();
  });

  it('a click sends deporte=<code> to /api/data and a second click clears it', async () => {
    await renderBar({ deportes: { futbol: 12 } });

    fireEvent.click((await screen.findByText('Fútbol')).closest('button'));
    await waitFor(() => expect(lastDataCall()).toContain('deporte=futbol'));

    fireEvent.click(screen.getByText('Fútbol').closest('button'));
    await waitFor(() => expect(lastDataCall()).not.toContain('deporte='));
  });

  it('Limpiar clears the active sport', async () => {
    await renderBar({ deportes: { tenis: 4 } });

    fireEvent.click((await screen.findByText('Tenis')).closest('button'));
    await waitFor(() => expect(lastDataCall()).toContain('deporte=tenis'));

    fireEvent.click(screen.getByText(/Limpiar/));
    await waitFor(() => expect(lastDataCall()).not.toContain('deporte='));
  });
});
