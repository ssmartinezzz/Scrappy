package ar.scraper.db;

import ar.scraper.indices.Indice;
import ar.scraper.indices.IndicePort;
import ar.scraper.indices.PuntoIndice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** V33 — persistence for {@code indice_valor}. */
@Repository
class IndiceRepository implements IndicePort {

    private static final Logger LOG = LoggerFactory.getLogger(IndiceRepository.class);

    private final DataSource dataSource;

    IndiceRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void guardar(List<PuntoIndice> puntos) {
        if (puntos == null || puntos.isEmpty()) return;
        String sql = """
                INSERT INTO indice_valor (indice, fecha, valor) VALUES (?, ?, ?)
                ON CONFLICT (indice, fecha) DO UPDATE SET valor = excluded.valor
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (PuntoIndice p : puntos) {
                ps.setString(1, p.indice().name());
                ps.setObject(2, p.fecha());
                ps.setDouble(3, p.valor());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando {} punto(s) de índice: {}", puntos.size(), e.getMessage());
        }
    }

    @Override
    public List<PuntoIndice> serie(Indice indice) {
        List<PuntoIndice> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fecha, valor FROM indice_valor WHERE indice=? ORDER BY fecha")) {
            ps.setString(1, indice.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PuntoIndice(indice, rs.getObject("fecha", java.time.LocalDate.class),
                            rs.getDouble("valor")));
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error leyendo serie de {}: {}", indice, e.getMessage());
        }
        return result;
    }
}
