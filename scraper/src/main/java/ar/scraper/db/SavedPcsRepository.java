package ar.scraper.db;

import ar.scraper.pcs.Gama;
import ar.scraper.pcs.GamaWire;
import ar.scraper.pcs.PcPick;
import ar.scraper.pcs.SavedPcsPort;
import ar.scraper.pcs.TechSpecs;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class SavedPcsRepository extends SavedBuildRepository implements SavedPcsPort {

    SavedPcsRepository(DataSource dataSource) {
        super(dataSource, "saved_pcs", "PC");
    }

    /**
     * Cabecera e ítems se escriben en UNA transacción: un build a medias —guardado pero sin picks—
     * es peor que no haberlo guardado.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int guardarPc(UUID usuarioId, String nombre, List<PcPick> picks, double presupuesto,
                         boolean conGpu, double totalEstimado, Gama gama) {
        return guardar(c -> {
            PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO saved_pcs (usuario_id, nombre, presupuesto, con_gpu, total_estimado, created_at, gama_id)
                    VALUES (?, ?, ?, ?, ?, ?, (SELECT id FROM gama WHERE nombre = ?))
                    """, new String[] {"id"});
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
            return ps;
        }, id -> insertarItems(id, picks));
    }

    /**
     * {@code null} and {@link Gama#DESCONOCIDA} both mean "no gama to record" —
     * {@code saved_pcs.gama_id} is nullable, unlike {@code preferencia_armador.gama_id}.
     */
    private static String gamaNombreOrNull(Gama gama) {
        return (gama == null || gama == Gama.DESCONOCIDA) ? null : GamaMapeo.nombreDeGama(gama);
    }

    /** Un pick sin url se descarta — sin ella la fila no apunta a nada. */
    private void insertarItems(int pcId, List<PcPick> picks) {
        if (picks == null || picks.isEmpty()) return;
        List<PcPick> conUrl = picks.stream().filter(p -> StringUtils.isNotBlank(p.url())).toList();
        jdbc.batchUpdate("""
                INSERT INTO saved_pc_item
                    (pc_id, posicion, slot, url, sitio, nombre, precio, img, marca,
                     socket, ddr, form_factor, watts, capacidad_gb, tipo_memoria)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PcPick pick = conUrl.get(i);
                TechSpecs specs = pick.specs() != null ? pick.specs() : TechSpecs.EMPTY;
                ps.setInt(1, pcId);
                ps.setShort(2, (short) (i + 1));
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
            }

            @Override
            public int getBatchSize() {
                return conUrl.size();
            }
        });
    }

    @Override
    public List<Map<String, Object>> obtenerPcsGuardadas(UUID usuarioId) {
        // Los ítems se cargan sólo para los PCs ya filtrados por dueño, así que heredan el
        // scope del padre sin repetir el WHERE.
        return listar(usuarioId, """
                SELECT sp.id, sp.nombre, sp.presupuesto, sp.con_gpu, sp.total_estimado, sp.created_at,
                       g.nombre AS gama_nombre
                FROM saved_pcs sp
                LEFT JOIN gama g ON g.id = sp.gama_id
                WHERE sp.usuario_id=? ORDER BY sp.created_at DESC
                """,
                (rs, i) -> {
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
                    return row;
                },
                this::cargarItems);
    }

    /**
     * Los ítems de TODOS los PCs en una sola consulta, mergeados por id — nunca una consulta por
     * PC.
     */
    private void cargarItems(List<Map<String, Object>> pcs) {
        Map<Integer, List<Map<String, Object>>> porPc = new LinkedHashMap<>();
        jdbc.query("""
                SELECT i.pc_id, i.slot, i.url, i.sitio, i.nombre, i.precio, i.img, i.marca,
                       i.socket, i.ddr, i.form_factor, i.watts, i.capacidad_gb, i.tipo_memoria,
                       p.precio AS precio_actual, p.producto_key
                FROM saved_pc_item i
                LEFT JOIN productos p ON p.url = i.url
                ORDER BY i.pc_id, i.posicion
                """, (RowCallbackHandler) rs -> {
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
            // Mismo motivo que en SavedOutfitsRepository: el handle corto es lo único que el
            // cliente no puede derivar solo, y es lo que le permite al panel de detalle pedir
            // la fila entera.
            item.put("key", rs.getString("producto_key"));
            porPc.computeIfAbsent(rs.getInt("pc_id"), k -> new ArrayList<>()).add(item);
        });
        for (Map<String, Object> pc : pcs) {
            int id = (Integer) pc.get("id");
            pc.put("picks", porPc.getOrDefault(id, List.of()));
        }
    }

    @Override
    public boolean eliminarPcGuardada(UUID usuarioId, int id) {
        return eliminar(usuarioId, id);
    }

    @Override
    public boolean renombrarPc(UUID usuarioId, int id, String nombre) {
        return renombrar(usuarioId, id, nombre);
    }
}
