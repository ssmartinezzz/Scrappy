import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import RequireRole from './RequireRole';
import { useAuth } from './AuthProvider';

vi.mock('./AuthProvider', () => ({ useAuth: vi.fn() }));

describe('RequireRole — explicit screen, not a silent redirect', () => {
  it('renders AccessDenied when identity lacks the required role', () => {
    useAuth.mockReturnValue({ identity: { username: 'v', roles: ['VIEWER'] } });

    render(<RequireRole role="ADMIN"><div>SECRET ADMIN PANEL</div></RequireRole>);

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('SECRET ADMIN PANEL')).not.toBeInTheDocument();
  });

  it('renders children when identity has the required role', () => {
    useAuth.mockReturnValue({ identity: { username: 'a', roles: ['ADMIN'] } });

    render(<RequireRole role="ADMIN"><div>SECRET ADMIN PANEL</div></RequireRole>);

    expect(screen.getByText('SECRET ADMIN PANEL')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('renders AccessDenied when identity has not resolved yet, rather than throwing', () => {
    useAuth.mockReturnValue({ identity: null });

    render(<RequireRole role="ADMIN"><div>SECRET ADMIN PANEL</div></RequireRole>);

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });
});
