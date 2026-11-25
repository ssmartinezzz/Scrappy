package ar.scraper.db;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroCatalogo;
import ar.scraper.classification.RubroCatalogo.CategoriaRubro;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.PersistenciaException;
import ar.scraper.testsupport.TestRubros;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Base de datos")
@Feature("Rubro por tablas")
@DisplayName("The migrated rubro tables and CategoryGroups say the same thing")
class RubroTaxonomiaAgreementTest extends PostgresTestBase {

    private static final Path RUBROS_JS = Path.of("..", "frontend", "src", "lib", "rubros.js");
    private static final Pattern FILA_JS = Pattern.compile("\\{\\s*key:\\s*'([^']*)'[^}]*label:\\s*'([^']*)'");

    private RubroCatalogo desdeLaBase() {
        return new JdbcRubroSource(dataSource()).cargar();
    }

    @Test
    @DisplayName("rubros, category rows and category indices are identical in both directions")
    void tablesAgreeWithCategoryGroups() {
        RubroCatalogo base = desdeLaBase();
        RubroCatalogo grupos = TestRubros.catalogo();

        assertThat(base.rubros()).as("rubro rows, in order").containsExactlyElementsOf(grupos.rubros());
        assertThat(claves(base)).as("category rows only in the database").isEqualTo(claves(grupos));
        assertThat(base.filas()).as("category rows").containsExactlyInAnyOrderElementsOf(grupos.filas());
        assertThat(base.indicesPorCategoria()).isEqualTo(grupos.indicesPorCategoria());
    }

    @Test
    @DisplayName("the canonical categories without a row are exactly Llavero, Conjunto and Otros")
    void categoriesWithoutRow() {
        RubroCatalogo base = desdeLaBase();

        Set<String> sinFila = CategoryGroups.canonicalCategories().stream()
                .filter(c -> base.fila(c).isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(sinFila).containsExactly("Conjunto", "Llavero", "Otros");
    }

    @Test
    @DisplayName("every category row names a canonical category: the table cannot hold a category the classifier never returns")
    void noRowOutsideTheCanon() {
        Set<String> canon = CategoryGroups.canonicalCategories();

        assertThat(desdeLaBase().filas()).extracting(CategoriaRubro::categoria).allMatch(canon::contains);
    }

    @Test
    @DisplayName("frontend/src/lib/rubros.js lists the table's rubros: same keys, labels and order, after the neutral entry")
    void frontendVocabularyAgrees() throws IOException {
        List<String> enElFrontend = FILA_JS.matcher(Files.readString(RUBROS_JS)).results()
                .map(m -> m.group(1) + "|" + m.group(2))
                .toList();
        List<String> enLaBase = desdeLaBase().rubros().stream()
                .map(r -> r.clave() + "|" + r.etiqueta())
                .toList();

        assertThat(enElFrontend).first().isEqualTo("|Todos");
        assertThat(enElFrontend.subList(1, enElFrontend.size())).containsExactlyElementsOf(enLaBase);
    }

    @Test
    @DisplayName("a failed read fails loudly instead of returning an empty taxonomy")
    void loadFailureFails() {
        DriverManagerDataSource roto = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "u", "p");

        assertThatThrownBy(() -> new JdbcRubroSource(roto).cargar()).isInstanceOf(PersistenciaException.class);
    }

    private static Set<String> claves(RubroCatalogo catalogo) {
        return catalogo.filas().stream()
                .map(f -> f.categoria() + "|" + f.subCategoria())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
