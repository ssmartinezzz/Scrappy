// Anti-rot test with a negative control: an EMPTY deny-list would pass a
// subset check vacuously, so four separate `it()` blocks guard against that
// instead of one combined assertion.
//
// apidocs-public-filtered-document: the exact count went 10 -> 3. The seven
// that left were `x-access: ADMIN` operations, which the backend now strips
// from the document it serves, so they never render a panel to deny; the
// three that stay are the auth operations, which are not ADMIN and therefore
// still reach the page. This guard keeps reading the full checked-in
// contract — a path rename is what it is here to catch.
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from 'yaml';
import { DENY_LIST, operationKey } from './nonExecutableOperations';

const HTTP_METHODS = new Set(['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS', 'HEAD']);

// Anchored to this module, never vitest's cwd (cwd differs between npm test,
// CI, and run-e2e.sh).
const HERE = dirname(fileURLToPath(import.meta.url));

function loadContract() {
  const path = resolve(HERE, '../../../../docs/openapi.yaml');
  return parse(readFileSync(path, 'utf8'));
}

function flattenedContractKeys(contract) {
  const keys = [];
  for (const [path, methods] of Object.entries(contract.paths ?? {})) {
    for (const method of Object.keys(methods ?? {})) {
      if (HTTP_METHODS.has(method.toUpperCase())) {
        keys.push(operationKey(method, path));
      }
    }
  }
  return keys;
}

/** The same resolver the plugin and this test both rely on: does `key` name a real operation? */
function resolvesToARealOperation(key, contractKeys) {
  return contractKeys.includes(key);
}

describe('nonExecutableOperations — anti-rot guard (design.md decision 4)', () => {
  it('1. the deny-list is not vacuous: exactly 3 keys, every reason non-empty, every verb real', () => {
    expect(DENY_LIST).toHaveLength(3);

    for (const entry of DENY_LIST) {
      expect(typeof entry.reason).toBe('string');
      expect(entry.reason.trim().length).toBeGreaterThan(0);

      const [verb] = entry.key.split(' ');
      expect(HTTP_METHODS.has(verb)).toBe(true);
    }
  });

  it('2. the contract is not vacuous: more than 40 flattened METHOD path keys', () => {
    const contract = loadContract();
    const keys = flattenedContractKeys(contract);

    expect(keys.length).toBeGreaterThan(40);
  });

  it('3. every deny-listed key resolves to a real operation in the contract', () => {
    const contract = loadContract();
    const contractKeys = flattenedContractKeys(contract);

    const offenders = DENY_LIST
      .map(entry => entry.key)
      .filter(key => !resolvesToARealOperation(key, contractKeys));

    expect(offenders).toEqual([]);
  });

  it('4. positive control: the resolver DOES report a bogus, never-denied key as unresolved', () => {
    const contract = loadContract();
    const contractKeys = flattenedContractKeys(contract);
    const bogusKey = operationKey('GET', '/api/does-not-exist');

    expect(resolvesToARealOperation(bogusKey, contractKeys)).toBe(false);
  });
});
