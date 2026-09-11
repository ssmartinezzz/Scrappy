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
                                   "ar.scraper.favoritos..", "ar.scraper.financiacion..",
                                   "ar.scraper.feedback..", "ar.scraper.outfits..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("ar.scraper.db..", "ar.scraper.cron..",
                            "ar.scraper.aggregator..", "ar.scraper.web..",
                            "ar.scraper.ml..", "ar.scraper.agent..",
                            "ar.scraper.security..", "ar.scraper.config..",
                            "ar.scraper.scrapers..", "ar.scraper.pages..",
                            "ar.scraper.health..", "ar.scraper.identity..");

    // ── close-backend-package-cycles (F3a) ──────────────────────────────────
    // Las unicas aristas que entran a `web` desde adentro del backend son tres:
    // `ml` -> InflacionService, `agent` -> ScraperService y `cron` -> ScraperService.
    // `aggregator`, `scrapers`, `pages`, `health` y `security` no nombran una sola
    // clase de `web`. Por eso los 7 ciclos congelados mueren con estas tres reglas
    // en verde, y `cicloBaseline` deja de necesitar el store.

    @ArchTest
    static final ArchRule mlNoDependeDeWeb = noClasses()
        .that().resideInAPackage("ar.scraper.ml..")
        .should().dependOnClassesThat().resideInAnyPackage("ar.scraper.web..");

    @ArchTest
    static final ArchRule agentNoDependeDeWeb = noClasses()
        .that().resideInAPackage("ar.scraper.agent..")
        .should().dependOnClassesThat().resideInAnyPackage("ar.scraper.web..");

    // `cron/` se absorbe en `scheduling/`, que es el nombre final del area. Una
    // vez vacio el paquete, quien impide que el runner vuelva a nombrar `web`,
    // `ml` o `config` desde su casa nueva es `areasSonSumideros`, no una regla
    // propia: por eso esta solo afirma que el paquete dejo de existir.
    @ArchTest
    static final ArchRule cronFueAbsorbidoEnScheduling = noClasses()
        .should().resideInAPackage("ar.scraper.cron..");

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

    // ── extract-ml-persistence-ports ────────────────────────────────────────
    // Unlike the six rules above, these four are NOT scoped to ar.scraper.web
    // alone: this cluster's consumers also live in ar.scraper.aggregator
    // (ResultAggregator). A web-only rule would have gone green while half the
    // extraction was still calling the facade.
    private static final String[] CONSUMIDORES_DEL_CLUSTER =
        { "ar.scraper.web..", "ar.scraper.aggregator.." };

    // The categoria_stats aggregate's 2 methods on DatabaseService.
    private static final Set<String> METODOS_CATEGORIA_STATS =
        Set.of("guardarCategoriaStats", "cargarCategoriaStats");

    @ArchTest
    static final ArchRule consumidoresUsanCategoriaStatsPorElPuerto = noClasses()
        .that().resideInAnyPackage(CONSUMIDORES_DEL_CLUSTER)
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a categoria-stats method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_CATEGORIA_STATS.contains(call.getTarget().getName());
            }
        });

    // The ml_output aggregate's 3 methods on DatabaseService.
    private static final Set<String> METODOS_ML_OUTPUT =
        Set.of("guardarMlOutput", "cargarMlOutput", "limpiarMlOutput");

    @ArchTest
    static final ArchRule consumidoresUsanMlOutputPorElPuerto = noClasses()
        .that().resideInAnyPackage(CONSUMIDORES_DEL_CLUSTER)
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target an ml-output method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_ML_OUTPUT.contains(call.getTarget().getName());
            }
        });

    // The scrape_run aggregate's 10 methods on DatabaseService.
    private static final Set<String> METODOS_SCRAPE_RUN =
        Set.of("crearScrapeRun", "marcarSitioEnCurso", "marcarSitioTerminado",
               "finalizarScrapeRun", "marcarRunsInterrumpidos", "ultimaCorridaInterrumpida",
               "reabrirScrapeRun", "marcarSitiosAusentesDelRegistro", "startedAtDeRun",
               "existeCorridaCompletada");

    @ArchTest
    static final ArchRule consumidoresUsanScrapeRunPorElPuerto = noClasses()
        .that().resideInAnyPackage(CONSUMIDORES_DEL_CLUSTER)
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a scrape-run method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_SCRAPE_RUN.contains(call.getTarget().getName());
            }
        });

    // The sitio aggregate's 3 methods. NOT siteRegistry(): that accessor returns
    // a Spring @Component, and reading a port or bean off the facade is the
    // route ApiController already uses for every hand-built endpoint
    // (db.productos(), db.presets(), db.favoritos()). Retiring those accessors
    // is F4 work, when the endpoints become beans; banning it here would widen
    // ApiController's constructor without retiring a single repository.
    private static final Set<String> METODOS_SITIOS =
        Set.of("guardarSitio", "eliminarSitio", "cargarSitiosDinamicos");

    @ArchTest
    static final ArchRule consumidoresUsanSitiosPorElPuerto = noClasses()
        .that().resideInAnyPackage(CONSUMIDORES_DEL_CLUSTER)
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a sitio method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_SITIOS.contains(call.getTarget().getName());
            }
        });

    // ─── El ultimo cluster de F2: feedback, outfits guardados y precios
    // externos. Tres agregados sin nada en comun salvo sus consumidores —
    // los tres endpoints que todavia reciben la fachada entera.

    // El agregado outfit_feedback_item + categoria_dismiss. Las dos superficies
    // que lo leen son distintas (outfits y el feed "Para ti") pero la senal es
    // una sola: que le gusto y que descarto el usuario.
    private static final Set<String> METODOS_FEEDBACK =
        Set.of("guardarOutfitFeedbackItem", "obtenerOutfitFeedback", "limpiarOutfitFeedback",
               "guardarCategoriaDismiss", "borrarCategoriaDismiss", "obtenerCategoriaDismiss");

    @ArchTest
    static final ArchRule webUsaFeedbackPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a feedback method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_FEEDBACK.contains(call.getTarget().getName());
            }
        });

    // El agregado saved_outfits + sus items.
    private static final Set<String> METODOS_OUTFITS_GUARDADOS =
        Set.of("guardarOutfit", "obtenerOutfitsGuardados", "eliminarOutfitGuardado",
               "renombrarOutfit");

    @ArchTest
    static final ArchRule webUsaOutfitsGuardadosPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a saved-outfit method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_OUTFITS_GUARDADOS.contains(call.getTarget().getName());
            }
        });

    // El agregado precios_externos. `cargarPreciosExternos` entra a la regla
    // aunque hoy no tenga un solo consumidor fuera de `db`: la regla describe
    // el agregado, no el conteo de llamadas de este commit.
    private static final Set<String> METODOS_PRECIOS_EXTERNOS =
        Set.of("guardarPreciosExternos", "cargarPreciosExternos");

    @ArchTest
    static final ArchRule webUsaPreciosExternosPorElPuerto = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "target a precios-externos method of ar.scraper.db.DatabaseService") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(ar.scraper.db.DatabaseService.class)
                    && METODOS_PRECIOS_EXTERNOS.contains(call.getTarget().getName());
            }
        });
}
