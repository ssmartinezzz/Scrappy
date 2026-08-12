package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import ar.scraper.pages.BasePage;
import java.util.Set;

public class ScraperFactory {

    // "forever" es Shopify (forever.com.ar/products.json responde 200) pese a no
    // usar dominio myshopify.com; sin esta entrada cae al default Tiendanube y
    // scrapea 0 productos. foreverbstrd y barnes son Tiendanube reales — no agregarlos.
    // Package-visible-turned-public (close-1nf-and-3nf-foundation, design DD3):
    // SitioSeedSyncTest cross-checks V18's seeded `sitio.plataforma` against
    // these exact 8 name-sets, bidirectionally — pure relocation of visibility,
    // no behavior change, same rationale SiteClassification's sets are public.
    public static final Set<String> SHOPIFY_NOMBRES = Set.of("freres", "vcp", "forever");
    public static final Set<String> VTEX_NOMBRES    = Set.of("sporting");
    public static final Set<String> VAYPOL_NOMBRES  = Set.of("vaypol", "city");
    public static final Set<String> WC_NOMBRES       = Set.of("dcshoes", "woocommerce");
    public static final Set<String> MONKYFORCE_NOMBRES = Set.of("monkyforce");
    public static final Set<String> MAXIMUS_NOMBRES  = Set.of("maximus");
    public static final Set<String> FULLH4RD_NOMBRES = Set.of("fullh4rd");
    public static final Set<String> CG_NOMBRES       = Set.of("compragamer");

    /**
     * Single source of truth for the name-based half of {@link #crear}'s
     * platform detection (the URL-based {@code myshopify.com}/
     * {@code vtexcommercestable.com.br} fallbacks are not name-based and do
     * not apply to a static seed table) — platform label to its name-set.
     */
    public static final java.util.Map<String, Set<String>> PLATAFORMA_NOMBRES = java.util.Map.of(
            "shopify", SHOPIFY_NOMBRES,
            "vtex", VTEX_NOMBRES,
            "vaypol", VAYPOL_NOMBRES,
            "woocommerce", WC_NOMBRES,
            "monkyforce", MONKYFORCE_NOMBRES,
            "maximus", MAXIMUS_NOMBRES,
            "fullh4rd", FULLH4RD_NOMBRES,
            "compragamer", CG_NOMBRES
    );

    public static BaseScraper crear(ScraperConfig config, SiteConfig site) {
        String n       = site.nombre().toLowerCase();
        String display = Character.toUpperCase(n.charAt(0)) + n.substring(1);

        if (WC_NOMBRES.contains(n))
            return new WooCommerceScraper(config, display, site.url());

        if (MAXIMUS_NOMBRES.contains(n))
            return new MaximusScraper(config, display, site.url());

        if (FULLH4RD_NOMBRES.contains(n))
            return new FullH4rdScraper(config, display, site.url());

        if (CG_NOMBRES.contains(n))
            return new CompraGamerScraper(config, display, site.url());


        if (VAYPOL_NOMBRES.contains(n))
            return new VaypolScraper(config, display, site.url());

        if (VTEX_NOMBRES.contains(n)
                || site.url().contains("vtexcommercestable.com.br")
                || site.url().contains("vteximg.com.br"))
            return new VtexScraper(config, display, site.url());

        if (SHOPIFY_NOMBRES.contains(n)
                || site.url().contains("myshopify.com"))
            return new ShopifyScraper(config, display, site.url());

        if (MONKYFORCE_NOMBRES.contains(n))
            return new MonkyforceScraper(config, display, site.url(), site.extraUrls());

        return new TiendanubeScraper(config, display, site.url(), site.extraUrls());
    }

    // ─── Favoritos (Fase 1: Shopify + VTEX Legacy) ────────────────────────────

    public enum FavPlatform { SHOPIFY, VTEX, UNSUPPORTED }

    /**
     * Resuelve la plataforma de un favorito a partir del nombre de sitio y la
     * URL del producto, reutilizando las mismas reglas de detección que crear().
     */
    public static FavPlatform plataformaDeFavorito(String sitio, String productUrl) {
        String n = sitio == null ? "" : sitio.toLowerCase();
        String u = productUrl == null ? "" : productUrl;
        if (VTEX_NOMBRES.contains(n) || u.contains("vtexcommercestable.com.br")
                || u.contains("vteximg.com.br")) return FavPlatform.VTEX;
        if (SHOPIFY_NOMBRES.contains(n) || u.contains("myshopify.com"))
            return FavPlatform.SHOPIFY;
        return FavPlatform.UNSUPPORTED;
    }

    /**
     * Resuelve un scraper Fase-1 (Shopify/VTEX) para un favorito, o null si
     * la plataforma no está soportada (TN/Vaypol/City/WooCommerce → Fase 2 no-op).
     */
    public static BaseScraper crearParaFavorito(ScraperConfig config, String sitio, String productUrl) {
        String dom = BasePage.dominioPublico(productUrl);
        return switch (plataformaDeFavorito(sitio, productUrl)) {
            case SHOPIFY -> new ShopifyScraper(config, sitio, dom);
            case VTEX    -> new VtexScraper(config, sitio, dom);
            case UNSUPPORTED -> null;
        };
    }
}
