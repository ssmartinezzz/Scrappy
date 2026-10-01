// Pins what four regexes accept today, so rewriting them to a non-backtracking
// form (Sonar S8786) cannot change a single match.
import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';

import { slugify } from '@/lib/cat';
import { accuracyFromMsg } from '@/lib/mlTrainingMsg';
import { renderRichText } from '@/lib/richText';

const html = (text) => renderToStaticMarkup(<div>{renderRichText(text)}</div>);

describe('accuracyFromMsg', () => {
  it.each([
    ['Accuracy final: 92.5%', '92.5'],
    ['acc 80 %', '80'],
    ['93%', '93'],
    ['12.%', '12.'],
    ['a 5% then 6%', '5'],
    ['v2.1 done, 88.25% top', '88.25'],
    ['.5%', '5'],
    ['no number here', null],
    ['12 but no percent', null],
    ['', null],
    [undefined, null],
    [null, null],
  ])('%j -> %j', (msg, expected) => {
    expect(accuracyFromMsg(msg)).toBe(expected);
  });

  it('stays linear on a long run of digits with no percent sign', () => {
    const t0 = performance.now();
    expect(accuracyFromMsg('9'.repeat(40_000))).toBeNull();
    expect(performance.now() - t0).toBeLessThan(500);
  });
});

describe('slugify trimming', () => {
  it.each([
    ['--a--', 'a'],
    ['  ¡Ofertas!  ', 'ofertas'],
    ['!!!', ''],
    ['', 'general'],
    ['a-b', 'a-b'],
    ['-a', 'a'],
    ['a-', 'a'],
    ['Ropa Interior / Medias', 'ropa-interior-medias'],
  ])('%j -> %j', (raw, expected) => {
    expect(slugify(raw)).toBe(expected);
  });
});

describe('renderRichText bullets', () => {
  it.each([
    ['- item', '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span>item</span></span></div>'],
    ['* item', '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span>item</span></span></div>'],
    ['   - indented', '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span>indented</span></span></div>'],
    ['-  two spaces', '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span>two spaces</span></span></div>'],
    ['-\titem', '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span>item</span></span></div>'],
    ['- ', '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span></span></span></div>'],
    ['-no space', '<div><span>-no space</span></div>'],
    ['not - a bullet', '<div><span>not - a bullet</span></div>'],
    ['-', '<div><span>-</span></div>'],
  ])('%j', (text, expected) => {
    expect(html(text)).toBe(expected);
  });

  it('a line terminator after the content keeps it out of the bullet form', () => {
    expect(html('- a b')).toBe('<div><span>- a b</span></div>');
    expect(html('- a\rb')).toBe('<div><span>- a\rb</span></div>');
  });

  it('a terminator inside the whitespace run still opens a bullet', () => {
    expect(html('-   item')).toBe(
      '<div><span class="flex gap-1.5"><span aria-hidden="true">•</span><span>item</span></span></div>',
    );
  });

  it('stays fast on a long whitespace run that fails to match', () => {
    const t0 = performance.now();
    html(`-${' '.repeat(40_000)}x y`);
    expect(performance.now() - t0).toBeLessThan(500);
  });
});

describe('renderRichText inline tokens', () => {
  const A = 'font-semibold underline decoration-dotted underline-offset-2 hover:decoration-solid';
  const link = (href, label) =>
    `<a href="${href}" target="_blank" rel="noopener noreferrer" class="${A}">${label}</a>`;

  it.each([
    ['**bold**', '<div><span><strong class="font-bold">bold</strong></span></div>'],
    ['a **b** c', '<div><span>a <strong class="font-bold">b</strong> c</span></div>'],
    ['`code`', '<div><span><code class="rounded bg-s3 px-1 py-[1px] font-mono text-[.68rem]">code</code></span></div>'],
    ['[ver](https://a.test/x)', `<div><span>${link('https://a.test/x', 'ver')}</span></div>`],
    ['see https://a.test/x. ok', `<div><span>see ${link('https://a.test/x.', 'https://a.test/x.')} ok</span></div>`],
    ['http://a.test', `<div><span>${link('http://a.test', 'http://a.test')}</span></div>`],
    ['**unclosed', '<div><span>**unclosed</span></div>'],
    ['****', '<div><span>****</span></div>'],
    ['`unclosed', '<div><span>`unclosed</span></div>'],
    ['[no](link', '<div><span>[no](link</span></div>'],
    ['[a]()', '<div><span>[a]()</span></div>'],
    ['https:// alone', '<div><span>https:// alone</span></div>'],
    ['[x](javascript:alert(1))', '<div><span>[x](javascript:alert(1))</span></div>'],
  ])('%j', (text, expected) => {
    expect(html(text)).toBe(expected);
  });

  it('stays fast on many unclosed openers', () => {
    const t0 = performance.now();
    html('[**a'.repeat(60_000));
    expect(performance.now() - t0).toBeLessThan(1000);
  });
});
