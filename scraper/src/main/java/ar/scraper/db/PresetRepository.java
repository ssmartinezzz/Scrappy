package ar.scraper.db;

import ar.scraper.financiacion.Preset;
import ar.scraper.financiacion.PresetPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code crearPresetInterno} stays private — it is a helper shared by two port methods, not part of
 * the port surface.
 */
@Repository
class PresetRepository implements PresetPort {

    private static final Logger LOG = LoggerFactory.getLogger(PresetRepository.class);

    private static final String PRESET_ILUSTRATIVO_LABEL =
            "Ejemplo — 12 cuotas / 40% recargo (editá este valor)";
    private static final double PRESET_ILUSTRATIVO_RECARGO_PCT = 40.0;
    private static final int    PRESET_ILUSTRATIVO_CUOTAS      = 12;

    private final JdbcTemplate jdbc;

    PresetRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * En el primer arranque (tabla vacía), crea un preset ilustrativo marcado explícitamente como
     * ejemplo y lo deja activo, para que la señal de financiación tenga un valor de referencia
     * desde el día uno sin requerir que el usuario configure nada manualmente.
     */
    @Override
    public void seedPresetIlustrativoSiVacio() {
        Sql.traducir(() -> {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM financiacion_presets", Integer.class);
            if (total != null && total == 0) {
                crearPresetInterno(PRESET_ILUSTRATIVO_LABEL, PRESET_ILUSTRATIVO_RECARGO_PCT,
                        PRESET_ILUSTRATIVO_CUOTAS, true);
                LOG.info("[DB] Preset ilustrativo creado y activado (tabla vacía).");
            }
        });
    }

    private int crearPresetInterno(String label, double recargoPct, int cuotas, boolean activo) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO financiacion_presets (label, recargo_pct, cuotas, activo, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, label);
            ps.setDouble(2, recargoPct);
            ps.setInt(3, cuotas);
            ps.setBoolean(4, activo);
            ps.setObject(5, Timestamps.now());
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key != null ? key.intValue() : -1;
    }

    @Override
    public List<Preset> listarPresets() {
        List<Preset> result = new ArrayList<>();
        try {
            jdbc.query("SELECT id, label, recargo_pct, cuotas, activo FROM financiacion_presets ORDER BY created_at, id",
                    rs -> {
                        result.add(new Preset(
                                rs.getInt("id"), rs.getString("label"),
                                rs.getDouble("recargo_pct"), rs.getInt("cuotas"),
                                rs.getBoolean("activo")));
                    });
        } catch (Exception e) {
            LOG.warn("[DB] Error listando presets: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Optional<Preset> cargarPresetActivo() {
        try {
            return jdbc.query("SELECT id, label, recargo_pct, cuotas, activo FROM financiacion_presets WHERE activo LIMIT 1",
                    rs -> rs.next()
                            ? Optional.of(new Preset(
                                    rs.getInt("id"), rs.getString("label"),
                                    rs.getDouble("recargo_pct"), rs.getInt("cuotas"), true))
                            : Optional.<Preset>empty());
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando preset activo: {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public int crearPreset(String label, double recargoPct, int cuotas) {
        if (cuotas <= 0 || recargoPct <= -100) {
            LOG.warn("[DB] crearPreset rechazado: cuotas={} recargoPct={} inválidos", cuotas, recargoPct);
            return -1;
        }
        try {
            return crearPresetInterno(label, recargoPct, cuotas, false);
        } catch (Exception e) {
            LOG.warn("[DB] Error creando preset: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean editarPreset(int id, String label, double recargoPct, int cuotas) {
        if (cuotas <= 0 || recargoPct <= -100) {
            LOG.warn("[DB] editarPreset rechazado: cuotas={} recargoPct={} inválidos", cuotas, recargoPct);
            return false;
        }
        try {
            int filasEditadas = jdbc.update("""
                    UPDATE financiacion_presets SET label=?, recargo_pct=?, cuotas=? WHERE id=?
                    """, ps -> {
                ps.setString(1, label);
                ps.setDouble(2, recargoPct);
                ps.setInt(3, cuotas);
                ps.setInt(4, id);
            });
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

    /** Activa el preset {@code id} y desactiva todos los demás, de forma transaccional. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean activarPreset(int id) {
        try {
            jdbc.update("UPDATE financiacion_presets SET activo=false WHERE activo");
            if (jdbc.update("UPDATE financiacion_presets SET activo=true WHERE id=?", id) == 0) {
                LOG.warn("[DB] activarPreset: id {} no existe, se revierte desactivación.", id);
                Sql.marcarRollback();
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            LOG.warn("[DB] Error activando preset {}: {}", id, e.getMessage());
            Sql.marcarRollback();
            return false;
        }
    }

    /**
     * Si es el ÚNICO preset restante (activo o no) → se borra y se recrea el preset ilustrativo por
     * defecto, activo (evita un estado de tabla vacía sin recuperación automática).
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean eliminarPreset(int id) {
        try {
            Integer cuenta = jdbc.queryForObject("SELECT COUNT(*) FROM financiacion_presets", Integer.class);
            int total = cuenta != null ? cuenta : 0;

            int filasBorradas = jdbc.update("DELETE FROM financiacion_presets WHERE id=?", id);

            if (filasBorradas > 0 && (total - filasBorradas) <= 0) {
                crearPresetInterno(PRESET_ILUSTRATIVO_LABEL, PRESET_ILUSTRATIVO_RECARGO_PCT,
                        PRESET_ILUSTRATIVO_CUOTAS, true);
                LOG.info("[DB] Último preset eliminado: preset ilustrativo recreado y activado.");
            }

            return filasBorradas > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando preset {}: {}", id, e.getMessage());
            Sql.marcarRollback();
            return false;
        }
    }
}
