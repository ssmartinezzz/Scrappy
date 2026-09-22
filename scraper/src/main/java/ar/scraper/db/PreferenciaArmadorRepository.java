package ar.scraper.db;

import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
import ar.scraper.pcs.PreferenciasDeArmado;
import ar.scraper.pcs.TamanioGabinete;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the one saved builder preference per user
 * ({@code preferencia_armador}, V35, widened by V36 and V37). Same shape as
 * {@link SavedPcsRepository}.
 */
@Repository
class PreferenciaArmadorRepository implements PreferenciaArmadorPort {

    private static final Logger LOG = LoggerFactory.getLogger(PreferenciaArmadorRepository.class);

    private final DataSource dataSource;

    PreferenciaArmadorRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Upsert por dueño: el {@code WHERE} del conflict target repite exactamente
     * el de {@code uq_preferencia_armador_usuario} — un índice parcial no se
     * infiere solo (V26, la lección de {@code favoritos}).
     */
    @Override
    public void guardar(UUID usuarioId, PreferenciaArmador preferencia) {
        String gamaNombre = GamaMapeo.nombreDeGama(preferencia.gama());
        PreferenciasDeArmado prefs = preferencia.preferencias();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO preferencia_armador (
                         usuario_id, gama_id, presupuesto, con_gpu, updated_at,
                         ddr_id, marca_cpu_id, marca_gpu_id, tipo_almacenamiento_id, ram_dual, wifi,
                         capacidad_minima_gb, tamanio_gabinete_id, tipo_cooler_id, watts_minimos
                     ) VALUES (
                         ?, (SELECT id FROM gama WHERE nombre = ?), ?, ?, now(),
                         (SELECT id FROM ddr WHERE nombre = ?),
                         (SELECT id FROM marca_chip WHERE nombre = ?),
                         (SELECT id FROM marca_chip WHERE nombre = ?),
                         (SELECT id FROM tipo_almacenamiento WHERE nombre = ?),
                         ?, ?,
                         ?,
                         (SELECT id FROM tamanio_gabinete WHERE nombre = ?),
                         (SELECT id FROM tipo_cooler WHERE nombre = ?),
                         ?
                     )
                     ON CONFLICT (usuario_id) WHERE usuario_id IS NOT NULL DO UPDATE SET
                         gama_id                 = EXCLUDED.gama_id,
                         presupuesto             = EXCLUDED.presupuesto,
                         con_gpu                 = EXCLUDED.con_gpu,
                         updated_at              = now(),
                         ddr_id                  = EXCLUDED.ddr_id,
                         marca_cpu_id            = EXCLUDED.marca_cpu_id,
                         marca_gpu_id            = EXCLUDED.marca_gpu_id,
                         tipo_almacenamiento_id  = EXCLUDED.tipo_almacenamiento_id,
                         ram_dual                = EXCLUDED.ram_dual,
                         wifi                    = EXCLUDED.wifi,
                         capacidad_minima_gb     = EXCLUDED.capacidad_minima_gb,
                         tamanio_gabinete_id     = EXCLUDED.tamanio_gabinete_id,
                         tipo_cooler_id          = EXCLUDED.tipo_cooler_id,
                         watts_minimos           = EXCLUDED.watts_minimos
                     """)) {
            ps.setObject(1, usuarioId);
            ps.setString(2, gamaNombre);
            if (preferencia.presupuesto() == null) {
                ps.setNull(3, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(3, preferencia.presupuesto());
            }
            ps.setBoolean(4, preferencia.conGpu());
            setNullableString(ps, 5, prefs.ddr());
            setNullableString(ps, 6, prefs.marcaCpu());
            setNullableString(ps, 7, prefs.marcaGpu());
            setNullableString(ps, 8, prefs.tipoAlmacenamiento() != null ? prefs.tipoAlmacenamiento().name() : null);
            ps.setBoolean(9, Boolean.TRUE.equals(prefs.ramDual()));
            ps.setBoolean(10, Boolean.TRUE.equals(prefs.wifi()));
            setNullableInt(ps, 11, prefs.capacidadMinimaGb());
            setNullableString(ps, 12, prefs.tamanioGabinete() != null ? prefs.tamanioGabinete().name() : null);
            setNullableString(ps, 13, prefs.tipoCooler() != null ? prefs.tipoCooler().name() : null);
            setNullableInt(ps, 14, prefs.wattsMinimos());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando preferencia de armador: {}", e.getMessage());
        }
    }

    @Override
    public Optional<PreferenciaArmador> cargar(UUID usuarioId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT g.nombre AS gama_nombre, p.presupuesto, p.con_gpu,
                            dd.nombre AS ddr_nombre, mc.nombre AS marca_cpu_nombre,
                            mg.nombre AS marca_gpu_nombre, ta.nombre AS tipo_almacenamiento_nombre,
                            p.ram_dual, p.wifi,
                            p.capacidad_minima_gb, tg.nombre AS tamanio_gabinete_nombre,
                            tc.nombre AS tipo_cooler_nombre, p.watts_minimos
                     FROM preferencia_armador p
                     JOIN gama g ON g.id = p.gama_id
                     LEFT JOIN ddr dd ON dd.id = p.ddr_id
                     LEFT JOIN marca_chip mc ON mc.id = p.marca_cpu_id
                     LEFT JOIN marca_chip mg ON mg.id = p.marca_gpu_id
                     LEFT JOIN tipo_almacenamiento ta ON ta.id = p.tipo_almacenamiento_id
                     LEFT JOIN tamanio_gabinete tg ON tg.id = p.tamanio_gabinete_id
                     LEFT JOIN tipo_cooler tc ON tc.id = p.tipo_cooler_id
                     WHERE p.usuario_id = ?
                     """)) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                double presupuesto = rs.getDouble("presupuesto");
                Double presupuestoOrNull = rs.wasNull() ? null : presupuesto;
                String tipoAlmacenamientoNombre = rs.getString("tipo_almacenamiento_nombre");
                String tamanioGabineteNombre = rs.getString("tamanio_gabinete_nombre");
                String tipoCoolerNombre = rs.getString("tipo_cooler_nombre");
                PreferenciasDeArmado prefs = new PreferenciasDeArmado(
                        rs.getString("ddr_nombre"),
                        rs.getString("marca_cpu_nombre"),
                        rs.getString("marca_gpu_nombre"),
                        tipoAlmacenamientoNombre != null ? TipoAlmacenamiento.valueOf(tipoAlmacenamientoNombre) : null,
                        booleanOrNull(rs.getBoolean("ram_dual")),
                        booleanOrNull(rs.getBoolean("wifi")),
                        intOrNull(rs, "capacidad_minima_gb"),
                        tamanioGabineteNombre != null ? TamanioGabinete.valueOf(tamanioGabineteNombre) : null,
                        tipoCoolerNombre != null ? TipoCooler.valueOf(tipoCoolerNombre) : null,
                        intOrNull(rs, "watts_minimos"));
                return Optional.of(new PreferenciaArmador(
                        GamaMapeo.gamaDeNombre(rs.getString("gama_nombre")),
                        presupuestoOrNull,
                        rs.getBoolean("con_gpu"),
                        prefs));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando preferencia de armador: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** D2: {@code FALSE} and "not requested" are the same state — never re-invented as a third value. */
    private static Boolean booleanOrNull(boolean value) {
        return value ? Boolean.TRUE : null;
    }

    /** Un piso NULL en la base es "no pedida", nunca un 0 — igual que {@link PreferenciasDeArmado} lo exige. */
    private static Integer intOrNull(ResultSet rs, String columna) throws java.sql.SQLException {
        int valor = rs.getInt(columna);
        return rs.wasNull() ? null : valor;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws Exception {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws Exception {
        if (value == null || value.isBlank()) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
