package ar.scraper.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence for the {@code saved_outfits} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 */
class SavedOutfitsRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SavedOutfitsRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;

    SavedOutfitsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Persiste un outfit generado con su nombre, slots y suplementos en JSON, y el
     * total estimado. Retorna el id generado, o -1 en error.
     */
    int guardarOutfit(String nombre, String slotsJson, String suplementosJson, double total) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO saved_outfits (nombre, slots_json, suplementos_json, total_estimado, created_at)
                    VALUES (?, ?::jsonb, ?::jsonb, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nombre != null ? nombre : "Outfit");
            ps.setString(2, slotsJson != null ? slotsJson : "[]");
            ps.setString(3, suplementosJson);
            ps.setDouble(4, total);
            ps.setObject(5, Timestamps.now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit: {}", e.getMessage());
            return -1;
        }
    }

    /** Retorna todos los outfits guardados, ordenados por created_at DESC. */
    List<Map<String, Object>> obtenerOutfitsGuardados() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id, nombre, slots_json, suplementos_json, total_estimado, created_at " +
                "FROM saved_outfits ORDER BY created_at DESC")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",            rs.getInt("id"));
                row.put("nombre",        rs.getString("nombre"));
                row.put("totalEstimado", rs.getDouble("total_estimado"));
                row.put("createdAt",     Timestamps.iso(rs, "created_at"));
                String slotsJson = rs.getString("slots_json");
                String suplJson  = rs.getString("suplementos_json");
                try { row.put("slots",       MAPPER.readValue(slotsJson, List.class)); }
                catch (Exception e) { row.put("slots", List.of()); }
                try { row.put("suplementos", suplJson != null ? MAPPER.readValue(suplJson, List.class) : List.of()); }
                catch (Exception e) { row.put("suplementos", List.of()); }
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo outfits guardados: {}", e.getMessage());
        }
        return result;
    }

    /** Elimina un outfit guardado por id. Retorna true si existía. */
    boolean eliminarOutfitGuardado(int id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM saved_outfits WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando outfit guardado {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Renombra un outfit guardado. Retorna true si existía. */
    boolean renombrarOutfit(int id, String nombre) {
        if (nombre == null || nombre.isBlank()) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE saved_outfits SET nombre=? WHERE id=?")) {
            ps.setString(1, nombre.trim());
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error renombrando outfit {}: {}", id, e.getMessage());
            return false;
        }
    }
}
