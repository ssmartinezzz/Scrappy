package ar.scraper.db;

import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.classification.SiteSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Component
class JdbcSiteSource implements SiteSource {

    private final JdbcTemplate jdbc;

    JdbcSiteSource(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Map<String, Sitio> cargar() {
        return Sql.traducir(() -> {
            Map<String, Sitio> sitios = new HashMap<>();
            jdbc.query("SELECT nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen FROM sitio",
                    rs -> {
                        String key = rs.getString("sitio_key");
                        sitios.put(key, new Sitio(
                                rs.getString("nombre"), key, rs.getString("plataforma"),
                                rs.getBoolean("es_premium"), rs.getString("rubro_forzado"), rs.getString("origen")));
                    });
            return sitios;
        });
    }
}
