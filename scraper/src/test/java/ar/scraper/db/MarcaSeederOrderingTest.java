package ar.scraper.db;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation extension, Phase 2 (design E4). Classpath-only
 * — pins {@link MarcaSeeder}'s ordering so a future second
 * {@code ApplicationRunner} cannot silently push it later without a test
 * going red. The ordering hazard is real: if the brand seed ran after a
 * scrape reached {@code sp_upsert_run}, a new curated brand's FK would
 * reject the very first write inside {@code ProductRepository}'s
 * swallowed-error path — surfacing as {@code "0 nuevos"}, never as an error.
 */
@Epic("Persistence")
@Feature("Brand")
@Story("MarcaSeeder runs before any scrape-capable bean")
@DisplayName("MarcaSeeder — ApplicationRunner ordering (no DB)")
class MarcaSeederOrderingTest {

    @Test
    @DisplayName("MarcaSeeder implementa ApplicationRunner")
    void marcaSeederIsAnApplicationRunner() {
        assertThat(ApplicationRunner.class).isAssignableFrom(MarcaSeeder.class);
    }

    @Test
    @DisplayName("MarcaSeeder está anotado @Order(HIGHEST_PRECEDENCE)")
    void marcaSeederRunsFirst() {
        Order order = MarcaSeeder.class.getAnnotation(Order.class);
        assertThat(order).as("MarcaSeeder debe declarar @Order").isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
