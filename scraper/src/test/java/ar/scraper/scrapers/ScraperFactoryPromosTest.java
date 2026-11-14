package ar.scraper.scrapers;

import ar.scraper.promos.BannerSitePromoSource;
import ar.scraper.promos.SitePromoSource;
import ar.scraper.promos.TiendanubeSitePromoSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScraperFactory — site promo source by platform, behind the kill switch")
class ScraperFactoryPromosTest {

    @ParameterizedTest
    @ValueSource(strings = {"tiendanube", "hadriatica", "monkyforce"})
    void tiendanubeYSusDerivadas(String plataforma) {
        assertThat(ScraperFactory.fuenteDePromos(plataforma, true)).isInstanceOf(TiendanubeSitePromoSource.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vtex", "woocommerce", "shopify", "magento", "vaypol", "fullh4rd", "morashop", ""})
    void todoLoDemasLeeBanners(String plataforma) {
        assertThat(ScraperFactory.fuenteDePromos(plataforma, true)).isInstanceOf(BannerSitePromoSource.class);
    }

    @Test
    void conElInterruptorApagadoNingunaLee() {
        assertThat(ScraperFactory.fuenteDePromos("tiendanube", false)).isSameAs(SitePromoSource.NINGUNA);
        assertThat(ScraperFactory.fuenteDePromos("vtex", false)).isSameAs(SitePromoSource.NINGUNA);
    }
}
