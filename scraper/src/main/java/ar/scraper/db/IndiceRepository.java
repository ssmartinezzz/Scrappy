package ar.scraper.db;

import ar.scraper.indices.Indice;
import ar.scraper.indices.IndicePort;
import ar.scraper.indices.PuntoIndice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@Repository
class IndiceRepository implements IndicePort {

    private static final Logger LOG = LoggerFactory.getLogger(IndiceRepository.class);

    private final JdbcTemplate jdbc;

    IndiceRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void guardar(List<PuntoIndice> puntos) {
        if (puntos == null || puntos.isEmpty()) return;
        String sql = """
                INSERT INTO indice_valor (indice, fecha, valor) VALUES (?, ?, ?)
                ON CONFLICT (indice, fecha) DO UPDATE SET valor = excluded.valor
                """;
        try {
            jdbc.batchUpdate(sql, puntos, puntos.size(), (ps, p) -> {
                ps.setString(1, p.indice().name());
                ps.setObject(2, p.fecha());
                ps.setDouble(3, p.valor());
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando {} punto(s) de índice: {}", puntos.size(), e.getMessage());
        }
    }

    @Override
    public List<PuntoIndice> serie(Indice indice) {
        List<PuntoIndice> result = new ArrayList<>();
        try {
            jdbc.query("SELECT fecha, valor FROM indice_valor WHERE indice=? ORDER BY fecha",
                    rs -> {
                        result.add(new PuntoIndice(indice, rs.getObject("fecha", java.time.LocalDate.class),
                                rs.getDouble("valor")));
                    },
                    indice.name());
        } catch (Exception e) {
            LOG.warn("[DB] Error leyendo serie de {}: {}", indice, e.getMessage());
        }
        return result;
    }
}
