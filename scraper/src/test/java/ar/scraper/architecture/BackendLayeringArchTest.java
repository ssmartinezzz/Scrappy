package ar.scraper.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

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
    static final ArchRule areasSonSumideros = noClasses()
        .that().resideInAnyPackage("ar.scraper.catalog..", "ar.scraper.classification..",
                                   "ar.scraper.scrape..", "ar.scraper.scheduling..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("ar.scraper.db..", "ar.scraper.cron..",
                            "ar.scraper.aggregator..", "ar.scraper.web..",
                            "ar.scraper.ml..", "ar.scraper.agent..",
                            "ar.scraper.security..", "ar.scraper.config..",
                            "ar.scraper.scrapers..", "ar.scraper.pages..",
                            "ar.scraper.health..", "ar.scraper.identity..");
}
