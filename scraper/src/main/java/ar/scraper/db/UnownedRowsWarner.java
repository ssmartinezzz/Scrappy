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
 * Startup check: are there personal rows nobody owns?
 *
 * <p>Belt to the adoption braces. {@code AdminSeeder} claims every ownerless row
 * at startup, so in a healthy installation this finds nothing and says nothing.
 * It exists because of what an ownerless row means <b>now that reads are
 * scoped</b>: {@code WHERE usuario_id = :subject} never matches {@code NULL}, so
 * such a row is invisible to <b>everybody</b> rather than visible to everybody.</p>
 *
 * <p>That is the safe direction to fail in — an invisible row can be adopted and
 * reappears, whereas a leaked one cannot be un-leaked — but from the user's chair
 * it looks exactly like their favourites vanished. A WARN naming the counts and
 * the SQL to fix it turns a mystery into a two-minute job.</p>
 *
 * <p><b>A warning, not a failure.</b> Refusing to start over legacy data would be
 * worse than surfacing it: the rows are invisible, not exposed, and an operator
 * locked out of their own application cannot fix anything.</p>
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
