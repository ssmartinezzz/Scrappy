// frontend-auth-ui, Phase 7 (design D6, tasks-part2 7.3/7.4).
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import NavDrawer from './NavDrawer';
import { useAuth } from '../../auth/AuthProvider';

vi.mock('../../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

function renderDrawer() {
  const utils = render(
    <MemoryRouter>
      <NavDrawer />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByLabelText('Abrir menú'));
  return utils;
}

describe('NavDrawer — role-aware rendering (design D6)', () => {
  it('a VIEWER sees no Cronjobs entry in the drawer', () => {
    useAuth.mockReturnValue({ identity: { username: 'v', roles: ['VIEWER'] } });

    renderDrawer();

    expect(screen.queryByText('Cronjobs')).not.toBeInTheDocument();
  });

  it('an ADMIN sees the Cronjobs entry in the drawer', () => {
    useAuth.mockReturnValue({ identity: { username: 'a', roles: ['ADMIN'] } });

    renderDrawer();

    expect(screen.getByText('Cronjobs')).toBeInTheDocument();
  });
});
