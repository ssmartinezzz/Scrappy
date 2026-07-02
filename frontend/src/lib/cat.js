// Mirror of ml_pipeline.py `norm_cat`: lowercase + strip accents/tildes. Product
// categories (Title Case, e.g. "Medias") must be normalized this way before
// looking them up in `catStats`, whose keys come from the ML pipeline already
// normalized (e.g. "medias"). Without this, every catStats lookup silently
// misses and category stats (price bar, pack savings %) never render.
export function normCat(raw) {
  if (!raw) return 'general';
  return String(raw)
    .toLowerCase()
    .trim()
    .replace(/á/g, 'a').replace(/é/g, 'e').replace(/í/g, 'i')
    .replace(/ó/g, 'o').replace(/ú/g, 'u').replace(/ü/g, 'u')
    .replace(/ñ/g, 'n')
    .trim();
}

// Deterministic URL slug for a category name, built on top of normCat's
// lowercase+accent-strip so it stays consistent with the catStats lookup key.
// Non-alphanumeric runs become a single "-", trimmed at both ends.
export function slugify(raw) {
  return normCat(raw)
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

// Reverse-resolves a URL slug back to its canonical category entry from the
// live /api/mejores category list (there is no server-side slug enum).
// First-match-wins on collision (unlikely with current Title-Case names);
// logs a warning so a real collision doesn't fail silently.
export function canonicalFromSlug(slug, cats) {
  if (!slug || !Array.isArray(cats)) return null;
  const matches = cats.filter(cat => slugify(cat.categoria) === slug);
  if (matches.length > 1) {
    console.warn(`canonicalFromSlug: slug "${slug}" matches multiple categories — using first match ("${matches[0].categoria}")`);
  }
  return matches[0] || null;
}
