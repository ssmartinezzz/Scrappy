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
