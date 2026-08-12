package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryAliases;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `close-category-vocabulary` — el clasificador y la migración de datos tienen
 * que estar de acuerdo sobre a dónde va cada alias.
 *
 * <p>Son dos idiomas distintos escribiendo la misma tabla: {@code
 * CategoryAliases} en Java para los productos que ENTRAN, y {@code V12} en SQL
 * para los que YA estaban. Si divergen, el catálogo queda con dos categorías
 * para la misma cosa y nadie se entera hasta que un filtro devuelve de menos.</p>
 */
@Epic("Persistence")
@Feature("Category classification")
@Story("The alias table and the data migration agree")
@DisplayName("V12 — los alias del clasificador y los de la migración coinciden")
class CategoryAliasesMigrationSyncTest {

    private static final String V12 = "/db/migration/V12__close_category_vocabulary.sql";

    @Test
    @DisplayName("Cada alias de CategoryAliases aparece en el UPDATE que lo lleva a su destino")
    void cadaAliasEstaEnLaMigracion() {
        String sql = leer(V12);
        // Sólo los UPDATE, para no matchear los ejemplos del comentario de arriba.
        String updates = sql.substring(sql.indexOf("UPDATE productos"));

        List<String> faltantes = new ArrayList<>();
        for (Map.Entry<String, String> alias : CategoryAliases.todos().entrySet()) {
            String origen = alias.getKey();
            String destino = alias.getValue();
            boolean cubierto = updates.lines()
                    .filter(l -> l.contains("= '" + destino + "'"))
                    .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains("'" + origen + "'"));
            if (!cubierto) faltantes.add(origen + " -> " + destino);
        }

        assertThat(faltantes)
                .as("alias que el clasificador aplica pero la migración no")
                .isEmpty();
    }

    private static String leer(String recurso) {
        try (InputStream in = CategoryAliasesMigrationSyncTest.class.getResourceAsStream(recurso)) {
            Objects.requireNonNull(in, "Falta el recurso: " + recurso);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
