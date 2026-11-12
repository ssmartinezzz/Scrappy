import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import PrimaryNav from './PrimaryNav';
import { useAuth } from '../../auth/AuthProvider';

vi.mock('../../auth/AuthProvider', () => ({ useAuth: vi.fn() }));

describe('PrimaryNav — one row for every width', () => {
  afterEach(() => { delete Element.prototype.scrollIntoView; });

  it('keeps the drawer trigger next to the scrollable destination row', () => {
    useAuth.mockReturnValue({ identity: { username: 'v', roles: ['VIEWER'] } });
    render(<MemoryRouter initialEntries={['/picks']}><PrimaryNav /></MemoryRouter>);

    expect(screen.getByLabelText('Abrir menú')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Picks' }).closest('.primary-nav-scroll')).not.toBeNull();
  });

  it('scrolls the active destination into view, so a phone never hides where you are', () => {
    useAuth.mockReturnValue({ identity: { username: 'v', roles: ['VIEWER'] } });
    const scrolled = [];
    Element.prototype.scrollIntoView = function () { scrolled.push(this.textContent); };

    render(<MemoryRouter initialEntries={['/favoritos']}><PrimaryNav /></MemoryRouter>);

    expect(scrolled).toEqual(['Guardados']);
  });
});
