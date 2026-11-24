package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.pages.TiendanubeUrl;
import ar.scraper.pages.TiendanubeUrlCases;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Product identity")
@Story("V136 folds the Tiendanube entry twins into the canonical url")
@DisplayName("V136 — twins with entry_path / entry_category_id merge into one row per product")
class V136MergeTiendanubeEntryTwinsTest extends PostgresTestBase {

    private static final String BASE = "https://tienda.test/productos/";
    private static final String USUARIO_UNO = "00000000-0000-0000-0000-000000000001";
    private static final String USUARIO_DOS = "00000000-0000-0000-0000-000000000002";
    private static final String E = "?entry_path=category";

    @BeforeEach
    void sembrarBase() throws Exception {
        sql("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('Tienda', 'tienda', 'tiendanube', false, NULL, 'historico') ON CONFLICT DO NOTHING",
            "INSERT INTO usuario (id, username, password_hash) VALUES ('" + USUARIO_UNO + "', 'usuarioUno', 'x'), ('"
                + USUARIO_DOS + "', 'usuarioDos', 'x')");
    }

    private void sql(String... sentencias) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            for (String s : sentencias) st.execute(s);
        }
    }

    private static final List<String> CATEGORIAS = List.of("Remera", "Buzo", "Campera");
    private static final List<String> GENEROS = List.of("hombre", "mujer", "unisex");

    /** Which classification a twin carries, so the one a lock protects can be told apart. */
    private static int clase(String suffix) {
        if (suffix.endsWith("entry_category_id=1")) return 0;
        if (suffix.endsWith("entry_category_id=2")) return 1;
        return suffix.equals(E) ? 2 : 0;
    }

    private void producto(String slug, String suffix, boolean activo, String touched, String extra) throws Exception {
        String url = BASE + slug + "/" + suffix;
        sql("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at, categoria, genero, "
                + "sub_categoria, rubro) VALUES ('" + url + "', 'Tienda', '" + slug + "', 100, " + activo + ", '"
                + touched + "', '" + touched + "', '" + CATEGORIAS.get(clase(suffix)) + "', '" + GENEROS.get(clase(suffix))
                + "', 'Sub-" + clase(suffix) + "', 'indumentaria')");
        if (!extra.isEmpty()) sql("UPDATE productos SET " + extra + " WHERE url = '" + url + "'");
    }

    private void migrar() throws Exception {
        String script;
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V136__merge_tiendanube_entry_twins.sql"))) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(script);
            }
            c.commit();
        }
    }

    private List<String> col(String consulta) throws Exception {
        List<String> r = new ArrayList<>();
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(consulta)) {
            while (rs.next()) r.add(rs.getString(1));
        }
        return r;
    }

    private String uno(String consulta) throws Exception {
        List<String> r = col(consulta);
        assertThat(r).as(consulta).hasSize(1);
        return r.get(0);
    }

    // ── a group whose canonical row does not exist yet ───────────────────────────────────────────────

    private void sembrarGrupoSinCanonica() throws Exception {
        String a1 = BASE + "g/?entry_path=category&entry_category_id=1";
        String a2 = BASE + "g/?entry_path=category&entry_category_id=2";
        String a3 = BASE + "g/" + E;
        producto("g", "?entry_path=category&entry_category_id=1", true, "2026-01-01T00:00:00Z",
                "bloqueado_por = 'admin', bloqueado_at = '2026-02-01T00:00:00Z', created_at = '2025-06-01T00:00:00Z', "
                        + "visto_en_stock_at = '2026-03-01T00:00:00Z', en_stock = false, stock_chequeado_at = '2026-03-01T00:00:00Z'");
        producto("g", "?entry_path=category&entry_category_id=2", true, "2026-05-01T00:00:00Z",
                "visto_en_stock_at = '2026-01-01T00:00:00Z', en_stock = true, stock_chequeado_at = '2026-05-01T00:00:00Z', precio = 777");
        producto("g", E, false, "2026-06-01T00:00:00Z",
                "bloqueado_por = 'usuario', bloqueado_at = '2026-04-01T00:00:00Z'");
        sql("INSERT INTO precio_historico (url, precio, fecha) VALUES "
                + "('" + a1 + "', 100, '2026-01-01'), ('" + a1 + "', 110, '2026-01-02'), "
                + "('" + a2 + "', 200, '2026-01-02'), ('" + a2 + "', 300, '2026-01-03')",
            "INSERT INTO precios_externos (producto_url, sitio, titulo, precio, fecha) "
                + "VALUES ('" + a1 + "', 'Otro', 't', 90, '2026-01-01')",
            "INSERT INTO producto_talle (url, posicion, talle) VALUES ('" + a1 + "', 0, 'S'), ('" + a1 + "', 1, 'M'), "
                + "('" + a2 + "', 0, 'L')",
            "INSERT INTO producto_badge (url, posicion, badge) VALUES ('" + a1 + "', 0, 'nuevo'), ('" + a1 + "', 1, 'oferta'), "
                + "('" + a2 + "', 0, 'ultimo')",
            "INSERT INTO producto_tech_specs (url, watts) VALUES ('" + a1 + "', 500)",
            "INSERT INTO favoritos (url, sitio, added_at, usuario_id) VALUES "
                + "('" + a1 + "', 'Tienda', '2026-01-10', '" + USUARIO_UNO + "'), "
                + "('" + a2 + "', 'Tienda', '2026-01-05', '" + USUARIO_UNO + "'), "
                + "('" + a1 + "', 'Tienda', '2026-01-07', NULL), ('" + a3 + "', 'Tienda', '2026-01-03', NULL), "
                + "('" + a3 + "', 'Tienda', '2026-01-04', '" + USUARIO_DOS + "')",
            "INSERT INTO notificacion_precio (usuario_id, url, precio_anterior, precio_nuevo, estado, enviada_at) VALUES "
                + "('" + USUARIO_UNO + "', '" + a1 + "', 120, 100, 'PENDIENTE', NULL), "
                + "('" + USUARIO_UNO + "', '" + a2 + "', 120, 100, 'ENVIADA', now()), "
                + "('" + USUARIO_UNO + "', '" + a1 + "', 120, 50, 'PENDIENTE', NULL)",
            "INSERT INTO promo_financiacion (sitio_key, medio_pago_id, tipo, pct, alcance) "
                + "VALUES ('tienda', 1, 'descuento', 10, 'producto')",
            "INSERT INTO promo_financiacion_producto (promo_id, url, visto_at) "
                + "SELECT id, '" + a1 + "', '2026-01-01' FROM promo_financiacion",
            "INSERT INTO promo_financiacion_producto (promo_id, url, visto_at) "
                + "SELECT id, '" + a2 + "', '2026-02-01' FROM promo_financiacion",
            "INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at) VALUES "
                + "('" + a1 + "', '\\x01', 1, 'm', '2026-01-01'), ('" + a3 + "', '\\x02', 1, 'm', '2026-05-01')",
            "INSERT INTO saved_outfits (nombre, total_estimado, created_at) VALUES ('o', 0, now())",
            "INSERT INTO saved_outfit_item (outfit_id, clase, posicion, ranura, url) "
                + "SELECT id, 'slot', 0, 'torso', '" + a2 + "' FROM saved_outfits",
            "INSERT INTO saved_pcs (nombre, presupuesto, con_gpu, total_estimado) VALUES ('p', 1, false, 1)",
            "INSERT INTO saved_pc_item (pc_id, posicion, slot, url) SELECT id, 0, 'cpu', '" + a1 + "' FROM saved_pcs",
            "INSERT INTO outfit_feedback_item (slot, url, liked, estilo, created_at) VALUES ('torso', '" + a3 + "', true, 'gym', now())",
            "INSERT INTO agent_reclassify_audit (url, applied_at) VALUES ('" + a2 + "', now())");
    }

    @Test
    @DisplayName("product columns come from the winner; created_at is the earliest, activo any, stock seen the latest")
    void productColumnsFollowTheWinner() throws Exception {
        sembrarGrupoSinCanonica();

        migrar();

        String canon = BASE + "g/";
        assertThat(col("SELECT url FROM productos")).containsExactly(canon);
        assertThat(uno("SELECT precio FROM productos")).isEqualTo("777");
        assertThat(uno("SELECT activo FROM productos")).isEqualTo("t");
        assertThat(uno("SELECT created_at::date FROM productos")).isEqualTo("2025-06-01");
        assertThat(uno("SELECT visto_en_stock_at::date FROM productos")).isEqualTo("2026-03-01");
        assertThat(uno("SELECT en_stock FROM productos")).as("stock travels with the winner").isEqualTo("t");
        assertThat(uno("SELECT stock_chequeado_at::date FROM productos")).isEqualTo("2026-05-01");
        assertThat(uno("SELECT producto_key FROM productos")).isEqualTo(uno("SELECT substr(md5(url), 1, 16) FROM productos"));
    }

    @Test
    @DisplayName("the latest lock survives together with the classification of the row that held it")
    void lockTravelsWithItsClassification() throws Exception {
        sembrarGrupoSinCanonica();

        migrar();

        assertThat(uno("SELECT bloqueado_por FROM productos")).isEqualTo("usuario");
        assertThat(uno("SELECT bloqueado_at::date FROM productos")).isEqualTo("2026-04-01");
        assertThat(uno("SELECT categoria || '/' || genero || '/' || sub_categoria FROM productos"))
                .isEqualTo("Campera/unisex/Sub-2");
    }

    @Test
    @DisplayName("history keeps every date once, the winner's price first; lists and specs follow the winner")
    void childrenAreMerged() throws Exception {
        sembrarGrupoSinCanonica();

        migrar();

        assertThat(col("SELECT fecha || '=' || precio FROM precio_historico ORDER BY fecha"))
                .containsExactly("2026-01-01=100", "2026-01-02=200", "2026-01-03=300");
        assertThat(col("SELECT talle FROM producto_talle")).containsExactly("L");
        assertThat(col("SELECT badge FROM producto_badge")).containsExactly("ultimo");
        assertThat(col("SELECT watts FROM producto_tech_specs")).containsExactly("500");
        assertThat(col("SELECT producto_url FROM precios_externos")).containsExactly(BASE + "g/");
    }

    @Test
    @DisplayName("favorites keep the earliest per user and per ownerless; a sent alert is never lost")
    void favoritosAndAlerts() throws Exception {
        sembrarGrupoSinCanonica();

        migrar();

        assertThat(col("SELECT coalesce(usuario_id::text, 'sin dueño') || '@' || added_at::date FROM favoritos ORDER BY 1"))
                .containsExactlyInAnyOrder(USUARIO_UNO + "@2026-01-05", USUARIO_DOS + "@2026-01-04", "sin dueño@2026-01-03");
        assertThat(col("SELECT DISTINCT url FROM favoritos")).containsExactly(BASE + "g/");
        assertThat(col("SELECT precio_nuevo || ':' || estado FROM notificacion_precio ORDER BY precio_nuevo"))
                .containsExactly("50:PENDIENTE", "100:ENVIADA");
        assertThat(col("SELECT visto_at::date FROM promo_financiacion_producto")).containsExactly("2026-02-01");
    }

    @Test
    @DisplayName("tables with no foreign key are repointed; the latest embedding is the one kept")
    void tablesWithoutForeignKey() throws Exception {
        sembrarGrupoSinCanonica();

        migrar();

        String canon = BASE + "g/";
        assertThat(col("SELECT url || '@' || encode(embedding, 'hex') FROM image_embeddings"))
                .containsExactly(canon + "@02");
        for (String tabla : List.of("saved_outfit_item", "saved_pc_item", "outfit_feedback_item", "agent_reclassify_audit")) {
            assertThat(col("SELECT url FROM " + tabla)).as(tabla).containsExactly(canon);
        }
    }

    // ── a group whose canonical row already exists, plus rows that must stay as they are ───────────────

    @Test
    @DisplayName("an existing canonical row is updated from the winner, keeps its own history dates and keeps other params")
    void existingCanonicalIsUpdated() throws Exception {
        producto("e", "", true, "2026-01-01T00:00:00Z", "precio = 10, created_at = '2025-01-01T00:00:00Z'");
        producto("e", "?variant=9", true, "2026-01-01T00:00:00Z", "");
        producto("e", "?variant=9&entry_path=category", true, "2026-07-01T00:00:00Z", "precio = 20");
        sql("INSERT INTO precio_historico (url, precio, fecha) VALUES "
                + "('" + BASE + "e/', 10, '2026-01-01'), ('" + BASE + "e/', 11, '2026-01-02'), "
                + "('" + BASE + "e/?variant=9&entry_path=category', 20, '2026-01-01')");

        migrar();

        assertThat(col("SELECT url FROM productos ORDER BY url")).containsExactly(BASE + "e/", BASE + "e/?variant=9");
        assertThat(uno("SELECT precio FROM productos WHERE url = '" + BASE + "e/?variant=9'")).isEqualTo("20");
        assertThat(col("SELECT fecha || '=' || precio FROM precio_historico WHERE url = '" + BASE + "e/?variant=9' ORDER BY fecha"))
                .containsExactly("2026-01-01=20");
        assertThat(col("SELECT fecha || '=' || precio FROM precio_historico WHERE url = '" + BASE + "e/' ORDER BY fecha"))
                .containsExactly("2026-01-01=10", "2026-01-02=11");
    }

    @Test
    @DisplayName("a winner that is the canonical row itself keeps its lists; ties break on the url")
    void canonicalWinnerKeepsItsLists() throws Exception {
        producto("w", "", true, "2026-08-01T00:00:00Z", "");
        producto("w", E, true, "2026-01-01T00:00:00Z", "");
        sql("INSERT INTO producto_talle (url, posicion, talle) VALUES ('" + BASE + "w/', 0, 'XL'), ('" + BASE + "w/" + E + "', 0, 'S')");

        migrar();

        assertThat(col("SELECT talle FROM producto_talle")).containsExactly("XL");
        assertThat(col("SELECT url FROM productos")).containsExactly(BASE + "w/");
    }

    @Test
    @DisplayName("twins that are all inactive leave an inactive canonical row")
    void inactiveStaysInactive() throws Exception {
        producto("i", E, false, "2026-01-01T00:00:00Z", "");
        producto("i", "?entry_path=category&entry_category_id=3", false, "2026-02-01T00:00:00Z", "");

        migrar();

        assertThat(col("SELECT url || ':' || activo FROM productos")).containsExactly(BASE + "i/:false");
    }

    @Test
    @DisplayName("a row without the params is not touched, even one that mentions them elsewhere")
    void otherRowsAreUntouched() throws Exception {
        producto("n", "", true, "2026-01-01T00:00:00Z", "");
        producto("n", "?my_entry_path=1", true, "2026-01-01T00:00:00Z", "");
        producto("n", "?x=1#a?entry_path=category", true, "2026-01-01T00:00:00Z", "");
        sql("UPDATE productos SET touched_at = '2000-01-01T00:00:00Z'");

        migrar();

        assertThat(col("SELECT url FROM productos ORDER BY url")).containsExactly(
                BASE + "n/", BASE + "n/?my_entry_path=1", BASE + "n/?x=1#a?entry_path=category");
        assertThat(col("SELECT DISTINCT touched_at::date FROM productos")).containsExactly("2000-01-01");
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void idempotent() throws Exception {
        sembrarGrupoSinCanonica();
        migrar();
        String antes = uno("SELECT md5(string_agg(p::text, '|' ORDER BY url)) FROM productos p");

        migrar();

        assertThat(uno("SELECT md5(string_agg(p::text, '|' ORDER BY url)) FROM productos p")).isEqualTo(antes);
    }

    @Test
    @DisplayName("SQL and Java canonicalise every shared case the same way")
    void sqlAgreesWithJava() throws Exception {
        int i = 0;
        for (String entrada : TiendanubeUrlCases.CASOS.keySet()) {
            sql("INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at) VALUES ('"
                    + entrada + "', 'Tienda', 'caso" + i++ + "', 1, true, now(), now())");
        }

        migrar();

        List<String> esperadas = TiendanubeUrlCases.CASOS.keySet().stream().map(TiendanubeUrl::canonica).distinct().sorted().toList();
        assertThat(col("SELECT url FROM productos ORDER BY url")).isEqualTo(esperadas);
        for (Map.Entry<String, String> caso : TiendanubeUrlCases.CASOS.entrySet()) {
            assertThat(TiendanubeUrl.canonica(caso.getKey())).isEqualTo(caso.getValue());
        }
    }
}
