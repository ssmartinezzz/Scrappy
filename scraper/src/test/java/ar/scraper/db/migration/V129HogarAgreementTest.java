package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;

/** V129 (SQL) and HogarSubcategoryResolver (Java) write the same sub_categoria over real Iluminación and Mueble names plus edge cases. */
@DisplayName("V129 — el SQL y el resolver Java coinciden")
class V129HogarAgreementTest extends AbstractSubcategoryBackfillTest {

    @Override
    protected String version() {
        return "V129";
    }

    @Override
    protected String migrationPath() {
        return "/db/migration/V129__subcategoria_iluminacion_mueble.sql";
    }

    @Override
    protected String fixturePath() {
        return "/v129/nombres-iluminacion-mueble.tsv";
    }

    @Override
    protected Muestra muestra() {
        return new Muestra("Velador Honguito E27 1 Luz Verde", "Iluminación", "Velador");
    }

    @Override
    protected String categoriaAjena() {
        return "Otros";
    }
}
