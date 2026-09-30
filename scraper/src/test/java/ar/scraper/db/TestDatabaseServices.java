package ar.scraper.db;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;

import javax.sql.DataSource;

/**
 * Builds a {@link DatabaseService} over the real repositories for tests that hand-wire it. Lives
 * in {@code ar.scraper.db} because the repositories are package-private. Each call gets its own
 * {@link SiteRegistry}; production wires one shared bean.
 */
public final class TestDatabaseServices {

    private TestDatabaseServices() {}

    public static DatabaseService create(DataSource dataSource) {
        SiteRegistry siteRegistry = new SiteRegistry(new JdbcSiteSource(dataSource));
        RubroResolver rubroResolver = new RubroResolver(siteRegistry);
        return new DatabaseService(dataSource, siteRegistry, rubroResolver,
                new CronRepository(dataSource),
                new FavoritosRepository(dataSource), new PresetRepository(dataSource),
                new HistorialRepository(dataSource),
                new CatalogQueryRepository(dataSource, siteRegistry),
                new ProductRepository(dataSource, siteRegistry, rubroResolver),
                new CategoriaStatsRepository(dataSource), new MlOutputRepository(dataSource),
                new ScrapeRunRepository(dataSource),
                new SitiosRepository(dataSource, siteRegistry),
                new FeedbackRepository(dataSource), new SavedOutfitsRepository(dataSource),
                new SavedPcsRepository(dataSource),
                new PreferenciaArmadorRepository(dataSource),
                new PreciosExternosRepository(dataSource));
    }
}
