// frontend-auth-ui, Phase 7 (design D6, tasks-part2 7.5). Topbar's scrape
// affordance is hidden — not disabled — for a non-ADMIN, and this is also
// where logout finally gets a home (5.11 left it unbuilt).
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import Topbar from './Topbar';
import { useAuth } from '../auth/AuthProvider';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

function baseProps(overrides = {}) {
  return {
    meta: {}, facets: {}, sitioFiltro: '', rubroFiltro: '',
    onSitioChange: vi.fn(), onRubroChange: vi.fn(), onReScrape: vi.fn(),
    gymrat: false, onGymratToggle: vi.fn(),
    ...overrides,
  };
}

describe('Topbar — canScrape gates the "nuevo scraping" button (design D6)', () => {
  it('omits the button entirely (not disabled) when canScrape is false', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<Topbar {...baseProps({ canScrape: false })} />);

    expect(screen.queryByText(/nuevo scraping/i)).not.toBeInTheDocument();
  });

  it('defaults to hidden when canScrape is not passed at all — fails closed, not open', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<Topbar {...baseProps()} />);

    expect(screen.queryByText(/nuevo scraping/i)).not.toBeInTheDocument();
  });

  it('renders the button when canScrape is true', () => {
    useAuth.mockReturnValue({ identity: { username: 'admin', roles: ['ADMIN'] }, logout: vi.fn() });

    render(<Topbar {...baseProps({ canScrape: true })} />);

    expect(screen.getByText(/nuevo scraping/i)).toBeInTheDocument();
  });
});

describe('Topbar — user menu + logout (5.11 / 7.5)', () => {
  it('shows the current username', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<Topbar {...baseProps()} />);

    expect(screen.getByText('valeria')).toBeInTheDocument();
  });

  // Logout moved behind the avatar dropdown (UserMenu), so it is no longer a
  // button sitting in the row — this test opens the menu first. What it
  // guarantees is unchanged: exactly one call into the shared authSession
  // path, never a second implementation.
  it('choosing "Cerrar sesión" calls the shared authSession logout path — no second implementation', async () => {
    const user = userEvent.setup();
    const logout = vi.fn();
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout });

    render(<Topbar {...baseProps()} />);
    await user.click(screen.getByRole('button', { name: /sesión de valeria/i }));
    await user.click(await screen.findByText('Cerrar sesión'));

    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('renders nothing for the user control when there is no identity', () => {
    useAuth.mockReturnValue({ identity: null, logout: vi.fn() });

    render(<Topbar {...baseProps()} />);

    expect(screen.queryByRole('button', { name: /sesión de/i })).not.toBeInTheDocument();
  });
});

// perf/dedupe-load-requests: Topbar used to fetch GET /api/status + GET
// /api/ml/estado itself for this banner — AppLayout already reads both once
// on its own mount for other reasons, so it now hands the same { st, ml }
// shape down as a prop and Topbar renders it without fetching anything.
describe('Topbar — ML banner renders from the mlBanner prop (perf/dedupe-load-requests)', () => {
  it('shows accuracy and refined count once a text model exists', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<Topbar {...baseProps({
      mlBanner: { st: { mlRefinadas: 42 }, ml: { hasTextModel: true, textMeta: { accuracy: 0.873 } } },
    })} />);

    expect(screen.getByText(/87\.3% acc/)).toBeInTheDocument();
    expect(screen.getByText(/42 ref\./)).toBeInTheDocument();
  });

  it('falls back to "ML estadístico" when there is no text model yet', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<Topbar {...baseProps({ mlBanner: { st: {}, ml: { hasTextModel: false } } })} />);

    expect(screen.getByText(/ML estadístico/)).toBeInTheDocument();
  });

  it('renders no banner at all while the caller has not resolved it yet', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<Topbar {...baseProps()} />);

    expect(screen.queryByText(/ML estadístico/)).not.toBeInTheDocument();
    expect(screen.queryByText(/acc ·/)).not.toBeInTheDocument();
  });
});
