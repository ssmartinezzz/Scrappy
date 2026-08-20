// frontend-auth-ui, Phase 7 audit finding (not in the design's named-surfaces
// list): every mutation on /financiacion (activar/editar/eliminar/crear
// preset) is ADMIN in ApiRoutePolicy.TABLE, but GET /api/financiacion/presets
// is AUTHENTICATED — a VIEWER is entitled to see which preset is active.
// Hiding the whole route would have hidden that legitimate read too (the
// symmetric error the task brief warns against), so only the mutation
// controls are gated, not the page.
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import FinanPanel from './FinanPanel';
import { useAuth } from '../auth/AuthProvider';
import { fetchFinanciacionPresets } from '../api';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));
vi.mock('../api', () => ({
  fetchFinanciacionPresets: vi.fn(),
  crearFinanciacionPreset: vi.fn(),
  editarFinanciacionPreset: vi.fn(),
  activarFinanciacionPreset: vi.fn(),
  eliminarFinanciacionPreset: vi.fn(),
}));

const PRESETS = {
  presets: [{ id: 1, label: '12 cuotas', recargoPct: 10, cuotas: 12, activo: true }],
  activo: { id: 1, label: '12 cuotas' },
};

beforeEach(() => {
  fetchFinanciacionPresets.mockResolvedValue(PRESETS);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('FinanPanel — VIEWER sees the read, not the mutation controls', () => {
  it('shows the active preset (a legitimate AUTHENTICATED read) with no edit/delete/activate/create controls', async () => {
    useAuth.mockReturnValue({ isAdmin: false });

    render(<FinanPanel />);

    expect((await screen.findAllByText('12 cuotas')).length).toBeGreaterThan(0);
    expect(screen.queryByTitle('Editar')).not.toBeInTheDocument();
    expect(screen.queryByTitle('Eliminar')).not.toBeInTheDocument();
    expect(screen.queryByTitle('Activar')).not.toBeInTheDocument();
    expect(screen.queryByText(/\+ nuevo preset/i)).not.toBeInTheDocument();
  });
});

describe('FinanPanel — ADMIN sees full CRUD controls', () => {
  it('shows edit/delete controls and the create-preset affordance', async () => {
    useAuth.mockReturnValue({ isAdmin: true });

    render(<FinanPanel />);

    await screen.findAllByText('12 cuotas');
    expect(screen.getByTitle('Editar')).toBeInTheDocument();
    expect(screen.getByTitle('Eliminar')).toBeInTheDocument();
    expect(screen.getByText(/\+ nuevo preset/i)).toBeInTheDocument();
  });
});
