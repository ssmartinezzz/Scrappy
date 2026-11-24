// A filter change while a catalog page is still loading: the grid must end up
// with the new filter's rows, whichever answer arrives last.
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useOutletContext } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import AppLayout from './AppLayout';
import { useAuth } from '../auth/AuthProvider';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

const json = body => ({ ok: true, status: 200, json: async () => body });
const page = (nombre) => json({
  data: { productos: [{ url: nombre, nombre }], meta: {} },
  page: { number: 0, size: 48, total: 1, totalPages: 1 },
});

function Probe() {
  const { S, setFilter } = useOutletContext();
  return (
    <>
      <button onClick={() => setFilter({ busq: 'zzz' })}>search</button>
      <p data-testid="rows">{S.prods.map(p => p.nombre).join()}</p>
    </>
  );
}

const DONE = json({ data: { tieneData: true, status: 'DONE' } });

function setup(status = Promise.resolve(DONE)) {
  useAuth.mockReturnValue({ isAdmin: false, identity: null, logout: vi.fn() });
  const pending = [];
  globalThis.fetch = vi.fn().mockImplementation(url => {
    const u = String(url);
    if (u.includes('/api/data')) return new Promise(resolve => { pending.push({ u, resolve }); });
    if (u.includes('/api/status')) return status;
    return Promise.resolve(json({ data: null }));
  });
  render(
    <MemoryRouter initialEntries={['/catalogo']}>
      <Routes>
        <Route element={<AppLayout />}><Route path="/catalogo" element={<Probe />} /></Route>
      </Routes>
    </MemoryRouter>,
  );
  return pending;
}

describe('AppLayout — filter change with a page in flight', () => {
  afterEach(() => vi.restoreAllMocks());

  it.each([
    ['the old answer arrives last', [1, 0]],
    ['the old answer arrives first', [0, 1]],
  ])('keeps only the new rows when %s', async (_, order) => {
    const pending = setup();
    await waitFor(() => expect(pending).toHaveLength(1));

    fireEvent.click(screen.getByText('search'));
    await waitFor(() => expect(pending).toHaveLength(2));
    expect(pending[1].u).toContain('q=zzz');

    const answers = [page('old'), page('new')];
    for (const i of order) await act(async () => pending[i].resolve(answers[i]));

    expect(screen.getByTestId('rows').textContent).toBe('new');
  });

  it('does not ask for the first page again when the status read lands after it', async () => {
    let releaseStatus;
    const pending = setup(new Promise(resolve => { releaseStatus = resolve; }));
    await waitFor(() => expect(pending).toHaveLength(1));
    await act(async () => pending[0].resolve(page('one')));
    await act(async () => releaseStatus(DONE));
    await waitFor(() => expect(globalThis.fetch.mock.calls.some(c => String(c[0]).includes('/api/facets'))).toBe(true));
    expect(pending).toHaveLength(1);
  });
});
