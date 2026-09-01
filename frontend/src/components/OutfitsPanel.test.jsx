import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import OutfitsPanel from '@/components/OutfitsPanel';
import { fetchOutfitBuilder, resetOutfitFeedback } from '@/api';

// Mock the api module rather than global.fetch, pinning the component to the seam
// that carries VITE_API_BASE_URL — same reasoning as SuplementosPanel.test.jsx.
vi.mock('@/api', async importOriginal => ({
  ...(await importOriginal()),
  fetchOutfitBuilder: vi.fn(),
  resetOutfitFeedback: vi.fn(),
}));

/** The query params the component sent on the Nth builder call. */
const llamada = n => fetchOutfitBuilder.mock.calls[n][0];

beforeEach(() => {
  vi.clearAllMocks();
  fetchOutfitBuilder.mockResolvedValue({ status: 'ok', slots: [], totalEstimado: 0 });
  resetOutfitFeedback.mockResolvedValue({});
});

/** The panel loads on mount; wait for that before driving the form. */
async function montado() {
  render(<OutfitsPanel />);
  await waitFor(() => expect(fetchOutfitBuilder).toHaveBeenCalled());
}

describe('OutfitsPanel — the category form', () => {
  it('scrolls the picker inside its own card instead of stretching the page', async () => {
    // 43 categories across 4 groups. Letting the picker grow free pushes the budget,
    // the button and the outfit itself below the fold — the same problem the
    // supplements picker had with 33 subtypes.
    await montado();

    const picker = await screen.findByTestId('cat-picker');
    expect(picker.className).toMatch(/overflow-y-auto/);
    expect(picker.className).toMatch(/max-h-/);
  });

  it('keeps a "Seleccionados" summary pinned above the groups', async () => {
    // The row is sticky, and sticky needs an opaque background of its own: it uses
    // bg-inherit, which resolves to transparent unless this root paints one.
    await montado();

    const picker = await screen.findByTestId('cat-picker');
    expect(within(picker).getByText('Seleccionados')).toBeInTheDocument();
    expect(picker.className).toMatch(/bg-s1/);
  });

  it('shows the default gym categories as selected, and toggling removes one', async () => {
    const user = userEvent.setup();
    await montado();

    // "Remera" is a gym default, so it starts in the selected row and its chip
    // offers to remove it.
    const quitar = await screen.findByRole('button', { name: 'Quitar Remera' });
    await user.click(quitar);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Agregar Remera' })).toBeInTheDocument());
  });

  it('formats the budget with thousands separators and sends the plain number', async () => {
    const user = userEvent.setup();
    await montado();
    const antes = fetchOutfitBuilder.mock.calls.length;

    await user.type(screen.getByLabelText(/presupuesto/i), '150000');
    await user.click(screen.getByRole('button', { name: 'Armar' }));

    await waitFor(() =>
      expect(fetchOutfitBuilder.mock.calls.length).toBeGreaterThan(antes));
    expect(screen.getByLabelText(/presupuesto/i)).toHaveValue('150.000');
    expect(llamada(fetchOutfitBuilder.mock.calls.length - 1).presupuesto).toBe(150000);
  });
});
