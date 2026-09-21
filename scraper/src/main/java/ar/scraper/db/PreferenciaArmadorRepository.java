package ar.scraper.db;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PreferenciaArmador;
import ar.scraper.pcs.PreferenciaArmadorPort;
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
 * ({@code preferencia_armador}, V35). Same shape as {@link SavedPcsRepository}.
 */
@Repository
class PreferenciaArmadorRepository implements PreferenciaArmadorPort {

    private static final Logger LOG = LoggerFactory.getLogger(PreferenciaArmadorRepository.class);

    private final DataSource dataSource;

    PreferenciaArmadorRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** {@code Gama.BAJA} maps to the {@code ECONOMICA} row — that row is the DB vocabulary. */
    private static String nombreDeGama(Gama gama) {
        return switch (gama) {
            case BAJA -> "ECONOMICA";
            case MEDIA -> "MEDIA";
            case ALTA -> "ALTA";
            case DESCONOCIDA -> throw new IllegalArgumentException(
                    "Gama.DESCONOCIDA es un centinela de abstención, nunca un valor de FK (D10)");
        };
    }

    private static Gama gamaDeNombre(String nombre) {
        return switch (nombre) {
            case "ECONOMICA" -> Gama.BAJA;
            case "MEDIA" -> Gama.MEDIA;
            case "ALTA" -> Gama.ALTA;
            default -> throw new IllegalStateException("gama.nombre desconocido en la base: " + nombre);
        };
    }

    /**
     * Upsert por dueño: el {@code WHERE} del conflict target repite exactamente
     * el de {@code uq_preferencia_armador_usuario} — un índice parcial no se
     * infiere solo (V26, la lección de {@code favoritos}).
     */
    @Override
    public void guardar(UUID usuarioId, PreferenciaArmador preferencia) {
        String gamaNombre = nombreDeGama(preferencia.gama());
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO preferencia_armador (usuario_id, gama_id, presupuesto, con_gpu, updated_at)
                     VALUES (?, (SELECT id FROM gama WHERE nombre = ?), ?, ?, now())
                     ON CONFLICT (usuario_id) WHERE usuario_id IS NOT NULL DO UPDATE SET
                         gama_id     = EXCLUDED.gama_id,
                         presupuesto = EXCLUDED.presupuesto,
                         con_gpu     = EXCLUDED.con_gpu,
                         updated_at  = now()
                     """)) {
            ps.setObject(1, usuarioId);
            ps.setString(2, gamaNombre);
            if (preferencia.presupuesto() == null) {
                ps.setNull(3, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(3, preferencia.presupuesto());
            }
            ps.setBoolean(4, preferencia.conGpu());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando preferencia de armador: {}", e.getMessage());
        }
    }

    @Override
    public Optional<PreferenciaArmador> cargar(UUID usuarioId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT g.nombre AS gama_nombre, p.presupuesto, p.con_gpu
                     FROM preferencia_armador p
                     JOIN gama g ON g.id = p.gama_id
                     WHERE p.usuario_id = ?
                     """)) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                double presupuesto = rs.getDouble("presupuesto");
                Double presupuestoOrNull = rs.wasNull() ? null : presupuesto;
                return Optional.of(new PreferenciaArmador(
                        gamaDeNombre(rs.getString("gama_nombre")),
                        presupuestoOrNull,
                        rs.getBoolean("con_gpu")));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando preferencia de armador: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
