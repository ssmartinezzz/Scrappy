package ar.scraper.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// The test tree mirrors ar.scraper.db with ~60 classes importing ar.scraper.aggregator;
// without DoNotIncludeTests, db<->aggregator would survive F1 as a test-only cycle.
@AnalyzeClasses(packages = "ar.scraper", importOptions = ImportOption.DoNotIncludeTests.class)
class BackendLayeringArchTest {

    // Frozen: records today's cycles as the reviewable baseline. Unfrozen rules
    // below are the actual win — a frozen-only green would silently absorb a
    // reintroduced cycle instead of failing the build.
    @ArchTest
    static final ArchRule cicloBaseline = FreezingArchRule.freeze(
        SlicesRuleDefinition.slices().matching("ar.scraper.(*)..").should().beFreeOfCycles());

    @ArchTest
    static final ArchRule dbNoDependeDeCron = noClasses()
        .that().resideInAPackage("ar.scraper.db..")
        .should().dependOnClassesThat().resideInAnyPackage("ar.scraper.cron..");

    @ArchTest
    static final ArchRule dbNoDependeDeAggregator = noClasses()
        .that().resideInAPackage("ar.scraper.db..")
        .should().dependOnClassesThat().resideInAnyPackage("ar.scraper.aggregator..");

    @ArchTest
    static final ArchRule areasSonSumideros = noClasses()
        .that().resideInAnyPackage("ar.scraper.catalog..", "ar.scraper.classification..",
                                   "ar.scraper.scrape..", "ar.scraper.scheduling..",
                                   "ar.scraper.favoritos..", "ar.scraper.financiacion..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("ar.scraper.db..", "ar.scraper.cron..",
                            "ar.scraper.aggregator..", "ar.scraper.web..",
                            "ar.scraper.ml..", "ar.scraper.agent..",
                            "ar.scraper.security..", "ar.scraper.config..",
                            "ar.scraper.scrapers..", "ar.scraper.pages..",
                            "ar.scraper.health..", "ar.scraper.identity..");

    @ArchTest
    static final ArchRule cronNoDependeDeDb = noClasses()
        .that().resideInAPackage("ar.scraper.cron..")
        .should().dependOnClassesThat().resideInAnyPackage("ar.scraper.db..");

    // The favoritos aggregate's 4 methods on DatabaseService (extract-favoritos-port).
    private static final Set<String> METODOS_FAVORITOS =
        Set.of("guardarFavorito", "eliminarFavorito", "listarFavoritos", "tocarFavorito");

    @ArchTest
    static final ArchRule webUsaFavoritosPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a favoritos method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_FAVORITOS.contains(call.getTarget().getName());
            }
        });

    // The presets aggregate's 6 methods on DatabaseService (extract-preset-historial-ports).
    private static final Set<String> METODOS_PRESETS =
        Set.of("listarPresets", "cargarPresetActivo", "crearPreset",
               "editarPreset", "activarPreset", "eliminarPreset");

    @ArchTest
    static final ArchRule webUsaPresetsPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a preset method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_PRESETS.contains(call.getTarget().getName());
            }
        });

    // The historial aggregate's 2 read methods on DatabaseService (extract-preset-historial-ports).
    private static final Set<String> METODOS_HISTORIAL =
        Set.of("getHistorialPrecios", "cargarHistorial");

    @ArchTest
    static final ArchRule webUsaHistorialPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a historial method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_HISTORIAL.contains(call.getTarget().getName());
            }
        });

    // The catalog-search aggregate's 3 methods (6 overloads) on DatabaseService
    // (extract-catalog-query-port).
    private static final Set<String> METODOS_CATALOG_QUERY =
        Set.of("buscarCatalogo", "facetasCatalogo", "resumenCatalogo");

    @ArchTest
    static final ArchRule webUsaCatalogQueryPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a catalog-query method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_CATALOG_QUERY.contains(call.getTarget().getName());
            }
        });

    // The product aggregate's 14 methods on DatabaseService (extract-catalog-query-port).
    private static final Set<String> METODOS_PRODUCTOS =
        Set.of("obtenerProducto", "obtenerProductoPorKey", "cargarProductos",
               "cargarClasificacionBloqueada", "estaBloqueado", "esProductoActivo",
               "contarEmbeddings", "upsertProductos", "upsertParcial", "actualizarCategoria",
               "actualizarNormalizacion", "marcarDescontinuado", "limpiarProductos",
               "aplicarReclasificacionAuditada");

    @ArchTest
    static final ArchRule webUsaProductoPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a product method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_PRODUCTOS.contains(call.getTarget().getName());
            }
        });
}
