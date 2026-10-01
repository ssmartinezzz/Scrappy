package ar.scraper.classification;

import java.util.Set;
import org.apache.commons.lang3.StringUtils;

public final class CategoryGroups {

    private CategoryGroups() {}

    private static final Set<String> INDUMENTARIA_O_CALZADO_EXTRA = Set.of(
        "Puffer","Campera","Sweater","Buzo","Musculosa","Camisa","Remera",
        "Chomba","Casaca","Chaleco","Saco","Traje","Piloto",
        "Calza","Baggy","Jean","Jogging","Short","Bermuda","Pollera",
        "Vestido","Enterito","Pantalón",
        "Calzoncillos","Corpino","Malla",
        "Mochila","Bolso","Riñonera","Billetera","Cinturón",
        "Bufanda","Guantes","Gorro","Gorra","Lentes","Medias",
        "Accesorio Deportivo"
    );

    private static final Set<String> CATEGORIAS_SUPLEMENTO = Set.of(
        "Suplemento","Alimentos","Creatina","Proteína","Colágeno",
        "Magnesio","Pre-Workout","BCAA","Vitaminas","Quemadores","Gainer",
        "Barra Proteica","Pancake Proteico","Snack Proteico",
        // "Proteína" quedó como el bucket de whey/concentrado: el aislado y el vegetal salieron a
        // categoría propia porque son ejes de compra, no matices de etiqueta.
        "Proteína Isolada","Proteína Vegetal"
    );

    /**
     * {@code esCalzado} is a predicate over {@code CategoryClassifier}'s actual {@code return}
     * literals, which are not otherwise centralized as a list.
     */
    private static final Set<String> CATEGORIAS_CALZADO = Set.of(
        "Zapatilla Running","Zapatilla Entrenamiento","Zapatilla Skate",
        "Zapatilla Urbana","Zapatilla","Sneaker",
        "Botines","Borcego","Botas","Ojotas","Sandalia","Mocasin","Zapato","Pantufla"
    );

    /**
     * El criterio de alta fue el mismo de siempre: ≥20 productos reales, sustantivo propio, y
     * ninguna categoría existente donde entren sin mentir.
     */
    private static final Set<String> CATEGORIAS_TECH = Set.of(
        "Notebook","PC","Monitor","GPU","CPU","RAM","Gabinete","Teclado","Mouse","Auricular","Webcam",
        // Almacenamiento faltaba en el canon y sin embargo la base tiene 57 productos ahí (HDDs y
        // SSDs de Fullh4rd): era una categoría REAL que el vocabulario no reconocía, no basura de
        // breadcrumb.
        "Almacenamiento",
        "Cooler","Fuente","Motherboard","Red","Cable","Impresión","Mousepad",
        "Joystick","Micrófono","UPS","Tablet","Cámara","Reloj",
        "Mini PC"
    );

    /** Aparte de {@code INDUMENTARIA_O_CALZADO_EXTRA} a propósito: una pelota no es ropa. */
    private static final Set<String> CATEGORIAS_DEPORTE = Set.of(
        "Pelota","Paleta"
    );

    private static final Set<String> CATEGORIAS_OFICINA = Set.of(
        "Silla", "Escritorio", "Soporte Monitor", "Soporte Laptop",
        "Iluminación", "Mat Escritorio", "Organización"
    );

    /** Standalone canonical categories not covered by the sets above. */
    private static final Set<String> CATEGORIAS_OTRAS = Set.of("Conjunto","Perfume","Otros");

    /**
     * Single source of truth injected into the tool schema {@code enum} and the system prompt so
     * the model cannot invent a nonexistent category.
     */
    public static Set<String> canonicalCategories() {
        Set<String> all = new java.util.HashSet<>();
        all.addAll(INDUMENTARIA_O_CALZADO_EXTRA);
        all.addAll(CATEGORIAS_SUPLEMENTO);
        all.addAll(CATEGORIAS_CALZADO);
        all.addAll(CATEGORIAS_TECH);
        all.addAll(CATEGORIAS_DEPORTE);
        all.addAll(CATEGORIAS_OFICINA);
        all.addAll(CATEGORIAS_OTRAS);
        return java.util.Collections.unmodifiableSet(all);
    }

    public static boolean esCalzado(String cat) {
        if (cat == null) return false;
        return cat.startsWith("Zapatilla") || cat.equals("Botines") || cat.equals("Borcego")
            || cat.equals("Botas") || cat.equals("Ojotas") || cat.equals("Sneaker")
            || cat.equals("Sandalia") || cat.equals("Mocasin") || cat.equals("Zapato")
            || cat.equals("Pantufla");
    }

    public static boolean esIndumentariaOCalzado(String cat) {
        if (StringUtils.isBlank(cat)) return false;
        return esCalzado(cat) || INDUMENTARIA_O_CALZADO_EXTRA.contains(cat);
    }

    public static boolean esCategoriaSuplemento(String cat) {
        return cat != null && CATEGORIAS_SUPLEMENTO.contains(cat);
    }

    /**
     * {@link RubroResolver} resuelve {@code oficina} por {@code sitio.rubro_forzado}, nunca por la
     * categoría.
     */
    public static Set<String> categoriasOficina() {
        return CATEGORIAS_OFICINA;
    }
}
