package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kids name signals that carry no size label. Names are real dev rows (2026-11-10). */
@Epic("Normalization")
@Feature("Gender")
@DisplayName("GenderResolver — kids name signals: juvenil, escolar, Footy and a narrow character list")
class GenderResolverKidsNameSignalsTest {

    private final GenderResolver resolver = new GenderResolver();

    private String ropa(String raw, String nombre, String categoria) {
        return resolver.resolver(raw, nombre, categoria, "indumentaria");
    }

    @Test
    @DisplayName("juvenil, juveniles and escolar are kids' words in clothing")
    void juvenilYEscolar() {
        assertThat(ropa("", "Medias Ushuaia Juvenil", "Medias")).isEqualTo("infantil");
        assertThat(ropa("unisex", "Canilleras Ushuaia Evo Juveniles", "Otros")).isEqualTo("infantil");
        assertThat(ropa("", "Camiseta Puma Cai Home Juvenil", "Remera")).isEqualTo("infantil");
        assertThat(ropa("", "Zapatillas Escolar Harvard", "Zapatilla")).isEqualTo("infantil");
    }

    @Test
    @DisplayName("the kids brand Footy is infantil whatever the rubro or the site tag says")
    void footy() {
        assertThat(ropa("", "Zapatillas Footy Spiderman", "Zapatilla")).isEqualTo("infantil");
        assertThat(ropa("mujer", "Calzado Zapatillas Paw Patrol Con Luces Footy", "Calza")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Tupper Plegable Footy Unicornio", "Otros", "hogar")).isEqualTo("infantil");
        assertThat(resolver.resolver("", "Mochila Footy Carro 18\" Next Champ", "Mochila", "deporte")).isEqualTo("infantil");
    }

    @Test
    @DisplayName("licensed characters that only ever sell to kids")
    void personajes() {
        assertThat(ropa("", "Sandalias Crocs Disney Minnie Mouse", "Ojotas")).isEqualTo("infantil");
        assertThat(ropa("", "Mochila LSyD Disney Mickey 16\"", "Mochila")).isEqualTo("infantil");
        assertThat(ropa("", "Sandalias Crocs Paw Patrol Off Court", "Ojotas")).isEqualTo("infantil");
        assertThat(ropa("", "Sandalias Crocs Peppa Pig", "Ojotas")).isEqualTo("infantil");
        assertThat(ropa("", "Ojotas Crocs Frozen Elsa Classic Clog", "Ojotas")).isEqualTo("infantil");
        assertThat(ropa("", "Zapatillas Toy Story", "Zapatilla")).isEqualTo("infantil");
    }

    @Test
    @DisplayName("adult licensed items stay untouched: Marvel, Disney collabs, Hello Kitty, Dr Martens Stitch")
    void adultosNoSeTocan() {
        assertThat(ropa("hombre", "Ojotas adidas de Ducha Marvel Spider-Man Adilette Unisex", "Ojotas")).isEqualTo("hombre");
        assertThat(ropa("", "Zapatillas adidas originals Disney Campus 00 S", "Zapatilla Urbana")).isEmpty();
        assertThat(ropa("", "Hello Kitty & Friends 5 Pair Ankle Socks", "Medias")).isEmpty();
        assertThat(ropa("unisex", "Bota Dr Martens Chelsea 2976 Yellow Stitch Smooth", "Borcego")).isEqualTo("unisex");
        assertThat(ropa("", "Remera Tributo Naruto Uzumaki", "Remera")).isEmpty();
    }

    @Test
    @DisplayName("outside clothing the words are not kids' signals, except the Footy brand")
    void fueraDeRopa() {
        assertThat(resolver.resolver("", "MARVEL'S SPIDER-MAN 2 - PS5", "Juego", "tecnologia")).isEmpty();
        assertThat(resolver.resolver("", "Libreta Escolar A4 Rayada", "Cuaderno", "oficina")).isEmpty();
        assertThat(resolver.resolver("", "Peppa Pig Juguete Para Mascotas", "Otros", "hogar")).isEmpty();
    }

    @Test
    @DisplayName("whole words only: lookalikes do not match")
    void palabrasEnteras() {
        assertThat(ropa("", "Remera Footyball Club", "Remera")).isEmpty();
        assertThat(ropa("", "Buzo Minnesota Wild", "Buzo")).isEmpty();
    }
}
