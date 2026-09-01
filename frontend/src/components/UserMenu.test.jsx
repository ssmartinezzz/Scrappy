// The identity control's own suite. Topbar.test.jsx still covers that the
// control is wired into the row; this file covers what the control itself
// promises — initials, role, and that logout runs exactly once.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import UserMenu, { iniciales } from './UserMenu';
import { useAuth } from '../auth/AuthProvider';

vi.mock('../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

describe('iniciales', () => {
  it('takes the first two letters of a single-word username', () => {
    expect(iniciales('valeria')).toBe('VA');
  });

  // A compound username is the reason this is not `slice(0, 2)`: "sa" says
  // nothing, "SM" reads as a person.
  it.each(['santi.martinez', 'santi_martinez', 'santi-martinez', 'santi martinez'])(
    'takes one letter per part of %s', (username) => {
      expect(iniciales(username)).toBe('SM');
    });

  it('falls back to a placeholder rather than rendering an empty circle', () => {
    expect(iniciales('')).toBe('?');
  });
});

describe('UserMenu', () => {
  it('renders nothing when there is no identity', () => {
    useAuth.mockReturnValue({ identity: null, logout: vi.fn() });

    const { container } = render(<UserMenu />);

    expect(container).toBeEmptyDOMElement();
  });

  it('shows the username and role once the menu is open', async () => {
    const user = userEvent.setup();
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<UserMenu />);
    await user.click(screen.getByRole('button', { name: /sesión de valeria/i }));

    expect(await screen.findByText('VIEWER')).toBeInTheDocument();
  });

  it('calls logout exactly once when the item is chosen', async () => {
    const user = userEvent.setup();
    const logout = vi.fn();
    useAuth.mockReturnValue({ identity: { username: 'admin', roles: ['ADMIN'] }, logout });

    render(<UserMenu />);
    await user.click(screen.getByRole('button', { name: /sesión de admin/i }));
    await user.click(await screen.findByText('Cerrar sesión'));

    expect(logout).toHaveBeenCalledTimes(1);
  });

  // The menu is closed by default: logout must not be one stray click away,
  // and the row must not carry its width on a phone.
  it('keeps logout out of the DOM until the menu is opened', () => {
    useAuth.mockReturnValue({ identity: { username: 'valeria', roles: ['VIEWER'] }, logout: vi.fn() });

    render(<UserMenu />);

    expect(screen.queryByText('Cerrar sesión')).not.toBeInTheDocument();
  });
});
