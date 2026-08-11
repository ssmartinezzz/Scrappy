package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the {@code financiacion_presets} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). The
 * {@code Preset} record stays nested on DatabaseService — callers and tests
 * name it {@code DatabaseService.Preset} — so this class returns that type.</p>
 */
class PresetRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PresetRepository.class);

    private static final String PRESET_ILUSTRATIVO_LABEL =
            "Ejemplo — 12 cuotas / 40% recargo (editá este valor)";
    private static final double PRESET_ILUSTRATIVO_RECARGO_PCT = 40.0;
    private static final int    PRESET_ILUSTRATIVO_CUOTAS      = 12;

    private final DataSource dataSource;

    PresetRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * En el primer arranque (tabla vacía), crea un preset ilustrativo marcado
     * explícitamente como ejemplo y lo deja activo, para que la señal de
     * financiación tenga un valor de referencia desde el día uno sin requerir
     * que el usuario configure nada manualmente.
     */
    void seedPresetIlustrativoSiVacio() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM financiacion_presets")) {
            if (rs.next() && rs.getInt(1) == 0) {
                crearPresetInterno(c, PRESET_ILUSTRATIVO_LABEL, PRESET_ILUSTRATIVO_RECARGO_PCT,
                        PRESET_ILUSTRATIVO_CUOTAS, true);
                LOG.info("[DB] Preset ilustrativo creado y activado (tabla vacía).");
            }
        }
    }

    private int crearPresetInterno(Connection c, String label, double recargoPct, int cuotas, boolean activo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO financiacion_presets (label, recargo_pct, cuotas, activo, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label);
            ps.setDouble(2, recargoPct);
            ps.setInt(3, cuotas);
            ps.setBoolean(4, activo);
            ps.setObject(5, Timestamps.now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    List<DatabaseService.Preset> listarPresets() {
        List<DatabaseService.Preset> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id, label, recargo_pct, cuotas, activo FROM financiacion_presets ORDER BY created_at, id")) {
            while (rs.next()) {
                result.add(new DatabaseService.Preset(
                        rs.getInt("id"), rs.getString("label"),
                        rs.getDouble("recargo_pct"), rs.getInt("cuotas"),
                        rs.getBoolean("activo")));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando presets: {}", e.getMessage());
        }
        return result;
    }

    Optional<DatabaseService.Preset> cargarPresetActivo() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id, label, recargo_pct, cuotas, activo FROM financiacion_presets WHERE activo LIMIT 1")) {
            if (rs.next()) {
                return Optional.of(new DatabaseService.Preset(
                        rs.getInt("id"), rs.getString("label"),
                        rs.getDouble("recargo_pct"), rs.getInt("cuotas"), true));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando preset activo: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Crea un preset nuevo, inactivo por defecto. Retorna el id generado, o -1 en
     * error o si {@code cuotas}/{@code recargoPct} son inválidos (mismo criterio
     * que {@code FinanciacionCalculator.compute}: cuotas&gt;0 y recargoPct&gt;-100).
     */
    int crearPreset(String label, double recargoPct, int cuotas) {
        if (cuotas <= 0 || recargoPct <= -100) {
            LOG.warn("[DB] crearPreset rechazado: cuotas={} recargoPct={} inválidos", cuotas, recargoPct);
            return -1;
        }
        try (Connection c = dataSource.getConnection()) {
            return crearPresetInterno(c, label, recargoPct, cuotas, false);
        } catch (Exception e) {
            LOG.warn("[DB] Error creando preset: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Edita label/recargoPct/cuotas de un preset existente. No altera su estado activo.
     * Retorna {@code false} sin persistir si {@code cuotas}/{@code recargoPct} son
     * inválidos (mismo criterio que {@code FinanciacionCalculator.compute}: cuotas&gt;0
     * y recargoPct&gt;-100), o si ocurre un error.
     */
    boolean editarPreset(int id, String label, double recargoPct, int cuotas) {
        if (cuotas <= 0 || recargoPct <= -100) {
            LOG.warn("[DB] editarPreset rechazado: cuotas={} recargoPct={} inválidos", cuotas, recargoPct);
            return false;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    UPDATE financiacion_presets SET label=?, recargo_pct=?, cuotas=? WHERE id=?
                    """)) {
            ps.setString(1, label);
            ps.setDouble(2, recargoPct);
            ps.setInt(3, cuotas);
            ps.setInt(4, id);
            int filasEditadas = ps.executeUpdate();
            if (filasEditadas == 0) {
                LOG.warn("[DB] editarPreset: id {} no existe.", id);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.warn("[DB] Error editando preset {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Activa el preset {@code id} y desactiva todos los demás, de forma transaccional.
     * Retorna {@code false} (y revierte la desactivación) si {@code id} no existe —
     * evita quedar sin ningún preset activo por un id inválido/obsoleto.
     */
    boolean activarPreset(int id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement psOff = c.prepareStatement(
                    "UPDATE financiacion_presets SET activo=false WHERE activo");
                 PreparedStatement psOn = c.prepareStatement(
                    "UPDATE financiacion_presets SET activo=true WHERE id=?")) {
                psOff.executeUpdate();
                psOn.setInt(1, id);
                int filasActivadas = psOn.executeUpdate();
                if (filasActivadas == 0) {
                    LOG.warn("[DB] activarPreset: id {} no existe, se revierte desactivación.", id);
                    c.rollback();
                    return false;
                }
                c.commit();
                return true;
            } catch (Exception e) {
                LOG.warn("[DB] Error activando preset {}: {}", id, e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error activando preset {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Elimina un preset. Comportamiento resuelto en el diseño para el caso
     * "borrar el preset activo":
     * <ul>
     *   <li>Si es el ÚNICO preset restante (activo o no) → se borra y se
     *       recrea el preset ilustrativo por defecto, activo (evita un estado
     *       de tabla vacía sin recuperación automática).</li>
     *   <li>Si quedan OTROS presets → se borra y NINGUNO se auto-activa; el
     *       catálogo cae a {@code sin_preset_activo} hasta que el usuario
     *       active uno explícitamente.</li>
     * </ul>
     *
     * @return {@code true} si el {@code id} pedido efectivamente existía y fue
     *         borrado; {@code false} si no existía (no-op) o si ocurrió un error.
     */
    boolean eliminarPreset(int id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int total;
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM financiacion_presets")) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }

                int filasBorradas;
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM financiacion_presets WHERE id=?")) {
                    ps.setInt(1, id);
                    filasBorradas = ps.executeUpdate();
                }

                if (filasBorradas > 0 && (total - filasBorradas) <= 0) {
                    crearPresetInterno(c, PRESET_ILUSTRATIVO_LABEL, PRESET_ILUSTRATIVO_RECARGO_PCT,
                            PRESET_ILUSTRATIVO_CUOTAS, true);
                    LOG.info("[DB] Último preset eliminado: preset ilustrativo recreado y activado.");
                }

                c.commit();
                return filasBorradas > 0;
            } catch (Exception e) {
                LOG.warn("[DB] Error eliminando preset {}: {}", id, e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error eliminando preset {}: {}", id, e.getMessage());
            return false;
        }
    }
}
