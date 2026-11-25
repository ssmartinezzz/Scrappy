package ar.scraper.db;

import ar.scraper.classification.RubroCatalogo;
import ar.scraper.classification.RubroCatalogo.CategoriaRubro;
import ar.scraper.classification.RubroCatalogo.Rubro;
import ar.scraper.classification.RubroSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unlike {@link JdbcSiteSource}'s consumer, nothing here swallows a failure: a taxonomy that silently comes back
 * empty would reclassify every product, so a failed read fails the bean.
 */
@Component
@DependsOnDatabaseInitialization
class JdbcRubroSource implements RubroSource {

    private final JdbcTemplate jdbc;

    JdbcRubroSource(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public RubroCatalogo cargar() {
        return Sql.traducir(() -> {
            List<Rubro> rubros = jdbc.query(
                    "SELECT clave, etiqueta, orden, indice, peso_forzado, peso_categoria FROM rubro",
                    (rs, i) -> new Rubro(rs.getString("clave"), rs.getString("etiqueta"), rs.getInt("orden"),
                            rs.getString("indice"), rs.getInt("peso_forzado"), rs.getInt("peso_categoria")));
            List<CategoriaRubro> filas = jdbc.query(
                    "SELECT categoria, sub_categoria, rubro, pisa_forzado FROM categoria_rubro",
                    (rs, i) -> new CategoriaRubro(rs.getString("categoria"), rs.getString("sub_categoria"),
                            rs.getString("rubro"), rs.getBoolean("pisa_forzado")));
            Map<String, String> indicePorCategoria = new HashMap<>();
            jdbc.query("SELECT nombre, indice FROM categoria WHERE indice IS NOT NULL",
                    rs -> {
                        indicePorCategoria.put(rs.getString("nombre"), rs.getString("indice"));
                    });
            return new RubroCatalogo(rubros, filas, indicePorCategoria);
        });
    }
}
