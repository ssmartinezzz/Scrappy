package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code WHERE usuario_id =:subject} never matches {@code NULL}, so such a row is invisible to
 * everybody rather than visible to everybody. A warning, not a failure.
 */
@Component
@Order(100)   // after AdminSeeder, whose adoption is what should have emptied these
public class UnownedRowsWarner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(UnownedRowsWarner.class);

    private static final List<String> TABLAS =
            List.of("favoritos", "saved_outfits", "outfit_feedback_item", "categoria_dismiss");

    private final DataSource dataSource;

    public UnownedRowsWarner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Integer> huerfanas = contar();
        int total = huerfanas.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return;
        }
        LOG.warn("""

                ╔══════════════════════════════════════════════════════════════╗
                ║  {} FILAS PERSONALES SIN DUEÑO                                 
                ╠══════════════════════════════════════════════════════════════╣
                ║  {}
                ║
                ║  Las lecturas están scopeadas por usuario, y NULL no matchea
                ║  con nadie: estas filas son invisibles para TODOS. No se
                ║  perdieron — están ahí y se recuperan asignándoles dueño:
                ║
                ║    UPDATE <tabla> SET usuario_id =
                ║      (SELECT id FROM usuario WHERE username = '<tu-admin>')
                ║     WHERE usuario_id IS NULL;
                ╚══════════════════════════════════════════════════════════════╝
                """, total, detalle(huerfanas));
    }

    Map<String, Integer> contar() {
        Map<String, Integer> resultado = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement()) {
            for (String tabla : TABLAS) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM " + tabla + " WHERE usuario_id IS NULL")) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        resultado.put(tabla, rs.getInt(1));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[AUTH] no se pudieron contar las filas sin dueño: {}", e.getMessage());
        }
        return resultado;
    }

    private static String detalle(Map<String, Integer> huerfanas) {
        StringBuilder sb = new StringBuilder();
        huerfanas.forEach((tabla, n) -> sb.append(tabla).append('=').append(n).append("  "));
        return sb.toString().trim();
    }
}
