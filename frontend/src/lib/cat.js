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
