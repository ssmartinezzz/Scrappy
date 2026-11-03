package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The short gender markers (w, men, male, dama) are words, never fragments of another word. */
class GenderResolverWholeWordTest {

    private final GenderResolver resolver = new GenderResolver();

    @Test
    void wInsideAWordIsNotWomen() {
        // neW Era, sNOW, WilloW, harloW: 167 active garments read as mujer (measured 2026-11-02).
        assertThat(resolver.resolver("", "Remera de compresión New Era", "Remera")).isEmpty();
        assertThat(resolver.resolver("", "REMERA SNOW BOXY BASIC", "Remera")).isEmpty();
        assertThat(resolver.resolver("", "Zapatillas New Balance 410 v8", "Zapatilla")).isEmpty();
        assertThat(resolver.resolver("", "Router Tp-Link Archer AX55 WIFI 6", "Router", "tecnologia")).isEmpty();
    }

    @Test
    void aStandaloneWIsStillWomen() {
        assertThat(resolver.resolver("", "Zapatillas Nike Air Max 90 W", "Zapatilla")).isEqualTo("mujer");
        assertThat(resolver.resolver("", "Calza Nike One (W) Negra", "Remera")).isEqualTo("mujer");
        assertThat(resolver.resolver("", "Zapatillas Nike Wmns Dunk Low", "Zapatilla")).isEqualTo("mujer");
    }

    @Test
    void menMaleAndDamaInsideAWordAreNotAGender() {
        assertThat(resolver.resolver("", "Camiseta Mitre Gimnasia y Esgrima de Mendoza Titular", "Remera")).isEmpty();
        assertThat(resolver.resolver("", "Mouse Logitech G305 Lightspeed Menta", "Mouse", "tecnologia")).isEmpty();
        assertThat(resolver.resolver("", "Buzo Damasco Oversize", "Buzo")).isEmpty();
    }

    @Test
    void femaleIsWomenNotMale() {
        assertThat(resolver.resolver("Female", "Remera Básica", "Remera")).isEqualTo("mujer");
    }

    @Test
    void wholeWordMarkersStillCount() {
        assertThat(resolver.resolver("", "Remera Nike Men's Dri-FIT", "Remera")).isEqualTo("hombre");
        assertThat(resolver.resolver("Male", "Remera Básica", "Remera")).isEqualTo("hombre");
        assertThat(resolver.resolver("", "Sweater Dama Tejido", "Buzo")).isEqualTo("mujer");
    }
}
