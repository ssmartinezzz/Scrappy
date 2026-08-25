// frontend-auth-ui, Phase 7 (design D6, tasks-part2 7.5). Topbar's scrape
// affordance is hidden — not disabled — for a non-ADMIN, and this is also
// where logout finally gets a home (5.11 left it unbuilt).
import { fireEvent, render, screen } from '@testing-library/react';
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

  it('clicking "Cerrar sesión" calls the shared authSession logout path — no second implementation', () => {
    const logout = vi.fn();
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout });

    render(<Topbar {...baseProps()} />);
    fireEvent.click(screen.getByText('Cerrar sesión'));

    expect(logout).toHaveBeenCalledTimes(1);
  });
});
