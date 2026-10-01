import { describe, expect, it } from 'vitest';

import { inlineMatches } from '@/lib/inlineMatches';

// The regex inlineMatches replaced. It is the specification: the hand-written
// scanner must return exactly what this returns, on every input.
const ORIGINAL = /(\*\*[^*\n]+\*\*)|(`[^`\n]+`)|(\[[^\]\n]+\]\([^)\s(]+\))|(https?:\/\/[^\s<>()[\]]+)/g;

function withRegex(line) {
  const out = [];
  ORIGINAL.lastIndex = 0;
  let m;
  while ((m = ORIGINAL.exec(line)) !== null) out.push({ index: m.index, token: m[0] });
  return out;
}

const withScanner = (line) => [...inlineMatches(line)];

// Deterministic PRNG, so a failure reproduces.
function mulberry32(seed) {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const PIECES = [
  '*', '**', '`', '[', ']', '(', ')', '](', 'a', 'b', ' ', '\n', '\t', ' ', ' ',
  'h', 'http', 'https', '://', 'http://', 'https://', 'x.y', '/', '<', '>', '.', '1',
];

describe('inlineMatches', () => {
  it('returns the original regex matches on representative lines', () => {
    for (const line of [
      '**bold**', 'a **b** c', '`code`', '[ver](https://a.test/x)', 'see https://a.test/x. ok',
      'http://a.test', '**unclosed', '****', '`unclosed', '[no](link', '[a]()', 'https:// alone',
      '[x](a(b))', '[x](a b)', '**a***', '***a***', '[a][b](c)', 'x https://a.b/(c) y',
      '[l](https://a.b) and https://c.d', '`a`**b**[c](d)',
    ]) {
      expect(withScanner(line), line).toEqual(withRegex(line));
    }
  });

  it('agrees with the original regex on 30000 random lines', () => {
    const rnd = mulberry32(20260101);
    for (let n = 0; n < 30_000; n++) {
      const len = 1 + Math.floor(rnd() * 14);
      let line = '';
      for (let i = 0; i < len; i++) line += PIECES[Math.floor(rnd() * PIECES.length)];
      expect(withScanner(line), JSON.stringify(line)).toEqual(withRegex(line));
    }
  });

  it('stays linear on a long line of unclosed openers', () => {
    const unit = '[a**b(`';
    expect(withScanner(unit.repeat(500))).toEqual(withRegex(unit.repeat(500)));

    const line = unit.repeat(60_000);
    const t0 = performance.now();
    withScanner(line);
    expect(performance.now() - t0).toBeLessThan(1000);
  });
});
