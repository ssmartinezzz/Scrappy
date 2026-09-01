import { beforeEach, describe, expect, it, vi } from 'vitest';

import { readStatus } from './readStatus';
import { fetchStatus } from '../api';

vi.mock('../api', () => ({ fetchStatus: vi.fn() }));

beforeEach(() => {
  vi.mocked(fetchStatus).mockReset();
});

describe('readStatus — collapses both failure shapes of /api/status into one', () => {
  it('passes a good status straight through', async () => {
    vi.mocked(fetchStatus).mockResolvedValue({ status: 'RUNNING', tieneData: true });

    await expect(readStatus()).resolves.toEqual({ status: 'RUNNING', tieneData: true });
  });

  it('returns null when fetchStatus resolves null (non-ok response)', async () => {
    vi.mocked(fetchStatus).mockResolvedValue(null);

    await expect(readStatus()).resolves.toBeNull();
  });

  it('returns null instead of rejecting when nothing is listening', async () => {
    // The shape a `.then()`-only caller never sees: authedFetch calls raw
    // fetch, which rejects on a dead backend. A caller that only guards `null`
    // dies here — inside an interval, once every tick, forever.
    vi.mocked(fetchStatus).mockRejectedValue(new TypeError('Failed to fetch'));

    await expect(readStatus()).resolves.toBeNull();
  });
});
