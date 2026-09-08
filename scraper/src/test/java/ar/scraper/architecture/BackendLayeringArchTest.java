package ar.scraper.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

// The test tree mirrors ar.scraper.db with ~60 classes importing ar.scraper.aggregator;
// without DoNotIncludeTests, db<->aggregator would survive F1 as a test-only cycle.
@AnalyzeClasses(packages = "ar.scraper", importOptions = ImportOption.DoNotIncludeTests.class)
class BackendLayeringArchTest {

    @ArchTest
    static final ArchRule cicloBaseline = FreezingArchRule.freeze(
        SlicesRuleDefinition.slices().matching("ar.scraper.(*)..").should().beFreeOfCycles());
}
