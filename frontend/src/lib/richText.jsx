/**
 * Minimal inline-markdown renderer for LLM chat output.
 *
 * The catalog agent frequently answers with light markdown (`**remera**`,
 * `[texto](url)`) and bare product URLs, which used to render as literal
 * characters. This turns the common subset into React elements.
 *
 * SECURITY: this returns React nodes, never an HTML string, and never touches
 * `dangerouslySetInnerHTML` — React escapes every text node, so markup in model
 * output cannot become live DOM. Link hrefs are additionally restricted to
 * http/https so a `javascript:` or `data:` URL can never reach an anchor.
 * That combination is why no HTML sanitizer dependency is needed here.
 *
 * Deliberately NOT supported (the agent does not emit these, and every extra
 * rule is more surface for little gain): headings, tables, images, blockquotes,
 * nested lists, and multi-line fenced code blocks.
 */

/** Only http/https may become a real link. */
const safeHref = (raw) => {
  try {
    return ['http:', 'https:'].includes(new URL(raw).protocol) ? raw : null;
  } catch {
    return null;
  }
};

// Order matters: links are matched before bare URLs so the URL inside
// [texto](url) is not also picked up as standalone text.
//
// The href part excludes `(` as well as `)`: a URL containing parentheses would
// otherwise match only up to the inner `)` and yield a silently truncated —
// therefore broken — link. Excluding `(` makes such a case fail to match at
// all, so it degrades to literal text instead of a link that looks fine and
// goes nowhere.
const INLINE = /(\*\*[^*\n]+\*\*)|(`[^`\n]+`)|(\[[^\]\n]+\]\([^)\s(]+\))|(https?:\/\/[^\s<>()[\]]+)/g;

const linkClass =
  'font-semibold underline decoration-dotted underline-offset-2 hover:decoration-solid';

function Link({ href, children }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={linkClass}>
      {children}
    </a>
  );
}

/** Splits one line into text + bold/code/link nodes. */
function parseInline(line, keyPrefix) {
  const nodes = [];
  let lastIndex = 0;
  let match;
  INLINE.lastIndex = 0;

  while ((match = INLINE.exec(line)) !== null) {
    if (match.index > lastIndex) nodes.push(line.slice(lastIndex, match.index));
    const [token] = match;
    const key = `${keyPrefix}-${match.index}`;

    if (token.startsWith('**')) {
      nodes.push(<strong key={key} className="font-bold">{token.slice(2, -2)}</strong>);
    } else if (token.startsWith('`')) {
      nodes.push(
        <code key={key} className="rounded bg-s3 px-1 py-[1px] font-mono text-[.68rem]">
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith('[')) {
      const split = token.indexOf('](');
      const label = token.slice(1, split);
      const href = safeHref(token.slice(split + 2, -1));
      // An unsafe href degrades to plain text — never a dead or dangerous link.
      nodes.push(href ? <Link key={key} href={href}>{label}</Link> : token);
    } else {
      const href = safeHref(token);
      nodes.push(href ? <Link key={key} href={href}>{token}</Link> : token);
    }
    lastIndex = match.index + token.length;
  }

  if (lastIndex < line.length) nodes.push(line.slice(lastIndex));
  return nodes;
}

const PRICE = /\$[\d.,]+/g;

/**
 * Emphasises the money amounts inside an otherwise plain sentence, returning
 * React nodes.
 *
 * Replaces the `dangerouslySetInnerHTML` + `String.replace` pattern that used to
 * build `<strong>` tags as raw HTML in `DetailPanel`. The strings themselves are
 * assembled locally from product data, but building HTML out of interpolated
 * catalog values is a standing injection hazard: the values ultimately originate
 * in scraped third-party store pages, so the day one of these sentences
 * interpolates a product name or brand instead of a category or a number, raw
 * HTML injection becomes live. Returning elements removes the hazard rather than
 * relying on today's inputs staying tame.
 *
 * @param {string} text plain sentence, no markup
 * @param {string} color CSS color applied to the highlighted amounts
 * @returns {import('react').ReactNode}
 */
export function highlightPrices(text, color) {
  if (typeof text !== 'string' || !text) return text ?? null;

  const nodes = [];
  let lastIndex = 0;
  let match;
  PRICE.lastIndex = 0;

  while ((match = PRICE.exec(text)) !== null) {
    if (match.index > lastIndex) nodes.push(text.slice(lastIndex, match.index));
    nodes.push(<strong key={match.index} style={{ color }}>{match[0]}</strong>);
    lastIndex = match.index + match[0].length;
  }
  if (lastIndex < text.length) nodes.push(text.slice(lastIndex));
  return nodes;
}

/**
 * Renders `text` as React nodes, preserving line breaks and rendering
 * `- `/`* ` prefixed lines as bullets.
 *
 * @param {string} text raw model output
 * @returns {import('react').ReactNode}
 */
export function renderRichText(text) {
  if (typeof text !== 'string' || !text) return text ?? null;

  return text.split('\n').map((line, i) => {
    const bullet = /^\s*[-*]\s+(.*)$/.exec(line);
    if (bullet) {
      return (
        <span key={i} className="flex gap-1.5">
          <span aria-hidden="true">•</span>
          <span>{parseInline(bullet[1], i)}</span>
        </span>
      );
    }
    // Blank lines become vertical space rather than collapsing away.
    if (!line.trim()) return <span key={i} className="h-1.5" />;
    return <span key={i}>{parseInline(line, i)}</span>;
  });
}
