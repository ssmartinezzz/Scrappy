package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GenderResolverKidsSuffixTest {

    private final GenderResolver resolver = new GenderResolver();

    @ParameterizedTest
    @ValueSource(strings = {"jr", "junior", "kid", "kids", "youth", "ps", "gs", "td", "toddler"})
    void sizeSuffixTokenIsInfantil(String token) {
        assertThat(resolver.resolver("", "Zapatilla Nike Air Max 90 " + token.toUpperCase(), "Zapatilla"))
                .isEqualTo("infantil");
        assertThat(resolver.resolver("hombre", token + " Remera adidas", "Remera")).isEqualTo("infantil");
    }

    @Test
    void punctuationStillSeparatesTheToken() {
        assertThat(resolver.resolver("", "Antiparras adidas Ripstream Starter Jr.", "Otros")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Campera (Youth), Negra", "Campera")).isEqualTo("infantil");
    }

    @Test
    void juniorsTheClubIsNotAKidsToken() {
        assertThat(resolver.resolver("", "Camiseta adidas Titular Boca Juniors 26/27", "Remera")).isNotEqualTo("infantil");
        assertThat(resolver.resolver("", "Camiseta Argentinos Juniors Local", "Remera")).isNotEqualTo("infantil");
    }

    @Test
    void tokensInsideLongerWordsDoNotMatch() {
        assertThat(resolver.resolver("", "Remera Pspace Gstar Tdi Kidz", "Remera")).isNotEqualTo("infantil");
        assertThat(resolver.resolver("", "Remera Lisa", "Remera")).isNotEqualTo("infantil");
    }

    @Test
    void sizeTokensOnlyCountOnClothing() {
        // "300 GS" is grams and "PC/PS" is PlayStation: the same tokens outside clothing are not sizes.
        assertThat(resolver.resolver("", "Isopure Zero Carb Protein 450 GS", "Suplemento", "suplementos")).isEmpty();
        assertThat(resolver.resolver("", "Auriculares Corsair HS80 PC/PS/MAC", "Auriculares", "tecnologia")).isEmpty();
        assertThat(resolver.resolver("", "Zapatillas Puma R78 V Ps", "Zapatilla", "indumentaria")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Zapatillas Puma R78 V Ps", "Zapatilla", "")).isEqualTo("infantil");
    }

    @Test
    void kidsWordsInsideLongerWordsAreNotKids() {
        // teaNINA, argiNINA, alaNINA, femeNINO, BEBEr: the kids word is a fragment, not a word.
        assertThat(resolver.resolver("", "L-teanina Cafeína Vitamina B6 Brain Focus", "Suplemento", "suplementos")).isEmpty();
        assertThat(resolver.resolver("", "STAR NUTRITION L-ARGININA GH 150 GRS", "Suplemento", "suplementos")).isEmpty();
        assertThat(resolver.resolver("", "RAW Beta Alanina en polvo 312g", "Suplemento", "suplementos")).isEmpty();
        assertThat(resolver.resolver("", "Proteína lista para beber 330ml", "Suplemento", "suplementos")).isEmpty();
        assertThat(resolver.resolver("", "Pollera Fila Corta Femenino Tennis Game Basic", "Pollera", "indumentaria"))
                .isEqualTo("mujer");
    }

    @Test
    void kidsWordsAsWholeWordsStillCount() {
        assertThat(resolver.resolver("", "Remera NIÑA Estampada", "Remera")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Multivitamínico para niños", "Suplemento", "suplementos")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Body bebés algodón", "Body")).isEqualTo("infantil");
        assertThat(resolver.resolver("Infantiles", "Campera Puffer", "Campera")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Zapatillas Kids Velcro", "Zapatilla")).isEqualTo("infantil");
    }

    @Test
    void existingKidsWordsStillCount() {
        assertThat(resolver.resolver("", "Remera Niños", "Remera")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Body bebé", "Body")).isEqualTo("infantil");
    }
}
