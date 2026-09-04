// swagger-ui-admin-gated — loadContract.js (design.md ADR-1/ADR-3).
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../authedFetch', () => ({ authedFetch: vi.fn() }));
vi.mock('../../api', () => ({ BASE: '' }));

import { authedFetch } from '../authedFetch';
import * as apiModule from '../../api';
import { loadContract, LoadContractError } from './loadContract';

const SAMPLE_YAML = `
openapi: 3.1.0
info:
  title: Sample
  version: "1.0.0"
servers:
  - url: http://localhost:3000
paths:
  /api/data:
    get:
      x-access: AUTHENTICATED
`;

function okResponse(text) {
  return { ok: true, status: 200, text: async () => text };
}

function errorResponse(status) {
  return { ok: false, status, text: async () => '' };
}

afterEach(() => {
  vi.clearAllMocks();
});

describe('loadContract — happy path', () => {
  it('fetches through authedFetch, parses the YAML, and returns a spec object', async () => {
    authedFetch.mockResolvedValue(okResponse(SAMPLE_YAML));

    const spec = await loadContract();

    expect(authedFetch).toHaveBeenCalledWith(expect.stringContaining('/api/openapi.yaml'));
    expect(spec.info.title).toBe('Sample');
    expect(spec.paths['/api/data'].get['x-access']).toBe('AUTHENTICATED');
  });

  it('overwrites servers to the app API base rather than the YAML hardcoded localhost (ADR-3)', async () => {
    apiModule.BASE = 'https://lan-host:3000';
    authedFetch.mockResolvedValue(okResponse(SAMPLE_YAML));

    const spec = await loadContract();

    expect(spec.servers).toEqual([{ url: 'https://lan-host:3000' }]);
    apiModule.BASE = '';
  });

  it('falls back to window.location.origin when BASE is empty', async () => {
    apiModule.BASE = '';
    authedFetch.mockResolvedValue(okResponse(SAMPLE_YAML));

    const spec = await loadContract();

    expect(spec.servers).toEqual([{ url: window.location.origin }]);
  });
});

describe('loadContract — error mapping', () => {
  it.each([
    [401, /session expired/i],
    [403, /ADMIN/i],
    [404, /not found/i],
    [500, /could not serve/i],
  ])('maps status %i to a distinct, legible message', async (status, expected) => {
    authedFetch.mockResolvedValue(errorResponse(status));

    await expect(loadContract()).rejects.toThrow(expected);
  });

  it('throws a LoadContractError carrying the status code', async () => {
    authedFetch.mockResolvedValue(errorResponse(403));

    await expect(loadContract()).rejects.toMatchObject({
      name: 'LoadContractError',
      status: 403,
    });
    expect(LoadContractError).toBeDefined();
  });
});
