package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;

/** V128 (SQL) and ElectroSubcategoryResolver (Java) write the same sub_categoria over 601 real names plus the edge cases of the rule. */
@DisplayName("V128 — el SQL y el resolver Java coinciden")
class V128ElectroTvAudioAgreementTest extends AbstractSubcategoryBackfillTest {

    @Override
    protected String version() {
        return "V128";
    }

    @Override
    protected String migrationPath() {
        return "/db/migration/V128__subcategoria_electro_tv_audio.sql";
    }

    @Override
    protected String fixturePath() {
        return "/v128/nombres-electro-tv-audio.tsv";
    }

    @Override
    protected Muestra muestra() {
        return new Muestra("Cafetera Oster Primalatte", "Pequeño Electrodoméstico", "Cafetera");
    }

    @Override
    protected String categoriaAjena() {
        return "Otros";
    }
}
