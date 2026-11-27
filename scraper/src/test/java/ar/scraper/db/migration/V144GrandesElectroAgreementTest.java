package ar.scraper.db.migration;

import org.junit.jupiter.api.DisplayName;

/**
 * V144 (SQL) and GrandesElectroSubcategoryResolver (Java) write the same sub_categoria over every distinct name the catalogue held in
 * Lavarropas, Cocina y Horno, Heladera, Calefactor and Termotanque (3,279, 2026-11-26) plus edge cases.
 */
@DisplayName("V144 — el SQL y el resolver Java coinciden en las subs de los electrodomésticos")
class V144GrandesElectroAgreementTest extends AbstractSubcategoryBackfillTest {

    @Override
    protected String version() {
        return "V144";
    }

    @Override
    protected String migrationPath() {
        return "/db/migration/V144__categorias_bebe_salud_libreria.sql";
    }

    @Override
    protected String fixturePath() {
        return "/v144/nombres-electro.tsv";
    }

    @Override
    protected Muestra muestra() {
        return new Muestra("Lavavajillas Whirlpool Inverter 14 Cubiertos Inox", "Lavarropas", "Lavavajillas");
    }

    @Override
    protected String categoriaAjena() {
        return "Otros";
    }
}
