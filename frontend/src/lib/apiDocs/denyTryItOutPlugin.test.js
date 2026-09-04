// Pure-function test, no swagger-ui mount: `execute` is the actual
// enforcement chokepoint.
//
// apidocs-public-filtered-document: the denied example moved from
// `DELETE /api/db/productos` to `POST /api/auth/login`. The former is an
// `x-access: ADMIN` operation, and the backend now strips those from the
// served document, so it left the deny-list — a fixture naming an operation
// the console can never render proves nothing about the plugin.
import { describe, it, expect, vi } from 'vitest';
import { denyTryItOutPlugin, reasonForProps } from './denyTryItOutPlugin';

function executeWrap() {
  const plugin = denyTryItOutPlugin();
  return plugin.statePlugins.spec.wrapActions.execute;
}

describe('denyTryItOutPlugin — execute enforcement', () => {
  it('does NOT dispatch the original action for a denied key', () => {
    const oriAction = vi.fn();
    const wrapped = executeWrap()(oriAction);

    const result = wrapped({ path: '/api/auth/login', method: 'POST' });

    expect(oriAction).not.toHaveBeenCalled();
    expect(result).toBeUndefined();
  });

  it('dispatches the original action normally for an allowed key', () => {
    const oriAction = vi.fn().mockReturnValue('dispatched');
    const wrapped = executeWrap()(oriAction);

    const args = { path: '/api/data', method: 'GET' };
    const result = wrapped(args);

    expect(oriAction).toHaveBeenCalledWith(args);
    expect(result).toBe('dispatched');
  });

  it('is case-insensitive on the method, matching operationKey', () => {
    const oriAction = vi.fn();
    const wrapped = executeWrap()(oriAction);

    wrapped({ path: '/api/auth/login', method: 'post' });

    expect(oriAction).not.toHaveBeenCalled();
  });
});

describe('denyTryItOutPlugin — reasonForProps (the OperationContainer seam)', () => {
  it('returns the stated reason for a denied operation', () => {
    expect(reasonForProps({ method: 'POST', path: '/api/auth/login' }))
      .toMatch(/Issues a new token pair/);
  });

  it('returns null for an allowed operation', () => {
    expect(reasonForProps({ method: 'GET', path: '/api/data' })).toBeNull();
  });

  it('returns null for incomplete props rather than throwing', () => {
    expect(reasonForProps({})).toBeNull();
    expect(reasonForProps(null)).toBeNull();
  });
});
