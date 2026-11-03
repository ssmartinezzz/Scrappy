package ar.scraper.db;

import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.model.SitioCategoriaNodo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

@Repository
class SitioCategoriaRepository implements SitioCategoriaPort {

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
