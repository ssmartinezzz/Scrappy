package ar.scraper.pages;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plumbing tests for the page cap that bounds {@code scrapeJs}'s pagination
 * loop (add-morashop-and-fix-entreno-pagination, D1).
 *
 * <p>What is actually at risk here is the WIRING, not the arithmetic. The loop
 * bound itself is one token; the way this breaks in practice is a value that
 * never reaches the page because a constructor in the chain dropped it. So
 * these tests assert that the cap arrives, and that the pre-existing
 * constructors still compile and still mean the default.
 *
 * <p>Why the default is 60 rather than the old 25: entreno's catalogue is 53
 * pages of 12 products and page 54 returns zero, so the old ceiling truncated
 * it at roughly half. The cap stays a safety belt — the real terminator is the
 * unchanged two-consecutive-empty-pages check inside {@code scrapeJs}, which
 * fires on entreno because Tiendanube serves an empty page past the end rather
 * than repeating the last one the way osCommerce does.
 *
 * <p>No browser: every constructor here takes a {@code null} Page, the same
 * shape {@link TiendanubeNombreSelectorTest} uses.
 */
@Epic("Scraping Engine")
@Feature("TiendaNube Parsing")
@Story("Pagination cap")
@DisplayName("TiendanubePage — page cap wiring")
class TiendanubePageMaxPaginasTest {

    @Test
    @DisplayName("el default publico es 60, la unica definicion del numero")
    void defaultIsSixty() {
        assertThat(TiendanubePage.MAX_PAGINAS_DEFAULT).isEqualTo(60);
    }

    @Test
    @DisplayName("el constructor de 6 args sigue compilando y significa el default")
    void sixArgConstructorKeepsMeaningTheDefault() {
        TiendanubePage p = new TiendanubePage(null, 0, "x", "https://x.com", 0, 1);
        assertThat(p.maxPaginas()).isEqualTo(TiendanubePage.MAX_PAGINAS_DEFAULT);
    }

    @Test
    @DisplayName("el constructor de 7 args sigue compilando y significa el default")
    void sevenArgConstructorKeepsMeaningTheDefault() {
        TiendanubePage p = new TiendanubePage(null, 0, "x", "https://x.com", 0, 1, List.of());
        assertThat(p.maxPaginas()).isEqualTo(TiendanubePage.MAX_PAGINAS_DEFAULT);
    }

    @Test
    @DisplayName("el constructor explicito transporta el cap hasta la pagina")
    void explicitCapReachesThePage() {
        TiendanubePage p = new TiendanubePage(null, 0, "x", "https://x.com", 0, 1, List.of(), 120);
        assertThat(p.maxPaginas()).isEqualTo(120);
    }

    @Test
    @DisplayName("una subclase hereda el cap sin re-implementar nada")
    void subclassInheritsTheCap() {
        MonkyforcePage mf = new MonkyforcePage(null, 0, "Monkyforce",
                "https://www.monkyforce.com/productos/", 0, 1, List.of(), 45);
        assertThat(mf.maxPaginas()).isEqualTo(45);
    }
}
