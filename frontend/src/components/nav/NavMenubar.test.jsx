// frontend-auth-ui, Phase 7 (design D6, tasks-part2 7.3/7.4).
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import NavMenubar from './NavMenubar';
import { useAuth } from '../../auth/AuthProvider';

vi.mock('../../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

function renderMenubar() {
  return render(
    <MemoryRouter>
      <NavMenubar />
    </MemoryRouter>,
  );
}

describe('NavMenubar — role-aware rendering (design D6)', () => {
  it('a VIEWER sees no Cronjobs nav node', () => {
    useAuth.mockReturnValue({ identity: { username: 'v', roles: ['VIEWER'] } });

    renderMenubar();

    expect(screen.queryByText('Cronjobs')).not.toBeInTheDocument();
  });

  it('an ADMIN sees the Cronjobs nav node', () => {
    useAuth.mockReturnValue({ identity: { username: 'a', roles: ['ADMIN'] } });

    renderMenubar();

    expect(screen.getByText('Cronjobs')).toBeInTheDocument();
  });
});
