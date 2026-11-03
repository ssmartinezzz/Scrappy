package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SportResolver — sport-specific merch detection")
class SportResolverTest {

    private final SportResolver resolver = new SportResolver();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Camiseta adidas Titular Boca Juniors 26/27 de Hombre|futbol",
            "Short adidas River Plate Descanso 25/26|futbol",
            "Camiseta Seleccion Argentina Titular|futbol",
            "Camiseta Real Madrid Alternativa|futbol",
            "Camiseta Barcelona FC 24/25|futbol",
            "Guantes de Arquero Nike|futbol",
            "Antiparras adidas Ripstream Starter Jr|natacion",
            "Malla de Natación Speedo Endurance|natacion",
            "Palo de hockey Kookaburra|hockey",
            "Pantalón de Rugby Gilbert|rugby",
            "Raqueta de Pádel Bullpadel|tenis",
            "Pelota de tenis Wilson|tenis",
            "Short de Básquet NBA Lakers|basquet",
            "Rodillera de Vóley Mizuno|voley",
            "Maillot Ciclismo Manga Corta|ciclismo",
            "Remera Golf Titleist|golf",
            "Guantes de Boxeo Everlast 12oz|combate",
            "Kimono Judo Adulto|combate",
            "Short MMA Venum|combate",
    })
    void detectsTheSport(String nombre, String deporte) {
        assertThat(resolver.resolver(nombre, "Remera", "indumentaria")).isEqualTo(deporte);
    }

    @Test
    @DisplayName("Botines is football whatever the name says")
    void botinesIsFutbol() {
        assertThat(resolver.resolver("Modelo Predator Elite", "Botines", "indumentaria")).isEqualTo("futbol");
    }

    @Test
    @DisplayName("A club needs its full name: a river, a colour or a brand is not a sport")
    void abstainsOnLookalikes() {
        assertThat(resolver.resolver("Remera Timberland Kennebec River Tree", "Remera", "indumentaria")).isEmpty();
        assertThat(resolver.resolver("Buzo color arena", "Buzo", "indumentaria")).isEmpty();
        assertThat(resolver.resolver("Zapatillas Puma Suede", "Zapatilla", "indumentaria")).isEmpty();
        assertThat(resolver.resolver("Remera lisa", "Remera", "indumentaria")).isEmpty();
        assertThat(resolver.resolver("Boxer Calvin Klein algodón", "Boxer", "indumentaria")).isEmpty();
    }

    @Test
    @DisplayName("Punctuation next to a keyword does not hide it, and accents are ignored")
    void punctuationAndAccents() {
        assertThat(resolver.resolver("Camiseta (Boca Juniors), 2026", "Remera", "indumentaria")).isEqualTo("futbol");
        assertThat(resolver.resolver("GORRO DE NATACIÓN", "Gorra", "indumentaria")).isEqualTo("natacion");
    }

    @Test
    @DisplayName("A keyword inside a longer word does not match")
    void wordBoundary() {
        assertThat(resolver.resolver("Remera golfista urbana", "Remera", "indumentaria")).isEmpty();
    }

    @Test
    @DisplayName("Only indumentaria: other rubros abstain, blank rubro counts as indumentaria")
    void onlyIndumentaria() {
        assertThat(resolver.resolver("Pelota de futbol", "Otros", "tecnologia")).isEmpty();
        assertThat(resolver.resolver("Modelo Predator", "Botines", "suplementos")).isEmpty();
        assertThat(resolver.resolver("Camiseta Boca Juniors", "Remera", "")).isEqualTo("futbol");
        assertThat(resolver.resolver("Camiseta Boca Juniors", "Remera", null)).isEqualTo("futbol");
    }

    @Test
    @DisplayName("When two sports match, the first in SportTaxonomy order wins")
    void precedenceFollowsTaxonomyOrder() {
        assertThat(resolver.resolver("Remera de rugby Boca Juniors", "Remera", "indumentaria")).isEqualTo("futbol");
        assertThat(resolver.resolver("Gorra de golf y tenis", "Gorra", "indumentaria")).isEqualTo("tenis");
    }

    @Test
    @DisplayName("Null and blank names abstain")
    void nullSafe() {
        assertThat(resolver.resolver(null, null, null)).isEmpty();
        assertThat(resolver.resolver("  ", "", "")).isEmpty();
    }
}
