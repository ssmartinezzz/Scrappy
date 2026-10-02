package ar.scraper.db;

import ar.scraper.classification.BrandExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * {@code @Order(HIGHEST_PRECEDENCE)}: runs before anything else Spring schedules as a runner, so a
 * brand added to {@code MARCAS} is always seeded before a scrape can reach {@code sp_upsert_run} —
 * the FK's own failure mode, if the seed lagged, is the project's signature silent one: a rejected
 * INSERT inside {@code ProductRepository}'s swallowed-error path reads as {@code "0 nuevos"}, never
 * as an error.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarcaSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MarcaSeeder.class);

    private final JdbcTemplate jdbc;

    public MarcaSeeder(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.batchUpdate("INSERT INTO marca (nombre) VALUES (?) ON CONFLICT DO NOTHING",
                    BrandExtractor.MARCAS, BrandExtractor.MARCAS.size(), (ps, marca) -> ps.setString(1, marca));
        } catch (Exception e) {
            LOG.warn("[MarcaSeeder] Error sembrando marca: {}", e.getMessage());
        }
    }
}
