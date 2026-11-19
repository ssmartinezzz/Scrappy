package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real names from the Oncity scrape (2026-11-19) and Las Pepas / dev-catalogue fragrances; the vetoes are the dev-catalogue and Oncity leaks. */
@DisplayName("Belleza — Maquillaje, Cuidado de la Piel, Cuidado del Cabello")
class BellezaClassifierTest {

    private final CategoryClassifier clasificador = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Tinte Labial Mate Soft Haze Lip Blur|Maquillaje",
            "Labial Líquido Lip Vinyl Tint - Ruby Kisses Roasted Peach|Maquillaje",
            "Mascara de Pestañas Wonder Bond Lash Filler Rimmel 001 Black|Maquillaje",
            "Delineador De Ojos Mia Colors Negro|Maquillaje",
            "Iluminador Soft Touch Bouncy Glow|Maquillaje",
            "Base de Maquillaje en Barra|Maquillaje",
            "Esmalte de Uñas Mia Colors Black|Maquillaje",
            "Uñas Postizas Bare But Better|Maquillaje",
            "Set de Maquillaje Harry Potter Full Collection Set|Maquillaje",
            "Crema Contorno de Ojos Ácido Hialurónico - Eye Cream Anti-Wrinkle Moisturizing|Cuidado de la Piel",
            "Loción Corporal Nivea Coco & Aceite de Monoi|Cuidado de la Piel",
            "Protector Solar Facial En Crema Compacta Tono 2 FPS 50|Cuidado de la Piel",
            "Agua Micelar Nivea Regeneradora Enriquecida Con Sérum Piel Sensible 400 Ml|Cuidado de la Piel",
            "Set de Balsamo Labial Burts Bees In Full Bloom Paquete de 4 Unidades|Cuidado de la Piel",
            "Tintura Nat Pro 7/77 Rubio Mate 60 Ml|Cuidado del Cabello",
            "Nat Pro Amazing Hair Shampoo 500Ml|Cuidado del Cabello",
            "Acondicionador Fructis Hair Food Aloe 300 Ml|Cuidado del Cabello",
            "Acondicionador Sedal Colágeno Y Vitamina C 340 Ml|Cuidado del Cabello",
            "Crema Para Peinar Fructis Goodbye Daño 250 Ml|Cuidado del Cabello",
            "Cepillo Tangle Teezer Easy Dry & Go Jet Black|Cuidado del Cabello",
            "Shampoo, 250 ml|Cuidado del Cabello"
    })
    void classifiesBeautyNames(String nombre, String esperada) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} is not beauty")
    @CsvSource(delimiter = '|', value = {
            "Shampoo para Auto Siliconado Manzana 600 Cc Silisur",
            "Shampoo 500 Ml Aloe Vera Pet's Class",
            "Dermosedan Shampoo para Perros Avena y Aloe 200g",
            "Acondicionador De Suelo Chips Decorativo 10 Lts Roots",
            "Acondicionador para Pisos Lavanda 5 Lts Suiza",
            "Aire Acondicionado Split Frio Calor 3000 Frigorias",
            "Esmalte Sintético Negro Satinado 1 Lt Interior/Exterior Venier",
            "Tintura para Madera Nogal 1 Lt",
            "Tintura Textil Rit Azul",
            "Dulce de Leche Repostero 400 g",
            "Crema de Leche 200 ml",
            "Crema Pastelera Preparada 500 g",
            "Pincel N°10 Esmalte Next 2,5 Cm El Galgo",
            "Kit de Seguridad Protector Facial con Malla y Auditivo Negro Libus",
            "Iluminador Led Regulable Gadnic Panel para Fotografía",
            "Dispenser Para Jabon Liquido Shampoo Ducha Baño De 450 Ml",
            "Corrector de Postura Ajustable",
            "Mi Primer Set De Maquillaje Chico",
            "Set De Maquillajes Cup Cake",
            "Valijita De Maquillaje Tiny Unicornio",
            "Bolso de Maquillajes Poppi",
            "Organizador Maquillaje 22X13X8 Cm Polipropileno Transparente Nitron",
            "Sanrio Characters - Lip Stick Candy (CARAMELO EN FORMA DE LABIAL)",
            "Mochila Footy Carro 18 C/Luz Make Up"
    })
    void aLeakyWordDoesNotMakeBeauty(String nombre) {
        assertThat(clasificador.normalizarCategoria("", nombre))
                .isNotIn("Maquillaje", "Cuidado de la Piel", "Cuidado del Cabello");
    }

    @ParameterizedTest(name = "{0} stays {1}")
    @CsvSource(delimiter = '|', value = {
            "Perfume Marc Jacobs Perfect Intense EDP 100ml|Perfume",
            "Antitranspirante Dove Serum Cream Reparacion Diaria 50gr|Desodorante",
            "Desodorante Woman Midnight|Desodorante",
            "Combo Tascani Distinto Perfume 100ml + Desodorante 150ml|Desodorante"
    })
    void fragranceAndDeodorantAreNotClaimed(String nombre, String esperada) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("a curated leaf outside belleza beats a beauty name, and beats a Perfume name too")
    void aLeafOutsideBellezaWins() {
        assertThat(clasificador.normalizarCategoria(null, "Shampoo Rosa pH Neutro 470 Ml 3D", "Limpieza de Auto"))
                .isEqualTo("Limpieza de Auto");
        assertThat(clasificador.normalizarCategoria(null, "Lego Creator Frasco Decorativo de Perfume Floral 3en1 286 Piezas", "Juguete"))
                .isEqualTo("LEGO");
        assertThat(clasificador.normalizarCategoria(null, "Tintura Nat Pro 7/77 Rubio Mate 60 Ml", "Cuidado del Cabello"))
                .isEqualTo("Cuidado del Cabello");
    }

    @ParameterizedTest(name = "{1}: {0} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "Labial Líquido Lip Vinyl Tint|Maquillaje|Labios",
            "Esmalte de Uñas Mia Colors Black|Maquillaje|Uñas",
            "Mascara de Pestañas Rimmel 001 Black|Maquillaje|Ojos",
            "Base de Maquillaje en Barra|Maquillaje|Rostro",
            "Protector Solar Facial FPS 50|Cuidado de la Piel|Protector Solar",
            "Loción Corporal Nivea Coco|Cuidado de la Piel|Corporal",
            "Crema Contorno de Ojos Eye Cream|Cuidado de la Piel|Facial",
            "Tintura Nat Pro 7/77 Rubio Mate 60 Ml|Cuidado del Cabello|Tintura",
            "Nat Pro Amazing Hair Shampoo 500Ml|Cuidado del Cabello|Shampoo",
            "Acondicionador Fructis Hair Food Aloe 300 Ml|Cuidado del Cabello|Acondicionador",
            "Máscara Capilar Con Aceite Puro De Coco|Cuidado del Cabello|Tratamiento",
            "Perfume Jean Paul Gaultier Scandal Absolu Extrait de Parfum 80ml|Perfume|Extracto",
            "Perfume Marc Jacobs Perfect Intense EDP 100ml|Perfume|Eau de Parfum"
    })
    void splitsBySubcategory(String nombre, String categoria, String sub) {
        assertThat(subs.resolver(nombre, categoria)).isEqualTo(sub);
    }

    @Test
    @DisplayName("beauty categories take no sport sub")
    void noSportSub() {
        assertThat(subs.resolver("Shampoo Running Hockey 250 ml", "Cuidado del Cabello")).isEqualTo("Shampoo");
        assertThat(subs.resolver("Crema Running Futbol", "Cuidado de la Piel")).isEmpty();
    }

    @Test
    void theThreeCategoriesAreBelleza() {
        assertThat(CategoryGroups.canonicalCategories()).contains("Maquillaje", "Cuidado de la Piel", "Cuidado del Cabello");
        for (String cat : new String[]{"Maquillaje", "Cuidado de la Piel", "Cuidado del Cabello"}) {
            assertThat(CategoryGroups.esCategoriaBelleza(cat)).isTrue();
        }
    }
}
