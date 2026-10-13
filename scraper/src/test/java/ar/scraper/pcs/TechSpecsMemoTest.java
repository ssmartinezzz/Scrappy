package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContextoDeArmado.specsDe — each product is parsed once per build")
class TechSpecsMemoTest {

    private static final Product CPU = Product.builder().sitio("s").url("u")
            .nombre("Micro AMD Ryzen 7 9700X AM5").categoria("CPU").rubro("tecnologia").build();

    @Test
    @DisplayName("returns what the parser returns")
    void devuelveLoQueParsea() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(contexto.specsDe(CPU)).isEqualTo(TechSpecsParser.parse(CPU.nombre(), CPU.categoria()));
    }

    @Test
    @DisplayName("a second lookup reuses the first parse")
    void reusaElPrimerParseo() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

        assertThat(contexto.specsDe(CPU)).isSameAs(contexto.specsDe(CPU));
    }

    @Test
    @DisplayName("choosing the mother keeps the memo of the same build")
    void conMotherConservaLaMemo() {
        ContextoDeArmado contexto = ContextoDeArmado.inicial(450);
        TechSpecs antes = contexto.specsDe(CPU);

        assertThat(contexto.conMother(TechSpecs.EMPTY).specsDe(CPU)).isSameAs(antes);
    }
}
