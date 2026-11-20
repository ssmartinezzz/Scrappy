package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;

/** V131 (SQL) and HerramientaSubcategoryResolver (Java) write the same sub_categoria over real Herramienta Manual names plus edge cases. */
@DisplayName("V131 — el SQL y el resolver Java coinciden")
class V131HerramientaAgreementTest extends AbstractSubcategoryBackfillTest {

    @Override
    protected String version() {
        return "V131";
    }

    @Override
    protected String migrationPath() {
        return "/db/migration/V131__subcategoria_herramienta_manual.sql";
    }

    @Override
    protected String fixturePath() {
        return "/v131/nombres-herramienta-manual.tsv";
    }

    @Override
    protected Muestra muestra() {
        return new Muestra("Martillo Carpintero Mango De Fibra 500 Gr", "Herramienta Manual", "Martillo y Maza");
    }

    @Override
    protected String categoriaAjena() {
        return "Otros";
    }
}
