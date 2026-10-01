package ar.scraper.db;

import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.classification.SiteSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Component
class JdbcSiteSource implements SiteSource {

    private final DataSource dataSource;

    JdbcSiteSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Map<String, Sitio> cargar() {
        return Sql.traducir(() -> {
            Map<String, Sitio> sitios = new HashMap<>();
            try (var c = dataSource.getConnection();
                 var st = c.createStatement();
                 var rs = st.executeQuery(
                         "SELECT nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen FROM sitio")) {
                while (rs.next()) {
                    String key = rs.getString("sitio_key");
                    sitios.put(key, new Sitio(
                            rs.getString("nombre"), key, rs.getString("plataforma"),
                            rs.getBoolean("es_premium"), rs.getString("rubro_forzado"), rs.getString("origen")));
                }
            }
            return sitios;
        });
    }
}
