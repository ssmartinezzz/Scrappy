package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * sp_upsert_promos is called inside the run's transaction and must raise on bad data. Unlike the
 * product upsert's Java wrapper, nothing here swallows an SQL error, so these tests assert the
 * exception itself.
 */
@Epic("Base de datos")
@Feature("sp_upsert_promos")
@DisplayName("sp_upsert_promos — promos de financiación")
class SpUpsertPromosTest extends PostgresTestBase {

    private static final String A = "PromoSitioA";
    private static final String B = "PromoSitioB";
    private static final String URL_A1 = "https://a.example/p1";
    private static final String URL_A2 = "https://a.example/p2";

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        db.upsertProductos(List.of(producto(A, URL_A1), producto(A, URL_A2), producto(B, "https://b.example/p1")));
        exec("DELETE FROM medio_pago WHERE nombre LIKE 'Banco Test%'");
    }

    private static Product producto(String sitio, String url) {
        return Product.builder().sitio(sitio).nombre("SSD").precio(100.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Almacenamiento").genero("unisex")
                .talles(List.of()).rubro("tecnologia").build();
    }

    private static String cuotas(String sitio, String medio, int cuotas, String urls) {
        return "{\"sitio\":\"%s\",\"medioPago\":\"%s\",\"tipoMedio\":\"banco\",\"tipo\":\"cuotas_sin_interes\","
                .formatted(sitio, medio) + "\"cuotas\":%d,\"alcance\":\"%s\",\"urls\":%s}"
                .formatted(cuotas, urls == null ? "sitio" : "producto", urls == null ? "[]" : urls);
    }

    private static String descuento(String sitio, String medio, Object pct) {
        return "{\"sitio\":\"%s\",\"medioPago\":\"%s\",\"tipoMedio\":\"medio\",\"tipo\":\"descuento\",\"pct\":%s,\"alcance\":\"sitio\"}"
                .formatted(sitio, medio, pct);
    }

    private static String arr(String... rows) {
        return "[" + String.join(",", rows) + "]";
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private int call(String json, String... sitios) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT sp_upsert_promos(?::jsonb, ?)")) {
            ps.setString(1, json);
            ps.setArray(2, c.createArrayOf("text", sitios));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private OffsetDateTime vistoAt(String sitioKey) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT visto_at FROM promo_financiacion WHERE sitio_key = '" + sitioKey + "'")) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class);
        }
    }

    private String key(String nombre) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT sitio_key FROM sitio WHERE nombre = '" + nombre + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("la migración siembra medios conocidos")
    void seedsKnownMedios() throws SQLException {
        assertThat(count("SELECT count(*) FROM medio_pago WHERE nombre IN ('Santander','Mercado Pago','Pago Nube','Transferencia')"))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("mismo payload dos veces -> mismas filas y visto_at avanza")
    void idempotent() throws Exception {
        String json = arr(cuotas(A, "Santander", 6, null), descuento(A, "Transferencia", 10));
        assertThat(call(json, A)).isEqualTo(2);
        OffsetDateTime primero = vistoAt(key(A));
        assertThat(call(json, A)).isEqualTo(2);

        assertThat(count("SELECT count(*) FROM promo_financiacion")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE activo")).isEqualTo(2);
        assertThat(vistoAt(key(A))).isAfter(primero);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE primer_visto_at < visto_at")).isEqualTo(2);
    }

    @Test
    @DisplayName("un medio desconocido se inserta con su tipo")
    void newMedioInserted() throws Exception {
        String banco = "{\"sitio\":\"%s\",\"medioPago\":\"Banco Test\",\"tipoMedio\":\"banco\",\"tipo\":\"descuento\",\"pct\":15.5,\"alcance\":\"sitio\"}".formatted(A);
        call(arr(banco), A);
        assertThat(count("SELECT count(*) FROM medio_pago WHERE nombre = 'Banco Test' AND tipo = 'banco'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE pct = 15.5")).isEqualTo(1);
    }

    @Test
    @DisplayName("sólo se vinculan las urls que existen")
    void linksOnlyExistingUrls() throws Exception {
        String urls = "[\"" + URL_A1 + "\",\"https://a.example/inexistente\"]";
        call(arr(cuotas(A, "Visa", 3, urls)), A);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto WHERE url = '" + URL_A1 + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("una url que deja de venir pierde su vínculo; la promo sigue activa")
    void unseenLinkIsDeleted() throws Exception {
        call(arr(cuotas(A, "Visa", 3, "[\"" + URL_A1 + "\",\"" + URL_A2 + "\"]")), A);
        call(arr(cuotas(A, "Visa", 3, "[\"" + URL_A1 + "\"]")), A);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE activo")).isEqualTo(1);
    }

    @Test
    @DisplayName("la desactivación se acota a p_sitios")
    void deactivationIsScopedToSitios() throws Exception {
        call(arr(descuento(A, "Transferencia", 10), descuento(B, "Transferencia", 10)), A, B);
        call(arr(descuento(A, "Efectivo", 5)), A);

        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE sitio_key='" + key(A) + "' AND activo")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion p JOIN medio_pago m ON m.id=p.medio_pago_id"
                + " WHERE p.sitio_key='" + key(A) + "' AND NOT p.activo AND m.nombre='Transferencia'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE sitio_key='" + key(B) + "' AND activo")).isEqualTo(1);
    }

    @Test
    @DisplayName("un sitio mirado sin promos las desactiva y borra sus vínculos")
    void siteLookedAtWithNoPromosDeactivatesAll() throws Exception {
        call(arr(cuotas(A, "Visa", 3, "[\"" + URL_A1 + "\"]")), A);
        call("[]", A);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE activo")).isZero();
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto")).isZero();
    }

    @Test
    @DisplayName("sin p_sitios no se desactiva nada")
    void emptyScopeDeactivatesNothing() throws Exception {
        call(arr(descuento(A, "Transferencia", 10)), A);
        call("[]");
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE activo")).isEqualTo(1);
    }

    @Test
    @DisplayName("una promo desactivada vuelve a activarse al verse de nuevo")
    void reactivation() throws Exception {
        String json = arr(descuento(A, "Transferencia", 10));
        call(json, A);
        call("[]", A);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE activo")).isZero();
        call(json, A);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE activo")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion")).isEqualTo(1);
    }

    @Test
    @DisplayName("duplicados dentro del mismo payload no rompen el upsert")
    void duplicatesInPayload() throws Exception {
        String d = descuento(A, "Transferencia", 10);
        assertThat(call(arr(d, d), A)).isEqualTo(1);
    }

    @Test
    @DisplayName("descuento sin pct viola el CHECK y el error se propaga")
    void descuentoWithoutPctRaises() {
        String malo = "{\"sitio\":\"%s\",\"medioPago\":\"Visa\",\"tipo\":\"descuento\",\"alcance\":\"sitio\"}".formatted(A);
        assertThatThrownBy(() -> call(arr(malo), A)).hasMessageContaining("ck_promo_pct_requerido");
    }

    @Test
    @DisplayName("cuotas sin cuotas, pct fuera de rango y tipo inválido también raisean")
    void otherChecksRaise() {
        String sinCuotas = "{\"sitio\":\"%s\",\"medioPago\":\"Visa\",\"tipo\":\"cuotas_sin_interes\",\"alcance\":\"sitio\"}".formatted(A);
        assertThatThrownBy(() -> call(arr(sinCuotas), A)).hasMessageContaining("ck_promo_cuotas_requeridas");
        assertThatThrownBy(() -> call(arr(descuento(A, "Visa", 150)), A)).hasMessageContaining("ck_promo_pct");
        String tipo = "{\"sitio\":\"%s\",\"medioPago\":\"Visa\",\"tipo\":\"regalo\",\"pct\":5,\"alcance\":\"sitio\"}".formatted(A);
        assertThatThrownBy(() -> call(arr(tipo), A)).hasMessageContaining("ck_promo_tipo");
    }

    @Test
    @DisplayName("un error en el payload deja la transacción sin escrituras parciales")
    void failureWritesNothing() throws Exception {
        String malo = "{\"sitio\":\"%s\",\"medioPago\":\"Banco Test Roto\",\"tipoMedio\":\"banco\",\"tipo\":\"descuento\",\"alcance\":\"sitio\"}".formatted(A);
        assertThatThrownBy(() -> call(arr(descuento(A, "Efectivo", 5), malo), A)).isInstanceOf(SQLException.class);
        assertThat(count("SELECT count(*) FROM promo_financiacion")).isZero();
        assertThat(count("SELECT count(*) FROM medio_pago WHERE nombre = 'Banco Test Roto'")).isZero();
    }

    private static String conRed(String sitio, String medio, String red, int cuotas, String urls) {
        String base = cuotas(sitio, medio, cuotas, urls);
        return base.substring(0, base.length() - 1) + ",\"red\":" + (red == null ? "null" : "\"" + red + "\"") + "}";
    }

    private long conRedCount(String medio, String red) throws SQLException {
        return count("SELECT count(*) FROM promo_financiacion p JOIN medio_pago m ON m.id = p.medio_pago_id"
                + " JOIN medio_pago r ON r.id = p.red_medio_pago_id WHERE m.nombre = '" + medio + "' AND r.nombre = '" + red + "'");
    }

    @Test
    @DisplayName("la red entra en la clave natural: Macro+Visa 12 y Macro+Mastercard 12 conviven")
    void redIsPartOfTheNaturalKey() throws Exception {
        assertThat(call(arr(conRed(A, "Macro", "Visa", 12, null), conRed(A, "Macro", "Mastercard", 12, null)), A)).isEqualTo(2);
        assertThat(conRedCount("Macro", "Visa")).isEqualTo(1);
        assertThat(conRedCount("Macro", "Mastercard")).isEqualTo(1);
        assertThat(call(arr(conRed(A, "Macro", "Visa", 12, null), conRed(A, "Macro", "Mastercard", 12, null)), A)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM promo_financiacion")).isEqualTo(2);
    }

    @Test
    @DisplayName("red NULL y red informada son promos distintas, y un payload sin campo red es red NULL")
    void nullRedIsDistinctFromARed() throws Exception {
        call(arr(conRed(A, "Macro", "Visa", 12, null), conRed(A, "Macro", null, 12, null),
                cuotas(A, "Macro", 12, null)), A);
        assertThat(count("SELECT count(*) FROM promo_financiacion")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE red_medio_pago_id IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("una red de cadena vacía es red NULL, no un valor de FK")
    void emptyRedIsNull() throws Exception {
        call(arr(conRed(A, "Macro", "", 12, null)), A);
        assertThat(count("SELECT count(*) FROM promo_financiacion WHERE red_medio_pago_id IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("la desactivación por corrida distingue la red: la que no vuelve se apaga, la otra sigue")
    void deactivationIsPerRed() throws Exception {
        call(arr(conRed(A, "Macro", "Visa", 12, null), conRed(A, "Macro", "Mastercard", 12, null)), A);
        call(arr(conRed(A, "Macro", "Visa", 12, null)), A);
        assertThat(count("SELECT count(*) FROM promo_financiacion p JOIN medio_pago r ON r.id = p.red_medio_pago_id"
                + " WHERE p.activo AND r.nombre = 'Visa'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion p JOIN medio_pago r ON r.id = p.red_medio_pago_id"
                + " WHERE NOT p.activo AND r.nombre = 'Mastercard'")).isEqualTo(1);
    }

    @Test
    @DisplayName("el vínculo de producto sigue a la promo con su red, no a la hermana con otra red")
    void productLinkFollowsTheRedRow() throws Exception {
        call(arr(conRed(A, "Macro", "Visa", 12, "[\"" + URL_A1 + "\"]"),
                conRed(A, "Macro", "Mastercard", 12, "[\"" + URL_A2 + "\"]")), A);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto l JOIN promo_financiacion p ON p.id = l.promo_id"
                + " JOIN medio_pago r ON r.id = p.red_medio_pago_id WHERE r.nombre = 'Visa' AND l.url = '" + URL_A1 + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM promo_financiacion_producto")).isEqualTo(2);
    }

    @Test
    @DisplayName("una red que no es tarjeta raisea y no escribe nada")
    void nonTarjetaRedRaises() {
        assertThatThrownBy(() -> call(arr(conRed(A, "Macro", "Santander", 12, null)), A))
                .hasMessageContaining("red");
        assertThatThrownBy(() -> call(arr(conRed(A, "Macro", "Red Inexistente", 12, null)), A))
                .hasMessageContaining("red");
    }

    @Test
    @DisplayName("un medio no puede ser su propia red")
    void redCannotBeTheMedioItself() {
        assertThatThrownBy(() -> call(arr(conRed(A, "Visa", "Visa", 12, null)), A))
                .isInstanceOf(SQLException.class);
    }
}
