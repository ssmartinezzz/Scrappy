package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * add-inpro-office-store. Mismo contrato que todo {@code V*RollbackRoundTripTest}:
 * una migración aplicada es byte-frozen, así que el rollback vive en
 * {@code docs/DATABASE.md} y este test ejecuta ESE bloque, verbatim, contra el
 * esquema real dentro de una transacción que siempre se revierte.
 *
 * <p>{@code V27} amplía TRES dominios cerrados a la vez —{@code rubro},
 * {@code rubro_forzado} y {@code plataforma}— y el orden del rollback importa:
 * la fila de seed de INPRO se borra ANTES de que los {@code CHECK} se angosten.
 * Al revés dejaría, por un instante, un {@code CHECK} más angosto que datos que
 * todavía lo violan.</p>
 *
 * <p>El segundo test fija la parte incómoda: pasado el primer scrape el
 * rollback <b>ya no está disponible</b>. Eso es prosa en {@code DATABASE.md} y
 * acá es ejecutable, que es la diferencia entre saberlo ahora y descubrirlo el
 * día que haya que revertir de verdad.</p>
 *
 * <p><b>add-morashop-and-fix-entreno-pagination, {@code CODE-2} declarado</b>:
 * editado por un cambio posterior que no toca V27. {@code V28} ensancha el
 * mismo CHECK a 13 y siembra una fila {@code morashop}, así que el bloque de
 * V27 ya no puede angostar solo: los rollbacks componen al revés y el de V28
 * corre primero, en los DOS tests.
 *
 * <p>En el segundo eso no es cosmético. Ese test afirma que el rollback falla
 * <i>por un producto de INPRO vivo</i>; con V28 aplicada también fallaría por
 * la fila {@code morashop}, y habría quedado en VERDE probando otra cosa. Un
 * test vacío es peor que uno rojo, porque nadie lo mira. Sacar morashop antes
 * —no tiene productos, sale limpio— deja que lo único que rompa sea INPRO.
 *
 * <p>Las aserciones de comportamiento no se tocaron: los tres dominios
 * post-rollback siguen siendo los mismos y el {@code hasMessageContaining} es
 * el mismo. Sólo se agregó una precondición y una aserción aditiva de
 * pre-estado.</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("V27 rollback narrows rubro, rubro_forzado and plataforma back, seed-first")
@DisplayName("V27 migration — the documented rollback actually runs")
class V27RollbackRoundTripTest extends PostgresTestBase {

    private static final String INPRO_URL = "https://inpro.ar/productos/silla-ergonomica-inpro";

    @Test
    @DisplayName("Sin productos scrapeados, el rollback borra el seed y angosta los tres CHECK")
    void rollbackDeletesSeedThenNarrowsTheThreeChecks() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(domainOf(st, "productos", "chk_productos_rubro_domain")).contains("oficina");
                assertThat(domainOf(st, "sitio", "sitio_rubro_forzado_check")).contains("oficina");
                assertThat(domainOf(st, "sitio", "sitio_plataforma_check")).contains("inpro");
                assertThat(sitioKeys(st)).contains("inpro");
                assertThat(domainOf(st, "sitio", "sitio_plataforma_check")).contains("morashop");

                // Los rollbacks componen al revés: V28 ensanchó este mismo
                // CHECK y sembró una fila `morashop`, así que el bloque de V27
                // no puede angostar por encima de ella.
                // add-zentra-and-mmartinez: V30 siembra Zentra con
                // rubro_forzado='oficina'. Mientras esa fila viva, el bloque
                // de V27 —que angosta sitio_rubro_forzado_check sacando justo
                // ese valor— es RECHAZADO por Postgres, que valida las filas
                // existentes al re-agregar el CHECK. Los rollbacks componen al
                // revés: de más nuevo a más viejo.
                st.execute(DocumentedRollback.sqlFor("V30"));
                st.execute(DocumentedRollback.sqlFor("V28"));
                st.execute(DocumentedRollback.sqlFor("V27"));

                assertThat(domainOf(st, "productos", "chk_productos_rubro_domain"))
                        .containsExactlyInAnyOrder("indumentaria", "tecnologia", "suplementos");
                assertThat(domainOf(st, "sitio", "sitio_rubro_forzado_check"))
                        .containsExactlyInAnyOrder("tecnologia", "suplementos");
                assertThat(domainOf(st, "sitio", "sitio_plataforma_check"))
                        .containsExactlyInAnyOrder(
                                "tiendanube", "shopify", "vtex", "vaypol", "woocommerce",
                                "monkyforce", "maximus", "fullh4rd", "compragamer",
                                "qloud", "oscommerce");
                assertThat(sitioKeys(st))
                        .as("inpro tenia 0 productos (nunca scrapeado) -> el NOT EXISTS lo deja borrar")
                        .doesNotContain("inpro");
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * La consecuencia honesta de la FK que {@code V23} le puso a
     * {@code productos.sitio_key}: con productos de INPRO vivos, el
     * {@code NOT EXISTS} protege la fila de {@code sitio}, y entonces angostar
     * {@code sitio_plataforma_check} choca contra esa misma fila. El rollback
     * NO es aplicable pasado el primer scrape, y retirar el sitio pasa a ser
     * {@code origen='historico'} (soft), no angostar el dominio — igual que en
     * {@code V24}. Si alguien "arregla" el bloque documentado para que corra
     * igual, pierde datos, y este test se pone rojo primero.
     */
    @Test
    @DisplayName("Con productos de INPRO vivos, el rollback del dominio ya no aplica — y falla ruidoso")
    void rollbackIsNoLongerAvailableOnceInproHasProducts() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // sitio_key es GENERATED ALWAYS (V23) — se deriva de `sitio`,
                // no se inserta. 'Inpro' -> 'inpro', que es la fila que V27 sembró.
                st.executeUpdate("""
                        INSERT INTO productos (url, sitio, nombre, precio, rubro)
                        VALUES ('%s', 'Inpro', 'Silla Ergonómica Pro', 999990, 'oficina')
                        """.formatted(INPRO_URL));
                assertThat(sitioKeyDe(st, INPRO_URL))
                        .as("la columna generada resuelve a la fila de sitio que V27 sembro")
                        .isEqualTo("inpro");

                // Sin esto el test seria VACUO: con V28 aplicada, el bloque de
                // V27 tambien tira por la fila `morashop`, y el assert de abajo
                // pasaria verde sin probar nada sobre INPRO. Se saca morashop
                // primero —no tiene productos, sale limpio— para que lo unico
                // que quede rompiendo sea el producto de INPRO.
                // add-zentra-and-mmartinez: V30 siembra Zentra con
                // rubro_forzado='oficina'. Mientras esa fila viva, el bloque
                // de V27 —que angosta sitio_rubro_forzado_check sacando justo
                // ese valor— es RECHAZADO por Postgres, que valida las filas
                // existentes al re-agregar el CHECK. Los rollbacks componen al
                // revés: de más nuevo a más viejo.
                st.execute(DocumentedRollback.sqlFor("V30"));
                st.execute(DocumentedRollback.sqlFor("V28"));

                assertThatThrownBy(() -> st.execute(DocumentedRollback.sqlFor("V27")))
                        .as("angostar el dominio con una fila plataforma='inpro' viva no puede pasar en silencio")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("sitio_plataforma_check");
            } finally {
                c.rollback();
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /** Los literales del CHECK, leídos del catálogo — no del texto de la migración. */
    private static List<String> domainOf(Statement st, String tabla, String constraint) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = '" + tabla + "'::regclass AND conname = '" + constraint + "'")) {
            assertThat(rs.next()).as("%s existe en %s", constraint, tabla).isTrue();
            String def = rs.getString(1);
            List<String> values = new ArrayList<>();
            Matcher m = Pattern.compile("'([a-z0-9]+)'").matcher(def);
            while (m.find()) values.add(m.group(1));
            return values;
        }
    }

    private static String sitioKeyDe(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT sitio_key FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("el producto insertado existe").isTrue();
            return rs.getString(1);
        }
    }

    private static List<String> sitioKeys(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT sitio_key FROM sitio")) {
            List<String> keys = new ArrayList<>();
            while (rs.next()) keys.add(rs.getString(1));
            return keys;
        }
    }
}
