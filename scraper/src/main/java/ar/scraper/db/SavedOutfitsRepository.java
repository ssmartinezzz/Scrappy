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
import java.util.UUID;
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
     * Persiste un outfit con sus ítems en filas propias (V14), no como un blob.
     *
     * <p>La firma sigue recibiendo JSON porque eso es lo que llega del borde
     * HTTP; lo que cambió es la FORMA EN QUE SE GUARDA. El parseo pasó a ser un
     * detalle de este método en vez de la estructura de la tabla.</p>
     *
     * <p>Cabecera e ítems se escriben en UNA transacción: un outfit a medias
     * —guardado pero sin prendas— es peor que no haberlo guardado.</p>
     */
    int guardarOutfit(UUID usuarioId, String nombre, String slotsJson, String suplementosJson, double total) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO saved_outfits (usuario_id, nombre, total_estimado, created_at)
                        VALUES (?, ?, ?, ?)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setObject(1, usuarioId);
                    ps.setString(2, nombre != null ? nombre : "Outfit");
                    ps.setDouble(3, total);
                    ps.setObject(4, Timestamps.now());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) { c.rollback(); return -1; }
                        id = keys.getInt(1);
                    }
                }
                insertarItems(c, id, "slot", "slot", slotsJson);
                insertarItems(c, id, "suplemento", "tipo", suplementosJson);
                c.commit();
                return id;
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando outfit, rollback: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * {@code campoRanura} es la única diferencia entre las dos listas: los slots
     * traen {@code slot} ("torso") y los suplementos {@code tipo} ("Proteína").
     * Un ítem sin url se descarta — sin él la fila no apunta a nada.
     */
    private void insertarItems(Connection c, int outfitId, String clase, String campoRanura, String json)
            throws Exception {
        if (json == null || json.isBlank()) return;
        com.fasterxml.jackson.databind.JsonNode arr = MAPPER.readTree(json);
        if (!arr.isArray()) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO saved_outfit_item
                    (outfit_id, clase, posicion, ranura, url, sitio, nombre, precio, img, categoria, marca)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            short posicion = 1;
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String url = n.path("url").asText("");
                if (url.isBlank()) continue;
                ps.setInt(1, outfitId);
                ps.setString(2, clase);
                ps.setShort(3, posicion++);
                ps.setString(4, n.path(campoRanura).asText(""));
                ps.setString(5, url);
                ps.setString(6, n.path("sitio").asText(""));
                ps.setString(7, n.path("nombre").asText(""));
                ps.setDouble(8, n.path("precio").asDouble(0));
                ps.setString(9, n.path("img").asText(""));
                ps.setString(10, n.path("categoria").asText(""));
                ps.setString(11, n.path("marca").asText(""));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Retorna todos los outfits guardados, ordenados por created_at DESC. */
    List<Map<String, Object>> obtenerOutfitsGuardados(UUID usuarioId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id, nombre, total_estimado, created_at " +
                "FROM saved_outfits WHERE usuario_id=? ORDER BY created_at DESC")) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",            rs.getInt("id"));
                row.put("nombre",        rs.getString("nombre"));
                row.put("totalEstimado", rs.getDouble("total_estimado"));
                row.put("createdAt",     Timestamps.iso(rs, "created_at"));
                row.put("slots", List.of());
                row.put("suplementos", List.of());
                result.add(row);
            }
            }
            // Los ítems se cargan sólo para los outfits ya filtrados por dueño,
            // así que heredan el scope del padre sin repetir el WHERE.
            cargarItems(c, result);
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo outfits guardados: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Los ítems de TODOS los outfits en una sola consulta, mergeados por id —
     * nunca una consulta por outfit.
     *
     * <p>Cada ítem devuelve la FOTO (lo que el producto era cuando se guardó) y
     * además {@code precioActual}, que sale de un LEFT JOIN contra el catálogo
     * vivo. Si el producto ya no existe, {@code precioActual} viene {@code null}
     * y el outfit sigue mostrándose igual: por eso {@code url} no lleva FK.</p>
     */
    private void cargarItems(Connection c, List<Map<String, Object>> outfits) throws Exception {
        Map<Integer, List<Map<String, Object>>> slots = new LinkedHashMap<>();
        Map<Integer, List<Map<String, Object>>> suplementos = new LinkedHashMap<>();
        try (java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT i.outfit_id, i.clase, i.ranura, i.url, i.sitio, i.nombre, i.precio,
                            i.img, i.categoria, i.marca, p.precio AS precio_actual
                     FROM saved_outfit_item i
                     LEFT JOIN productos p ON p.url = i.url
                     ORDER BY i.outfit_id, i.clase, i.posicion
                     """)) {
            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                boolean esSlot = "slot".equals(rs.getString("clase"));
                item.put(esSlot ? "slot" : "tipo", rs.getString("ranura"));
                item.put("sitio",  rs.getString("sitio"));
                item.put("nombre", rs.getString("nombre"));
                item.put("precio", rs.getDouble("precio"));
                item.put("url",    rs.getString("url"));
                item.put("img",    rs.getString("img"));
                if (esSlot) item.put("categoria", rs.getString("categoria"));
                item.put("marca",  rs.getString("marca"));
                double precioActual = rs.getDouble("precio_actual");
                item.put("precioActual", rs.wasNull() ? null : precioActual);
                (esSlot ? slots : suplementos)
                        .computeIfAbsent(rs.getInt("outfit_id"), k -> new ArrayList<>())
                        .add(item);
            }
        }
        for (Map<String, Object> outfit : outfits) {
            int id = (Integer) outfit.get("id");
            outfit.put("slots",       slots.getOrDefault(id, List.of()));
            outfit.put("suplementos", suplementos.getOrDefault(id, List.of()));
        }
    }

    /** Elimina un outfit guardado por id. Retorna true si existía. */
    /**
     * @return {@code false} when the outfit does not exist <b>or belongs to
     *         somebody else</b>. The two are one answer on purpose: telling a
     *         caller "that exists but is not yours" confirms the existence of
     *         another user's row, which is the disclosure the scoping prevents.
     */
    boolean eliminarOutfitGuardado(UUID usuarioId, int id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM saved_outfits WHERE usuario_id=? AND id=?")) {
            ps.setObject(1, usuarioId);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando outfit guardado {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Renombra un outfit guardado. Retorna true si existía. */
    boolean renombrarOutfit(UUID usuarioId, int id, String nombre) {
        if (nombre == null || nombre.isBlank()) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE saved_outfits SET nombre=? WHERE usuario_id=? AND id=?")) {
            ps.setString(1, nombre.trim());
            ps.setObject(2, usuarioId);
            ps.setInt(3, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error renombrando outfit {}: {}", id, e.getMessage());
            return false;
        }
    }
}
