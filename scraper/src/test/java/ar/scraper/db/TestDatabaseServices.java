package ar.scraper.db;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;

import ar.scraper.db.support.TestTransactions;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Builds a {@link DatabaseService} over the real repositories for tests that hand-wire it. Lives
 * in {@code ar.scraper.db} because the repositories are package-private. Repositories with transactional methods are proxied the way Spring does, so the
 * annotation is live. Each call gets its own
 * {@link SiteRegistry}; production wires one shared bean.
 */
public final class TestDatabaseServices {

    private TestDatabaseServices() {}

    public static DatabaseService create(DataSource raw) {
        PlatformTransactionManager tm = TestTransactions.manager(raw);
        DataSource dataSource = TestTransactions.aware(raw);
        SiteRegistry siteRegistry = new SiteRegistry(new JdbcSiteSource(dataSource));
        RubroResolver rubroResolver = new RubroResolver(siteRegistry);
        return new DatabaseService(dataSource, siteRegistry, rubroResolver,
                tx(new CronRepository(dataSource), tm),
                new FavoritosRepository(dataSource), tx(new PresetRepository(dataSource), tm),
                new HistorialRepository(dataSource),
                new CatalogQueryRepository(dataSource, siteRegistry),
                tx(new ProductRepository(dataSource, siteRegistry, rubroResolver), tm),
                tx(new CategoriaStatsRepository(dataSource), tm), tx(new MlOutputRepository(dataSource), tm),
                tx(new ScrapeRunRepository(dataSource), tm),
                tx(new SitiosRepository(dataSource, siteRegistry), tm),
                tx(new FeedbackRepository(dataSource), tm), tx(new SavedOutfitsRepository(dataSource), tm),
                tx(new SavedPcsRepository(dataSource), tm),
                new PreferenciaArmadorRepository(dataSource),
                tx(new PreciosExternosRepository(dataSource), tm));
    }

    private static <T> T tx(T repository, PlatformTransactionManager tm) {
        return TestTransactions.proxy(repository, tm);
    }
}
