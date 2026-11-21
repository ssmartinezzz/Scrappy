package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;

/** V132 (SQL) and BicicletaSubcategoryResolver (Java) write the same sub_categoria over real Bicicleta names plus edge cases. */
@DisplayName("V132 — el SQL y el resolver Java coinciden")
class V132BicicletaAgreementTest extends AbstractSubcategoryBackfillTest {

    @Override
    protected String version() {
        return "V132";
    }

    @Override
    protected String migrationPath() {
        return "/db/migration/V132__subcategoria_bicicleta.sql";
    }

    @Override
    protected String fixturePath() {
        return "/v132/nombres-bicicleta.tsv";
    }

    @Override
    protected Muestra muestra() {
        return new Muestra("Bicicleta Mtb Overtech Fortis R29 Aluminio Shimano", "Bicicleta", "Mountain Bike");
    }

    @Override
    protected String categoriaAjena() {
        return "Otros";
    }
}
