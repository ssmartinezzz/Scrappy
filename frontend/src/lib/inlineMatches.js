// Finds the inline tokens renderRichText understands, in a single left-to-right
// pass.
//
// This replaces one big alternation regex:
//
//   (\*\*[^*\n]+\*\*)|(`[^`\n]+`)|(\[[^\]\n]+\]\([^)\s(]+\))|(https?:\/\/[^\s<>()[\]]+)
//
// Its alternatives stop scanning only at a closing delimiter, so every unclosed
// opener ("[", "**", "`") rescans the rest of the line before failing — quadratic
// on a line of openers. Here each scan result is remembered and reused by the
// next opener that lands inside the same stretch, so the whole line costs O(n).
// The matches are the regex's, token for token (see inlineMatches.test.js).

const WHITESPACE = /\s/;

/**
 * A memoised "first index >= p whose char satisfies `isStop`" (line.length when
 * none). Later openers ask from a larger p, almost always inside the stretch
 * already scanned, so they get the stored answer.
 */
function stopScanner(line, isStop) {
  let from = -1;
  let found = -1;
  return (p) => {
    if (from >= 0 && p >= from && p <= found) return found;
    let i = p;
    while (i < line.length && !isStop(line[i])) i++;
    from = p;
    found = i;
    return i;
  };
}

/**
 * Yields `{ index, token }` for every bold, code, link and bare-URL token in
 * `line`, left to right and non-overlapping.
 */
export function* inlineMatches(line) {
  const n = line.length;
  const toStar = stopScanner(line, c => c === '*' || c === '\n');
  const toTick = stopScanner(line, c => c === '`' || c === '\n');
  const toBracket = stopScanner(line, c => c === ']' || c === '\n');
  const toParen = stopScanner(line, c => c === ')' || c === '(' || WHITESPACE.test(c));
  const toUrlStop = stopScanner(line, c => WHITESPACE.test(c) || '<>()[]'.includes(c));

  // Each helper returns the end (exclusive) of a token opening at `i`, or -1.
  const bold = (i) => {
    if (line[i + 1] !== '*') return -1;
    const j = toStar(i + 2);
    return j > i + 2 && line[j] === '*' && line[j + 1] === '*' ? j + 2 : -1;
  };
  const code = (i) => {
    const j = toTick(i + 1);
    return j > i + 1 && line[j] === '`' ? j + 1 : -1;
  };
  const link = (i) => {
    const j = toBracket(i + 1);
    if (!(j > i + 1 && line[j] === ']' && line[j + 1] === '(')) return -1;
    const k = toParen(j + 2);
    return k > j + 2 && line[k] === ')' ? k + 1 : -1;
  };
  const url = (i) => {
    if (!line.startsWith('http', i)) return -1;
    let p = i + 4;
    if (line[p] === 's') p++;
    if (!line.startsWith('://', p)) return -1;
    const q = toUrlStop(p + 3);
    return q > p + 3 ? q : -1;
  };

  for (let i = 0; i < n; i++) {
    const c = line[i];
    let end = -1;
    if (c === '*') end = bold(i);
    else if (c === '`') end = code(i);
    else if (c === '[') end = link(i);
    else if (c === 'h') end = url(i);
    if (end > i) {
      yield { index: i, token: line.slice(i, end) };
      i = end - 1;
    }
  }
}
