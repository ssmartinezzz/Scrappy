package ar.scraper.db;

import ar.scraper.catalog.HistorialEntry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Legacy ML price history import")
@Story("HistorialImportPort.importarCambios")
@DisplayName("HistorialRepository — one-shot import of legacy price changes")
class HistorialImportTest extends PostgresTestBase {

    private HistorialRepository repo;

    @BeforeEach
    void setUp() {
        repo = new HistorialRepository(dataSource());
        TestDatabaseServices.create(dataSource()).upsertProductos(List.of(Product.builder()
                .sitio("Sitio").nombre("Producto").precio(100.0).precioOriginal(null)
                .url("http://site/a").imagenUrl("http://img.example/x.jpg").categoria("Remera")
                .genero("unisex").talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Nike")
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build()));
        // el upsert deja el punto de hoy; los tests parten de una tabla de historial vacía
        new org.springframework.jdbc.core.JdbcTemplate(dataSource()).update("DELETE FROM precio_historico");
    }

    private static Map<String, List<HistorialEntry>> cambios(Object... urlYPuntos) {
        var m = new LinkedHashMap<String, List<HistorialEntry>>();
        for (int i = 0; i < urlYPuntos.length; i += 2) {
            @SuppressWarnings("unchecked")
            var puntos = (List<HistorialEntry>) urlYPuntos[i + 1];
            m.put((String) urlYPuntos[i], puntos);
        }
        return m;
    }

    @Test
    @DisplayName("inserts the changes of known products and skips urls missing from productos")
    void insertaSoloLosProductosConocidos() {
        int n = repo.importarCambios(cambios(
                "http://site/a", List.of(new HistorialEntry("2026-10-01", 120.0),
                                         new HistorialEntry("2026-10-05", 100.0)),
                "http://site/huerfana", List.of(new HistorialEntry("2026-10-01", 9.0))));

        assertThat(n).isEqualTo(2);
        assertThat(repo.getHistorialPrecios("http://site/a")).containsExactly(
                new HistorialEntry("2026-10-01", 120.0), new HistorialEntry("2026-10-05", 100.0));
        assertThat(repo.getHistorialPrecios("http://site/huerfana")).isEmpty();
    }

    @Test
    @DisplayName("is idempotent and never overwrites what the table already holds")
    void esIdempotenteYNoPisaLoExistente() {
        var datos = cambios("http://site/a", List.of(new HistorialEntry("2026-10-01", 120.0)));
        repo.importarCambios(datos);

        int segunda = repo.importarCambios(cambios(
                "http://site/a", List.of(new HistorialEntry("2026-10-01", 999.0),
                                         new HistorialEntry("2026-10-02", 110.0))));

        assertThat(segunda).isEqualTo(1);
        assertThat(repo.getHistorialPrecios("http://site/a")).containsExactly(
                new HistorialEntry("2026-10-01", 120.0), new HistorialEntry("2026-10-02", 110.0));
    }

    @Test
    @DisplayName("an empty import touches nothing")
    void vacioNoHaceNada() {
        assertThat(repo.importarCambios(Map.of())).isZero();
    }
}
