import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import OportunidadesPanel from './OportunidadesPanel';
import OportunidadesBadgePage from './OportunidadesBadgePage';
import { fetchData } from '../api';

vi.mock('../api', async importOriginal => ({
  ...(await importOriginal()),
  fetchData: vi.fn(),
}));

const prod = (n, badge) => ({ url: `https://x/${badge}/${n}`, nombre: `Prod ${badge} ${n}`, sitio: 's', marca: 'm', precio: 100, img: '' });
const TOTALES = { all_time_low: 5, verified_deal: 4, below_market: 3, trending: 20, price_dropping: 2, above_market: 0, fake_discount: 1 };
const BASE = 100;

function respuesta(filtros) {
  const total = filtros.badge ? TOTALES[filtros.badge] : BASE;
  const n = Math.min(total, filtros.size || 24);
  return { productos: Array.from({ length: n }, (_, i) => prod(i, filtros.badge || 'base')), meta: { total } };
}

const Where = () => {
  const l = useLocation();
  return <div data-testid="where">{l.pathname + l.search}</div>;
};

function renderPanel(url = '/analisis/oportunidades') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Where />
      <Routes>
        <Route path="/analisis/oportunidades" element={<OportunidadesPanel onProductClick={() => {}} />} />
        <Route path="/analisis/oportunidades/:badge" element={<OportunidadesBadgePage onProductClick={() => {}} />} />
      </Routes>
    </MemoryRouter>,
  );
}

const llamadas = () => fetchData.mock.calls.map(c => c[0]);

beforeEach(() => {
  vi.clearAllMocks();
  fetchData.mockImplementation(async f => respuesta(f));
});

describe('OportunidadesPanel', () => {
  it('fetches each preview badge ranked by its own order, scoped to the rubro and without the junk categories', async () => {
    renderPanel('/analisis/oportunidades?rubro=indumentaria');
    await screen.findByText('Prod all_time_low 0');

    const ordenes = { all_time_low: 'baja_historica', verified_deal: 'desc_pct', below_market: 'mercado_asc' };
    for (const [badge, orden] of Object.entries(ordenes)) {
      expect(llamadas()).toContainEqual({
        badge, rubro: 'indumentaria', excluirCategoria: ['Otros', 'Alimentos'], orden, size: 8,
      });
    }
  });

  it('counts every badge inside the same rubro and exclusion, against the no-badge total', async () => {
    renderPanel('/analisis/oportunidades?rubro=indumentaria');
    await screen.findByText('Prod all_time_low 0');

    expect(llamadas()).toContainEqual({
      badge: 'trending', rubro: 'indumentaria', excluirCategoria: ['Otros', 'Alimentos'], size: 1,
    });
    expect(llamadas()).toContainEqual({
      rubro: 'indumentaria', excluirCategoria: ['Otros', 'Alimentos'], size: 1,
    });
    expect(await screen.findByText('20 (20.0%)')).toBeTruthy();
  });

  it('renders the rubro chips with Todos selected by default', async () => {
    renderPanel();
    await screen.findByText('Prod all_time_low 0');

    const chips = screen.getByRole('group', { name: 'Rubro' });
    expect(within(chips).getAllByRole('button').map(b => b.textContent.replace(/^\S+\s/, '')))
      .toEqual(['Todos', 'Indumentaria', 'Tecnología', 'Suplementos', 'Oficina', 'Construcción', 'Hogar']);
    expect(within(chips).getByRole('button', { name: /Todos/ }).getAttribute('aria-pressed')).toBe('true');
  });

  it('picking a chip writes ?rubro= and refetches with it', async () => {
    const user = userEvent.setup();
    renderPanel();
    await screen.findByText('Prod all_time_low 0');

    await user.click(within(screen.getByRole('group', { name: 'Rubro' })).getByRole('button', { name: /Tecnología/ }));

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/analisis/oportunidades?rubro=tecnologia'));
    await waitFor(() => expect(llamadas()).toContainEqual(expect.objectContaining({
      badge: 'all_time_low', rubro: 'tecnologia', orden: 'baja_historica', size: 8,
    })));
  });

  it('drill-down link carries the rubro and the page honours it, filter and back button included', async () => {
    const user = userEvent.setup();
    renderPanel('/analisis/oportunidades?rubro=hogar');
    await user.click(await screen.findByRole('button', { name: /Mínimo histórico/ }));

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/analisis/oportunidades/all_time_low?rubro=hogar'));
    await waitFor(() => expect(llamadas()).toContainEqual(expect.objectContaining({
      badge: 'all_time_low', rubro: 'hogar', excluirCategoria: ['Otros', 'Alimentos'], page: 0, size: 48,
    })));

    await user.click(screen.getByRole('button', { name: /Volver/ }));
    expect(screen.getByTestId('where').textContent).toBe('/analisis/oportunidades?rubro=hogar');
  });

  it('keeps a failure visible instead of an empty page', async () => {
    fetchData.mockResolvedValue(null);
    renderPanel();
    expect(await screen.findByText(/No se pudieron cargar las oportunidades/)).toBeTruthy();
  });

  it('says so when the rubro has no products at all', async () => {
    fetchData.mockResolvedValue({ productos: [], meta: { total: 0 } });
    renderPanel('/analisis/oportunidades?rubro=oficina');
    expect(await screen.findByText(/Sin productos en este rubro/)).toBeTruthy();
  });
});
