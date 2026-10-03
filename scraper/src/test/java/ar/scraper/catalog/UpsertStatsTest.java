package ar.scraper.catalog;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Upsert")
@Story("A run totals its per-site upserts")
@DisplayName("UpsertStats — suma")
class UpsertStatsTest {

    @Test
    @DisplayName("adds every counter")
    void sumaCadaContador() {
        UpsertStats total = new UpsertStats(3, 2, 10, 0).sumar(new UpsertStats(1, 4, 5, 7));

        assertThat(total.nuevos()).isEqualTo(4);
        assertThat(total).isEqualTo(new UpsertStats(4, 6, 15, 7));
    }
}
