package ar.scraper.db;

import ar.scraper.outfits.SavedOutfitsPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class SavedOutfitsRepository extends SavedBuildRepository implements SavedOutfitsPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    SavedOutfitsRepository(DataSource dataSource) {
        super(dataSource, "saved_outfits", "outfit");
    }

    /**
     * La firma sigue recibiendo JSON porque eso es lo que llega del borde HTTP; lo que cambió es la
     * FORMA EN QUE SE GUARDA.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int guardarOutfit(UUID usuarioId, String nombre, String slotsJson, String suplementosJson, double total) {
        return guardar(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO saved_outfits (usuario_id, nombre, total_estimado, created_at)
                    VALUES (?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setObject(1, usuarioId);
            ps.setString(2, nombre != null ? nombre : "Outfit");
            ps.setDouble(3, total);
            ps.setObject(4, Timestamps.now());
            return ps;
        }, id -> {
            insertarItems(id, "slot", "slot", slotsJson);
            insertarItems(id, "suplemento", "tipo", suplementosJson);
        });
    }

    /** Un ítem sin url se descarta — sin él la fila no apunta a nada. */
    private void insertarItems(int outfitId, String clase, String campoRanura, String json) {
        if (StringUtils.isBlank(json)) return;
        JsonNode arr;
        try {
            arr = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        if (!arr.isArray()) return;
        List<Object[]> filas = new ArrayList<>();
        short posicion = 1;
        for (JsonNode n : arr) {
            String url = n.path("url").asText("");
            if (url.isBlank()) continue;
            filas.add(new Object[] {outfitId, clase, posicion++, n.path(campoRanura).asText(""), url,
                    n.path("sitio").asText(""), n.path("nombre").asText(""), n.path("precio").asDouble(0),
                    n.path("img").asText(""), n.path("categoria").asText(""), n.path("marca").asText("")});
        }
        jdbc.batchUpdate("""
                INSERT INTO saved_outfit_item
                    (outfit_id, clase, posicion, ranura, url, sitio, nombre, precio, img, categoria, marca)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, filas);
    }

    @Override
    public List<Map<String, Object>> obtenerOutfitsGuardados(UUID usuarioId) {
        // Los ítems se cargan sólo para los outfits ya filtrados por dueño, así que heredan el
        // scope del padre sin repetir el WHERE.
        return listar(usuarioId,
                "SELECT id, nombre, total_estimado, created_at "
                        + "FROM saved_outfits WHERE usuario_id=? ORDER BY created_at DESC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",            rs.getInt("id"));
                    row.put("nombre",        rs.getString("nombre"));
                    row.put("totalEstimado", rs.getDouble("total_estimado"));
                    row.put("createdAt",     Timestamps.iso(rs, "created_at"));
                    row.put("slots", List.of());
                    row.put("suplementos", List.of());
                    return row;
                },
                this::cargarItems);
    }

    /**
     * Los ítems de TODOS los outfits en una sola consulta, mergeados por id — nunca una consulta
     * por outfit.
     */
    private void cargarItems(List<Map<String, Object>> outfits) {
        Map<Integer, List<Map<String, Object>>> slots = new LinkedHashMap<>();
        Map<Integer, List<Map<String, Object>>> suplementos = new LinkedHashMap<>();
        jdbc.query("""
                SELECT i.outfit_id, i.clase, i.ranura, i.url, i.sitio, i.nombre, i.precio,
                       i.img, i.categoria, i.marca, p.precio AS precio_actual,
                       p.producto_key
                FROM saved_outfit_item i
                LEFT JOIN productos p ON p.url = i.url
                ORDER BY i.outfit_id, i.clase, i.posicion
                """, (RowCallbackHandler) rs -> {
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
            item.put("key", rs.getString("producto_key"));
            (esSlot ? slots : suplementos)
                    .computeIfAbsent(rs.getInt("outfit_id"), k -> new ArrayList<>())
                    .add(item);
        });
        for (Map<String, Object> outfit : outfits) {
            int id = (Integer) outfit.get("id");
            outfit.put("slots",       slots.getOrDefault(id, List.of()));
            outfit.put("suplementos", suplementos.getOrDefault(id, List.of()));
        }
    }

    /** Elimina un outfit guardado por id. */
    @Override
    public boolean eliminarOutfitGuardado(UUID usuarioId, int id) {
        return eliminar(usuarioId, id);
    }

    /** Renombra un outfit guardado. */
    @Override
    public boolean renombrarOutfit(UUID usuarioId, int id, String nombre) {
        return renombrar(usuarioId, id, nombre);
    }
}
