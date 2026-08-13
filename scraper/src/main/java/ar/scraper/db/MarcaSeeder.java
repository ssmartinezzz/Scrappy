package ar.scraper.db;

import ar.scraper.aggregator.normalize.BrandExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * close-1nf-and-3nf-foundation extension, Phase 2 (design E4). Re-seeds
 * {@code marca} from {@link BrandExtractor#MARCAS} on every boot
 * (`ON CONFLICT DO NOTHING`) — {@code V21}'s static seed only exists to make
 * the {@code fk_productos_marca} constraint VALID at migrate time; this is
 * what lets a future curated brand be a one-line edit to {@code MARCAS}
 * rather than a new migration ({@code CODE-6}: {@code MARCAS} stays the
 * single owner, this table is its projection).
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)}: runs before anything else Spring
 * schedules as a runner, so a brand added to {@code MARCAS} is always seeded
 * before a scrape can reach {@code sp_upsert_run} — the FK's own failure
 * mode, if the seed lagged, is the project's signature silent one: a
 * rejected INSERT inside {@code ProductRepository}'s swallowed-error path
 * reads as {@code "0 nuevos"}, never as an error.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarcaSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MarcaSeeder.class);

    private final DataSource dataSource;

    public MarcaSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO marca (nombre) VALUES (?) ON CONFLICT DO NOTHING")) {
            for (String marca : BrandExtractor.MARCAS) {
                ps.setString(1, marca);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            LOG.warn("[MarcaSeeder] Error sembrando marca: {}", e.getMessage());
        }
    }
}
