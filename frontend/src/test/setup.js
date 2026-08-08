import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// React Testing Library does not auto-clean when `globals` is on in some
// setups; unmounting explicitly keeps one test's DOM out of the next one's
// queries, which otherwise produces "found multiple elements" failures that
// look like component bugs.
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
