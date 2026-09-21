package ar.scraper.db;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.GamaWire;
import ar.scraper.pcs.PcPick;
import ar.scraper.pcs.SavedPcsPort;
import ar.scraper.pcs.TechSpecs;
import org.springframework.stereotype.Repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * Persistence for the {@code saved_pcs} aggregate. Same shape as
 * {@link SavedOutfitsRepository} (V34).
 */
@Repository
class SavedPcsRepository implements SavedPcsPort {

    private static final Logger LOG = LoggerFactory.getLogger(SavedPcsRepository.class);

    private final DataSource dataSource;

    SavedPcsRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Cabecera e ítems se escriben en UNA transacción: un build a medias
     * —guardado pero sin picks— es peor que no haberlo guardado.
     */
    @Override
    public int guardarPc(UUID usuarioId, String nombre, List<PcPick> picks, double presupuesto,
                         boolean conGpu, double totalEstimado, Gama gama) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO saved_pcs (usuario_id, nombre, presupuesto, con_gpu, total_estimado, created_at, gama_id)
                        VALUES (?, ?, ?, ?, ?, ?, (SELECT id FROM gama WHERE nombre = ?))
                        """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setObject(1, usuarioId);
                    ps.setString(2, nombre != null ? nombre : "PC");
                    ps.setDouble(3, presupuesto);
                    ps.setBoolean(4, conGpu);
                    ps.setDouble(5, totalEstimado);
                    ps.setObject(6, Timestamps.now());
                    String gamaNombre = gamaNombreOrNull(gama);
                    if (gamaNombre == null) {
                        ps.setNull(7, Types.VARCHAR);
                    } else {
                        ps.setString(7, gamaNombre);
                    }
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) { c.rollback(); return -1; }
                        id = keys.getInt(1);
                    }
                }
                insertarItems(c, id, picks);
                c.commit();
                return id;
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando PC, rollback: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando PC: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * {@code null} and {@link Gama#DESCONOCIDA} both mean "no gama to
     * record" — {@code saved_pcs.gama_id} is nullable, unlike {@code
     * preferencia_armador.gama_id} (D10, pc-builder-gama T6).
     */
    private static String gamaNombreOrNull(Gama gama) {
        return (gama == null || gama == Gama.DESCONOCIDA) ? null : GamaMapeo.nombreDeGama(gama);
    }

    /** Un pick sin url se descarta — sin ella la fila no apunta a nada. */
    private void insertarItems(Connection c, int pcId, List<PcPick> picks) throws Exception {
        if (picks == null || picks.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO saved_pc_item
                    (pc_id, posicion, slot, url, sitio, nombre, precio, img, marca,
                     socket, ddr, form_factor, watts, capacidad_gb, tipo_memoria)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            short posicion = 1;
            for (PcPick pick : picks) {
                if (pick.url() == null || pick.url().isBlank()) continue;
                TechSpecs specs = pick.specs() != null ? pick.specs() : TechSpecs.EMPTY;
                ps.setInt(1, pcId);
                ps.setShort(2, posicion++);
                ps.setString(3, pick.slot());
                ps.setString(4, pick.url());
                ps.setString(5, pick.sitio());
                ps.setString(6, pick.nombre());
                ps.setDouble(7, pick.precio());
                ps.setString(8, pick.img());
                ps.setString(9, pick.marca());
                ps.setString(10, specs.socket());
                ps.setString(11, specs.ddr());
                ps.setString(12, specs.formFactor());
                ps.setInt(13, specs.watts());
                ps.setInt(14, specs.capacidadGb());
                ps.setString(15, specs.tipoMemoria());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public List<Map<String, Object>> obtenerPcsGuardadas(UUID usuarioId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                SELECT sp.id, sp.nombre, sp.presupuesto, sp.con_gpu, sp.total_estimado, sp.created_at,
                       g.nombre AS gama_nombre
                FROM saved_pcs sp
                LEFT JOIN gama g ON g.id = sp.gama_id
                WHERE sp.usuario_id=? ORDER BY sp.created_at DESC
                """)) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",            rs.getInt("id"));
                    row.put("nombre",        rs.getString("nombre"));
                    row.put("presupuesto",   rs.getDouble("presupuesto"));
                    row.put("conGpu",        rs.getBoolean("con_gpu"));
                    row.put("totalEstimado", rs.getDouble("total_estimado"));
                    row.put("createdAt",     Timestamps.iso(rs, "created_at"));
                    String gamaNombre = rs.getString("gama_nombre");
                    row.put("gama", gamaNombre != null ? GamaWire.wire(GamaMapeo.gamaDeNombre(gamaNombre)) : null);
                    row.put("picks", List.of());
                    result.add(row);
                }
            }
            // Los ítems se cargan sólo para los PCs ya filtrados por dueño, así
            // que heredan el scope del padre sin repetir el WHERE.
            cargarItems(c, result);
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo PCs guardadas: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Los ítems de TODOS los PCs en una sola consulta, mergeados por id —
     * nunca una consulta por PC. Igual que {@link SavedOutfitsRepository}, cada
     * ítem devuelve la FOTO más {@code precioActual} vía LEFT JOIN contra el
     * catálogo vivo; {@code null} si el producto ya no existe.
     */
    private void cargarItems(Connection c, List<Map<String, Object>> pcs) throws Exception {
        Map<Integer, List<Map<String, Object>>> porPc = new LinkedHashMap<>();
        try (java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT i.pc_id, i.slot, i.url, i.sitio, i.nombre, i.precio, i.img, i.marca,
                            i.socket, i.ddr, i.form_factor, i.watts, i.capacidad_gb, i.tipo_memoria,
                            p.precio AS precio_actual
                     FROM saved_pc_item i
                     LEFT JOIN productos p ON p.url = i.url
                     ORDER BY i.pc_id, i.posicion
                     """)) {
            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("slot",   rs.getString("slot"));
                item.put("sitio",  rs.getString("sitio"));
                item.put("nombre", rs.getString("nombre"));
                item.put("precio", rs.getDouble("precio"));
                item.put("url",    rs.getString("url"));
                item.put("img",    rs.getString("img"));
                item.put("marca",  rs.getString("marca"));
                Map<String, Object> specs = new LinkedHashMap<>();
                specs.put("socket",      rs.getString("socket"));
                specs.put("ddr",         rs.getString("ddr"));
                specs.put("formFactor",  rs.getString("form_factor"));
                specs.put("watts",       rs.getInt("watts"));
                specs.put("capacidadGb", rs.getInt("capacidad_gb"));
                specs.put("tipoMemoria", rs.getString("tipo_memoria"));
                item.put("specs", specs);
                double precioActual = rs.getDouble("precio_actual");
                item.put("precioActual", rs.wasNull() ? null : precioActual);
                porPc.computeIfAbsent(rs.getInt("pc_id"), k -> new ArrayList<>()).add(item);
            }
        }
        for (Map<String, Object> pc : pcs) {
            int id = (Integer) pc.get("id");
            pc.put("picks", porPc.getOrDefault(id, List.of()));
        }
    }

    /**
     * @return {@code false} cuando el PC no existe o es de otro usuario — las dos
     *         cosas se responden igual a propósito, mismo motivo que
     *         {@link SavedOutfitsRepository#eliminarOutfitGuardado}.
     */
    @Override
    public boolean eliminarPcGuardada(UUID usuarioId, int id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM saved_pcs WHERE usuario_id=? AND id=?")) {
            ps.setObject(1, usuarioId);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando PC guardado {}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean renombrarPc(UUID usuarioId, int id, String nombre) {
        if (nombre == null || nombre.isBlank()) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE saved_pcs SET nombre=? WHERE usuario_id=? AND id=?")) {
            ps.setString(1, nombre.trim());
            ps.setObject(2, usuarioId);
            ps.setInt(3, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error renombrando PC {}: {}", id, e.getMessage());
            return false;
        }
    }
}
