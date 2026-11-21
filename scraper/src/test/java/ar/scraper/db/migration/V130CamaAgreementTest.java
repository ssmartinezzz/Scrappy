package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;

/** V130 (SQL) and CamaSubcategoryResolver (Java) write the same sub_categoria over real Ropa de Cama and Colchón names plus edge cases. */
@DisplayName("V130 — el SQL y el resolver Java coinciden")
class V130CamaAgreementTest extends AbstractSubcategoryBackfillTest {

    @Override
    protected String version() {
        return "V130";
    }

    @Override
    protected String migrationPath() {
        return "/db/migration/V130__subcategoria_ropa_de_cama_colchon.sql";
    }

    @Override
    protected String fixturePath() {
        return "/v130/nombres-ropa-de-cama-colchon.tsv";
    }

    @Override
    protected Muestra muestra() {
        return new Muestra("Colchón de Espuma Inducol Limay 1 Plaza 80 x 190 cm", "Colchón", "1 Plaza");
    }

    @Override
    protected String categoriaAjena() {
        return "Otros";
    }
}
