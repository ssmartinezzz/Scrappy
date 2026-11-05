package ar.scraper.db;

import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.model.SitioCategoriaNodo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
class SitioCategoriaRepository implements SitioCategoriaPort {

    private static final Logger LOG = LoggerFactory.getLogger(SitioCategoriaRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    SitioCategoriaRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int upsertArbol(String sitio, List<SitioCategoriaNodo> arbol) {
        if (sitio == null || arbol == null || arbol.isEmpty()) return 0;
        Integer cantidad = jdbc.queryForObject("SELECT sp_upsert_sitio_categoria(?, ?::jsonb)",
                Integer.class, sitio, nodosJson(arbol));
        return cantidad == null ? 0 : cantidad;
    }

    @Override
    public Map<Integer, String> categoriaPorOrigen(String sitio) {
        Map<Integer, String> categorias = new HashMap<>();
        if (sitio == null) return categorias;
        try {
            jdbc.query("SELECT c.origen_id, c.categoria FROM sitio_categoria c "
                            + "JOIN sitio s ON s.sitio_key = c.sitio_key "
                            + "WHERE s.nombre = ? AND c.categoria IS NOT NULL",
                    rs -> {
                        categorias.put(rs.getInt(1), rs.getString(2));
                    },
                    sitio);
        } catch (DataAccessException e) {
            LOG.warn("[SITIO-CATEGORIA] No se pudo leer el mapeo de {}: {}", sitio, e.getMessage());
            categorias.clear();
        }
        return categorias;
    }

    private static String nodosJson(List<SitioCategoriaNodo> arbol) {
        ArrayNode nodos = MAPPER.createArrayNode();
        for (SitioCategoriaNodo n : arbol) {
            ObjectNode nodo = nodos.addObject();
            nodo.put("origen_id", n.origenId());
            if (n.padreOrigenId() != null) nodo.put("padre_origen_id", n.padreOrigenId());
            nodo.put("nombre", n.nombre());
            nodo.put("es_servicio", n.esServicio());
        }
        try {
            return MAPPER.writeValueAsString(nodos);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
