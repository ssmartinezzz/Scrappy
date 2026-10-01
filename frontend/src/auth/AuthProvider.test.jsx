import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider, useAuth } from './AuthProvider';
import * as authSession from '../lib/authSession';
import { trackUnhandledRejections } from '../test/unhandledRejections';

vi.mock('../lib/authSession', () => ({
  bootstrap: vi.fn(),
  subscribe: vi.fn(() => () => {}),
  getSnapshot: vi.fn(() => ({ authenticated: false, identity: null })),
  getLastFailureReason: vi.fn(() => null),
  logout: vi.fn(),
}));

function Status() {
  return <p>{useAuth().status}</p>;
}

let rejections;
beforeEach(() => { rejections = trackUnhandledRejections(); });
afterEach(() => rejections.stop());

describe('AuthProvider — a bootstrap that rejects does not strand the gate on "booting"', () => {
  it('settles as anonymous from the snapshot and leaves no unhandled rejection', async () => {
    authSession.bootstrap.mockRejectedValue(new TypeError('Failed to fetch'));
    render(<AuthProvider><Status /></AuthProvider>);

    await screen.findByText('anonymous');
    await rejections.settle();
    expect(rejections.seen).toEqual([]);
  });
});
