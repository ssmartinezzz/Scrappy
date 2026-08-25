import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import AccessDenied from './AccessDenied';

describe('AccessDenied — an explicit screen, never a silent redirect', () => {
  it('renders an alert explaining the permission gap, not a blank page', () => {
    render(<AccessDenied />);

    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(/no ten[eé]s permiso/i);
  });
});
