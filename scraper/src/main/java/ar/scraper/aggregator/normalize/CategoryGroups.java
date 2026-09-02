package ar.scraper.aggregator.normalize;

import java.util.Set;

/**
 * Canonical-category membership predicates.
 *
 * <p>Extracted from {@code NormalizerService.esCalzado} /
 * {@code esIndumentariaOCalzado} plus the inline supplement-category check
 * from {@code normalizarProducto} (Work Unit 3 of the aggregator SOLID
 * modularization) — pure relocation, no behavior change.</p>
 */
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
        // "Proteína" quedó como el bucket de whey/concentrado: el aislado y el
        // vegetal salieron a categoría propia porque son ejes de compra, no
        // matices de etiqueta. Ver V32.
        "Proteína Isolada","Proteína Vegetal"
    );

    /**
     * Calzado canonical category names (llm-catalog-nlp, design D4/Safeguard
     * A) — mirrors {@link #esCalzado}'s membership check, but as an
     * enumerable set: {@code esCalzado} is a predicate over
     * {@code CategoryClassifier}'s actual {@code return} literals, which are
     * not otherwise centralized as a list.
     */
    private static final Set<String> CATEGORIAS_CALZADO = Set.of(
        "Zapatilla Running","Zapatilla Entrenamiento","Zapatilla Skate",
        "Zapatilla Urbana","Zapatilla","Sneaker",
        "Botines","Borcego","Botas","Ojotas","Sandalia","Mocasin","Zapato","Pantufla"
    );

    /**
     * Tech ("rubro=tecnologia") canonical category names — {@code CategoryClassifier}.
     *
     * <p>Las 13 nuevas de {@code richer-category-taxonomy} no son una taxonomía
     * de hardware imaginada: cada una salió de contar cuántos productos reales
     * caían en {@code Otros} sin tener dónde ir. {@code Otros} tenía 2.974
     * filas —14% del catálogo— y adentro había 453 teclados, 302 mouses, 285
     * fuentes, 231 discos, 161 productos de red y 130 cables. El criterio de
     * alta fue el mismo de siempre: ≥20 productos reales, sustantivo propio, y
     * ninguna categoría existente donde entren sin mentir.</p>
     *
     * <p>{@code Cooler} es la excepción a esa regla y la más cara de todas: no
     * venía de {@code Otros} sino de adentro de {@code CPU}, donde 321 de 646
     * filas eran disipadores. Una categoría con la mitad de sus productos a un
     * orden de magnitud de precio de la otra mitad no le miente a un filtro —
     * le miente al pipeline ML, que vive de esa distribución.</p>
     */
    private static final Set<String> CATEGORIAS_TECH = Set.of(
        "Notebook","PC","Monitor","GPU","CPU","RAM","Gabinete","Teclado","Mouse","Auricular","Webcam",
        // Almacenamiento faltaba en el canon y sin embargo la base tiene 57
        // productos ahí (HDDs y SSDs de Fullh4rd): era una categoría REAL que
        // el vocabulario no reconocía, no basura de breadcrumb.
        "Almacenamiento",
        // richer-category-taxonomy
        "Cooler","Fuente","Motherboard","Red","Cable","Impresión","Mousepad",
        "Joystick","Micrófono","UPS","Tablet","Cámara","Reloj"
    );

    /**
     * Equipamiento deportivo — {@code richer-category-taxonomy}.
     *
     * <p>Aparte de {@code INDUMENTARIA_O_CALZADO_EXTRA} a propósito: una pelota
     * no es ropa. Si entrara ahí, {@code GymratTagger} la taggearía y los tres
     * armadores de outfits la considerarían una prenda vestible.</p>
     */
    private static final Set<String> CATEGORIAS_DEPORTE = Set.of(
        "Pelota","Paleta"
    );

    /**
     * Office ({@code rubro=oficina}) canonical category names —
     * {@code CategoryClassifier}, add-inpro-office-store.
     *
     * <p>Siete y no más: son los tipos de producto que el catálogo real de
     * INPRO tiene (100 productos leídos en vivo el 2026-08-19), no una
     * taxonomía de oficina imaginada. Lo que no cae en una de estas siete
     * —cargadores, valijas, docking stations— cae en {@code Otros}, que es el
     * "no sé" explícito y medible del vocabulario. Inventar una categoría por
     * producto suelto es exactamente lo que {@code close-category-vocabulary}
     * vino a cerrar.</p>
     */
    private static final Set<String> CATEGORIAS_OFICINA = Set.of(
        "Silla", "Escritorio", "Soporte Monitor", "Soporte Laptop",
        "Iluminación", "Mat Escritorio", "Organización"
    );

    /**
     * Standalone canonical categories not covered by the sets above.
     *
     * <p>{@code Otros} es el bucket EXPLÍCITO de "no sé": desde
     * `close-category-vocabulary`, un producto que no matchea ninguna
     * categoría cae acá en vez de inventarse una con la primera palabra del
     * breadcrumb de la tienda. Es feo a propósito — un "no sé" visible se
     * puede medir y corregir; uno disfrazado de categoría real, no.</p>
     */
    private static final Set<String> CATEGORIAS_OTRAS = Set.of("Conjunto","Perfume","Otros");

    /**
     * Full canonical {@code categoria} vocabulary the agent's
     * {@code propose_reclassify} tool (llm-catalog-nlp, Safeguard A) accepts
     * — the union of every category name {@code CategoryClassifier} can
     * resolve to. Single source of truth injected into the tool schema
     * {@code enum} and the system prompt so the model cannot invent a
     * nonexistent category.
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

    /** Categorías de calzado — excluidas de gymrat (el tag es para ROPA). */
    public static boolean esCalzado(String cat) {
        if (cat == null) return false;
        return cat.startsWith("Zapatilla") || cat.equals("Botines") || cat.equals("Borcego")
            || cat.equals("Botas") || cat.equals("Ojotas") || cat.equals("Sneaker")
            || cat.equals("Sandalia") || cat.equals("Mocasin") || cat.equals("Zapato")
            || cat.equals("Pantufla");
    }

    /** Categorías reconocidas como indumentaria o calzado (no suplemento/alimentos). */
    public static boolean esIndumentariaOCalzado(String cat) {
        if (cat == null || cat.isBlank()) return false;
        return esCalzado(cat) || INDUMENTARIA_O_CALZADO_EXTRA.contains(cat);
    }

    /** Categorías resueltas por el clasificador que pertenecen al rubro suplementos/alimentos. */
    public static boolean esCategoriaSuplemento(String cat) {
        return cat != null && CATEGORIAS_SUPLEMENTO.contains(cat);
    }

    /**
     * Las siete categorías del rubro {@code oficina}, enumerables.
     *
     * <p>A diferencia de {@code esIndumentariaOCalzado}/{@code esCategoriaSuplemento},
     * esto NO alimenta ninguna decisión de rubro: {@link RubroResolver} resuelve
     * {@code oficina} por {@code sitio.rubro_forzado}, nunca por la categoría.
     * Una silla la vende una tienda de oficina; que aparezca una en una tienda
     * de ropa no convierte a esa tienda en otra cosa.</p>
     */
    public static Set<String> categoriasOficina() {
        return CATEGORIAS_OFICINA;
    }
}
