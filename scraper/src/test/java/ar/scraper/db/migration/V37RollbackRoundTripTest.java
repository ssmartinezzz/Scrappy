package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V37}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 *
 * <p>Covers the fase-9 objects: the {@code tamanio_gabinete} lookup, the two
 * columns it and {@code radiador_mm} add to {@code producto_tech_specs}, and
 * the four preference columns on {@code preferencia_armador}. Everything
 * {@code V35} and {@code V36} created stays standing — including the widened
 * CHECK, which has to come back in its {@code V35} shape rather than
 * disappear.</p>
 */
@DisplayName("V37 migration — the documented rollback actually runs, and is contained")
class V37RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops tamanio_gabinete and the fase-9 columns, leaving V35/V36 standing")
    void rollbackDropsOnlyItsOwnObjects() throws Exception {
        sembrarUnaPreferencia();
        sembrarUnasSpecs();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V37"));

                assertThat(existeTabla(st, "tamanio_gabinete")).isFalse();
                for (String columna : new String[] {"capacidad_minima_gb", "tamanio_gabinete_id",
                        "tipo_cooler_id", "watts_minimos"}) {
                    assertThat(existeColumna(st, "preferencia_armador", columna))
                            .as("preferencia_armador." + columna + " should be dropped").isFalse();
                }
                for (String columna : new String[] {"tamanio_gabinete_id", "radiador_mm"}) {
                    assertThat(existeColumna(st, "producto_tech_specs", columna))
                            .as("producto_tech_specs." + columna + " should be dropped").isFalse();
                }

                // Lo de V35/V36 sigue en pie, columnas incluidas.
                assertThat(existeTabla(st, "tipo_cooler")).isTrue();
                assertThat(existeTabla(st, "marca_chip")).isTrue();
                assertThat(existeTabla(st, "preferencia_armador")).isTrue();
                assertThat(existeTabla(st, "producto_tech_specs")).isTrue();
                assertThat(existeColumna(st, "producto_tech_specs", "marca_chip_id")).isTrue();
                assertThat(existeColumna(st, "preferencia_armador", "ram_dual")).isTrue();

                // Y el CHECK vuelve a existir en su forma de V35: el rollback lo
                // dropea para poder soltar radiador_mm, así que no alcanza con que
                // la columna se haya ido — tiene que volver a estar la constraint.
                assertThat(existeConstraint(st, "chk_producto_tech_specs_enteros_positivos")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The block drops the referencing columns before the lookup, restores the CHECK last, and never CASCADEs")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V37");

        int idxPrefCol = sql.indexOf("DROP COLUMN tamanio_gabinete_id");
        int idxRadiador = sql.indexOf("DROP COLUMN radiador_mm");
        int idxAddCheck = sql.indexOf("ADD CONSTRAINT chk_producto_tech_specs_enteros_positivos");
        int idxDropTabla = sql.indexOf("DROP TABLE tamanio_gabinete;");

        assertThat(idxPrefCol).isNotNegative();
        assertThat(idxDropTabla)
                .as("las dos columnas que referencian tamanio_gabinete se sueltan antes que la tabla")
                .isGreaterThan(idxPrefCol);
        assertThat(idxAddCheck)
                .as("un CHECK no puede nombrar una columna que ya no existe: se recrea DESPUÉS de soltar radiador_mm")
                .isGreaterThan(idxRadiador);
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnaPreferencia() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO preferencia_armador (usuario_id, gama_id, tamanio_gabinete_id, watts_minimos)
                    SELECT '%s', g.id, t.id, 850 FROM gama g, tamanio_gabinete t
                    WHERE g.nombre = 'ALTA' AND t.nombre = 'MID'
                    """.formatted(usuario));
        }
    }

    private void sembrarUnasSpecs() throws Exception {
        String url = "https://v37-rollback.test/gabinete-1";
        try (Connection c = dataSource().getConnection()) {
            try (Statement seed = c.createStatement()) {
                seed.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                        + "ON CONFLICT DO NOTHING");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) "
                            + "VALUES (?, 'Sitio', 'Producto', 1000, 'tecnologia')")) {
                ps.setString(1, url);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.execute("""
                        INSERT INTO producto_tech_specs (url, tamanio_gabinete_id, radiador_mm)
                        SELECT '%s', t.id, 360 FROM tamanio_gabinete t WHERE t.nombre = 'FULL'
                        """.formatted(url));
            }
        }
    }

    private static boolean existeTabla(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = '%s'
                """.formatted(tabla))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static boolean existeColumna(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '%s' AND column_name = '%s'
                """.formatted(tabla, columna))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static boolean existeConstraint(Statement st, String nombre) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND constraint_name = '%s'
                """.formatted(nombre))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
