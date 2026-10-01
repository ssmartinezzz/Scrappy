package ar.scraper.outfits;

import ar.scraper.aggregator.normalize.GarmentTaxonomy;
import ar.scraper.aggregator.text.AccentStripper;
import ar.scraper.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Supplements are a different domain from clothing that happened to live inside a class named
 * OutfitService: they have their own catalogue categories, their own subtype keyword matching and
 * their own brand preference order, and share none of the slot/style machinery.
 */
public class SupplementCombo {

    private final RecommendationService recommendationService;

    SupplementCombo(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * Los estáticos se inicializan en orden de declaración, y varios de los que siguen normalizan
     * keywords al construirse ({@link #VETO_POR_SUBTIPO}, {@link #SUBTIPOS_POR_PRECEDENCIA}) — un
     * Pattern declarado más abajo llegaría null a su propio uso, con un ExceptionInInitializerError
     * como único síntoma.
     */
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    /**
     * Es metadata de taxonomía, no de presentación, y vive acá por la misma razón que el resto: el
     * frontend mantenía su propia copia de la lista Y de los grupos, así que cada subtipo nuevo
     * había que agregarlo dos veces — y un olvido dejaba un tipo que el builder devuelve y la UI no
     * puede seleccionar.
     */
    private record SubtipoSuplemento(String tipo, String grupo, boolean comida, String[] keywords) {
        SubtipoSuplemento(String tipo, String[] keywords) { this(tipo, null, false, keywords); }
        SubtipoSuplemento(String tipo, String grupo, String[] keywords) { this(tipo, grupo, false, keywords); }

        static SubtipoSuplemento comida(String tipo, String grupo, String[] keywords) {
            return new SubtipoSuplemento(tipo, grupo, true, keywords);
        }
    }

    /**
     * Subtipos del combo de suplementos, en el orden en que se arma el combo — que es también el
     * orden en que se consume el presupuesto.
     */
    private static final List<SubtipoSuplemento> SUPLEMENTO_SUBTIPOS = List.of(
            new SubtipoSuplemento("Proteína en Polvo", "Proteína", new String[]{
                    "proteina", "protein", "whey", "isolate", "concentrate",
                    "caseina", "casein", "proteina isolada", "proteina hidrolizada"
            }),
            new SubtipoSuplemento("Barra Proteica", "Proteína", new String[]{
                    "barra proteica", "barra protein", "barra de proteina", "bar proteica",
                    "barra energetica", "barrita proteica", "barrita protein", "barrita"
            }),
            new SubtipoSuplemento("Pancake / Waffle", "Proteína", new String[]{
                    "pancake", "panqueque", "waffle", "hotcake proteico",
                    "preparo pancake", "mezcla pancake", "mix pancake"
            }),
            new SubtipoSuplemento("Snack Proteico", "Proteína", new String[]{
                    "snack proteico", "snack proteica",
                    "cookie proteica", "cookie protein",
                    "budín proteico", "budin proteico",
                    "muffin proteico", "brownie proteico", "alfajor proteico",
                    "tortita proteica", "galleta proteica",
                    // Accented forms are redundant now that keywords are normalized like the names
                    // they match, but harmless — both fold to the same token, so the duplicate is a
                    // no-op rather than a second rule.
                    "cupcake", "pudding", "budin", "budín", "omelette", "omelet"
            }),
            new SubtipoSuplemento("Creatina", new String[]{"creatina", "creatine", "monohidrato"}),
            new SubtipoSuplemento("BCAA", GarmentTaxonomy.KW_BCAA_SUP),
            new SubtipoSuplemento("Pre-Workout", GarmentTaxonomy.KW_PRE_WORKOUT_SUP),
            // KW_GAINERS trae "mass gainer"/"hipercalorico", y en el catálogo aparecen productos
            // que son sólo "Gainer Xtreme".
            new SubtipoSuplemento("Gainer", new String[]{
                    "gainer", "hipercalorico", "ganador de peso", "mass gainer"
            }),
            new SubtipoSuplemento("Colágeno", GarmentTaxonomy.KW_COLAGENO),
            new SubtipoSuplemento("Quemador", new String[]{"quemador", "fat burner", "termogenico", "carnitina", "cla "}),
            new SubtipoSuplemento("Vitamina C", "Vitaminas", new String[]{
                    "vitamina c", "vitamin c", "acido ascorbico", "ascórbico", "ascorbico"
            }),
            new SubtipoSuplemento("Multivitamínico", "Vitaminas", new String[]{
                    "multivitaminico", "multivitamin", "polivitaminico", "complejo vitaminico",
                    "complejo vitamínico", "multivit"
            }),
            new SubtipoSuplemento("Vitamina D", "Vitaminas", new String[]{
                    "vitamina d", "vitamin d", "colecalciferol", "vitamina d3", "vit d"
            }),
            new SubtipoSuplemento("Omega 3", "Vitaminas", new String[]{
                    "omega 3", "omega3", "omega-3", "aceite de pescado", "fish oil", "dha", "epa"
            }),
            new SubtipoSuplemento("Complejo B", "Vitaminas", new String[]{
                    "complejo b", "vitamina b", "vitaminas b", "b12", "b6", "b complex",
                    "cianocobalamina", "metilcobalamina"
            }),
            new SubtipoSuplemento("Zinc", "Vitaminas", new String[]{
                    "zinc", "gluconato de zinc", "picolinato de zinc", "citrato de zinc"
            }),
            new SubtipoSuplemento("Magnesio", new String[]{"magnesio", "magnesium", "citrato de magnesio"}),
            new SubtipoSuplemento("Mayonesa", "Aderezos", new String[]{
                    "mayonesa fit", "mayonesa light", "mayonesa proteica", "mayonesa zero",
                    "mayo fit", "mayo proteica", "mayo light",
                    "mayonesa"
            }),
            new SubtipoSuplemento("Ketchup / Salsa", "Aderezos", new String[]{
                    "ketchup", "ketchup fit", "ketchup zero", "ketchup sin azucar",
                    "salsa fit", "salsa zero", "salsa de tomate fit",
                    "topping proteico", "topping fit",
                    "aderezo fit", "aderezo proteico"
            }),
            new SubtipoSuplemento("Mostaza", "Aderezos", new String[]{
                    "mostaza fit", "mostaza light", "mostaza zero", "mostaza dijón",
                    "mostaza dijon", "mostaza americana", "mostaza de grano",
                    "salsa mostaza"
            }),
            new SubtipoSuplemento("Maple / Sirope", "Aderezos", new String[]{
                    "maple", "maple fit", "maple sin azucar", "maple zero",
                    "jarabe de arce", "sirope", "sirope fit", "sirope zero",
                    "sirope sin azucar"
            }),
            SubtipoSuplemento.comida("Mermelada / Dulce", "Aderezos", new String[]{
                    "mermelada", "jalea", "dulce de leche", "dulce de membrillo",
                    "untable", "crema untable"
            }),
            SubtipoSuplemento.comida("Miel / Endulzante", "Aderezos", new String[]{
                    "miel ", "endulzante", "edulcorante", "stevia", "sucralosa",
                    "eritritol", "monk fruit"
            }),
            SubtipoSuplemento.comida("Postre Proteico", "Proteína", new String[]{
                    "postre proteico", "flan proteico", "helado proteico",
                    "mousse proteico", "gelatina proteica",
                    "yogur proteico", "yoghurt proteico", "yogur con proteina"
            }),
            SubtipoSuplemento.comida("Bebida Proteica", "Bebidas", new String[]{
                    "bebida proteica", "agua proteica",
                    "leche proteica", "leche con proteina", "leche alta en proteina",
                    "listo para tomar", "listo para beber", "ready to drink",
                    "bebida deportiva", "isotonica"
            }),
            SubtipoSuplemento.comida("Infusiones", "Bebidas", new String[]{
                    "yerba", "mate ", "te verde", "matcha", "infusion",
                    "cafe molido", "cafe en grano", "cafe instantaneo"
            }),
            SubtipoSuplemento.comida("Pasta de Maní", "Alimentos", new String[]{
                    "pasta de mani", "manteca de mani", "mantequilla de mani",
                    "crema de mani", "pasta de almendra", "manteca de almendra",
                    "mantequilla de almendra"
                    // El veto de sabor ya lo resolvería, pero la forma castellana es la que nombra
                    // al frasco y alcanza.
            }),
            SubtipoSuplemento.comida("Avena / Harina", "Alimentos", new String[]{
                    "avena", "harina de avena", "harina integral", "harina de almendra",
                    "oatmeal", "porridge"
            }),
            SubtipoSuplemento.comida("Granola / Cereal", "Alimentos", new String[]{
                    "granola", "cereal", "muesli", "copos de"
            }),
            SubtipoSuplemento.comida("Galletas / Tostadas", "Alimentos", new String[]{
                    "galletita", "galleta", "tostada", "rice cake",
                    "pan proteico", "pan integral", "tortita de arroz"
            }),
            SubtipoSuplemento.comida("Fideos / Arroz", "Alimentos", new String[]{
                    "fideos", "arroz", "konjac", "noodles"
            }),
            SubtipoSuplemento.comida("Snack Salado", "Alimentos", new String[]{
                    "palmito", "chips", "snack saludable", "tostaditas",
                    "pochoclo", "popcorn"
            }),
            SubtipoSuplemento.comida("Frutos Secos", "Alimentos", new String[]{
                    "frutos secos", "almendra", "nueces", "nuez ", "castanas",
                    "castaña de caju", "pistacho", "mani ", "semillas", "chia "
            })
    );

    public static final Set<String> TIPOS_COMBO_OUTFIT = SUPLEMENTO_SUBTIPOS.stream()
            .filter(s -> !s.comida())
            .map(SubtipoSuplemento::tipo)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * Orden de clasificación — de específico a genérico, y deliberadamente distinto al de
     * {@link #SUPLEMENTO_SUBTIPOS} (que es el orden de salida del combo).
     */
    private static final List<String> PRECEDENCIA_CLASIFICACION = List.of(
            "Barra Proteica", "Pancake / Waffle", "Snack Proteico",
            "Creatina", "Pre-Workout", "BCAA", "Gainer", "Quemador",
            "Multivitamínico", "Vitamina C", "Vitamina D", "Complejo B",
            "Omega 3", "Zinc", "Magnesio",
            "Mayonesa", "Ketchup / Salsa", "Mostaza", "Maple / Sirope",
            "Mermelada / Dulce", "Miel / Endulzante",
            // Los subtipos de comida corren DESPUÉS de todo suplemento y ANTES del polvo. Entre
            // ellos el orden también es semántico, y cada par tiene un producto real detrás:
            "Postre Proteico", "Bebida Proteica", "Infusiones",
            "Pasta de Maní", "Avena / Harina", "Granola / Cereal",
            "Galletas / Tostadas", "Fideos / Arroz", "Snack Salado", "Frutos Secos",
            // Proteína en Polvo va al final, y Colágeno DESPUÉS todavía: hay whey fortificada con
            // colágeno y es whey.
            "Proteína en Polvo", "Colágeno");

    /**
     * Formatos que descalifican a un candidato del subtipo "Proteína en Polvo": tiene proteína,
     * pero no es un pote de polvo.
     */
    private static final String[] FORMATO_NO_POLVO = {
            "leche proteica", "leche con proteina", "leche saborizada",
            "yogur proteico", "yogur con proteina", "yoghurt proteico",
            "helado proteico", "flan proteico", "postre proteico",
            "pan proteico", "galletita proteica",
            "bebida proteica", "agua proteica",
            "listo para tomar", "listo para beber", "listo para consumir",
            "ready to drink",
    };

    /**
     * Acá sí van pelados, porque por sí solos no vetan nada: sólo cuentan si el nombre además dice
     * "con proteína".
     */
    private static final String[] FORMATO_ALIMENTO = {
            "leche", "yogur", "yoghurt", "helado", "flan", "postre", "mousse", "gelatina",
            "pan", "galletita", "galleta", "budin", "muffin", "alfajor", "tortita",
            "avena", "granola", "cereal", "queso", "chips", "snack",
            "agua", "jugo", "cafe", "bebida", "crema", "mantequilla", "fideos", "pasta",
            "waffle", "pancake", "panqueque", "brownie", "cookie", "cupcake", "pudding",
    };

    private static final String[] CABEZA_PROTEINA = {
            "proteina", "protein", "whey", "isolate", "caseina", "casein"
    };

    /**
     * Lo estable es el ORDEN. Un polvo se nombra por la cabeza — "Proteína Whey", "Whey Protein
     * Isolate" — mientras que un alimento fortificado arranca por el alimento y menciona la
     * proteína después, como claim.
     */
    private static boolean esProteinaAgregadaAUnAlimento(String nombreNormalizado) {
        int proteina = primeraAparicion(nombreNormalizado, CABEZA_PROTEINA);
        if (proteina < 0) return false;
        int alimento = primeraAparicion(nombreNormalizado, FORMATO_ALIMENTO);
        return alimento >= 0 && alimento < proteina;
    }

    /**
     * Es la misma observación de orden, leída del otro lado, y por eso las dos reglas no pueden
     * contradecirse:
     */
    private static boolean esElSaborDeUnPolvo(String nombreNormalizado) {
        int proteina = primeraAparicion(nombreNormalizado, CABEZA_PROTEINA);
        if (proteina < 0) return false;
        int alimento = primeraAparicion(nombreNormalizado, FORMATO_ALIMENTO);
        return alimento < 0 || alimento > proteina;
    }

    private static int primeraAparicion(String nombreNormalizado, String[] palabras) {
        int mejor = -1;
        for (String palabra : palabras) {
            int i = nombreNormalizado.indexOf(" " + palabra);
            if (i >= 0 && (mejor < 0 || i < mejor)) mejor = i;
        }
        return mejor;
    }

    /**
     * Tenerlo en un solo lugar es el punto: esta línea llegó a afirmar que el mecanismo "hoy sólo
     * lo usa Proteína en Polvo" mientras cuatro líneas más abajo los doce subtipos de comida ya
     * heredaban el suyo.
     */
    private static final Map<String, Predicate<String>> VETO_POR_SUBTIPO = compilarVetos();

    /**
     * "Proteína en Polvo" tiene el suyo — es el único bucket cuyas keywords aparecen en productos
     * de otro formato por el solo hecho de declarar su composición — y cada subtipo de comida
     * hereda el espejo, {@link #esElSaborDeUnPolvo}.
     */
    private static Map<String, Predicate<String>> compilarVetos() {
        Map<String, Predicate<String>> vetos = new HashMap<>();
        vetos.put("Proteína en Polvo",
                vetoDeFrases(FORMATO_NO_POLVO).or(SupplementCombo::esProteinaAgregadaAUnAlimento));
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            if (subtipo.comida()) vetos.put(subtipo.tipo(), SupplementCombo::esElSaborDeUnPolvo);
        }
        return Map.copyOf(vetos);
    }

    private static final Predicate<String> SIN_VETO = n -> false;

    private static Predicate<String> vetoDeFrases(String[] frases) {
        List<String> compiladas = new ArrayList<>(frases.length);
        for (String frase : frases) {
            String norm = normalizar(frase);
            if (norm.isBlank()) continue;
            compiladas.add(norm.substring(0, norm.length() - 1));
        }
        List<String> finales = List.copyOf(compiladas);
        return nombre -> {
            for (String frase : finales) if (nombre.contains(frase)) return true;
            return false;
        };
    }

    /**
     * Un subtipo con sus keywords ya normalizadas y ya padeadas, para que un request no las
     * re-derive ni concatene un pad por comparación.
     */
    private record SubtipoCompilado(String tipo, List<String> prefijos, List<String> exactos,
                                    Predicate<String> veto) {
        boolean matches(String nombreNormalizado) {
            if (veto.test(nombreNormalizado)) return false;
            for (String kw : prefijos) if (nombreNormalizado.contains(kw)) return true;
            for (String kw : exactos)  if (nombreNormalizado.contains(kw)) return true;
            return false;
        }
    }

    private static final List<SubtipoCompilado> SUBTIPOS_POR_PRECEDENCIA = compilarPorPrecedencia();

    private static List<SubtipoCompilado> compilarPorPrecedencia() {
        Map<String, SubtipoSuplemento> porTipo = new LinkedHashMap<>();
        for (SubtipoSuplemento s : SUPLEMENTO_SUBTIPOS) porTipo.put(s.tipo(), s);

        // Fail-fast en class-init si las dos listas divergen: un subtipo agregado a
        // SUPLEMENTO_SUBTIPOS sin lugar en la precedencia nunca se clasificaría, que es exactamente
        // la clase de bug silencioso que este orden viene a cerrar.
        if (!porTipo.keySet().equals(new LinkedHashSet<>(PRECEDENCIA_CLASIFICACION))) {
            throw new IllegalStateException(
                    "PRECEDENCIA_CLASIFICACION y SUPLEMENTO_SUBTIPOS deben listar los mismos subtipos");
        }

        List<SubtipoCompilado> out = new ArrayList<>(PRECEDENCIA_CLASIFICACION.size());
        for (String tipo : PRECEDENCIA_CLASIFICACION) {
            List<String> prefijos = new ArrayList<>();
            List<String> exactos  = new ArrayList<>();
            for (String kw : porTipo.get(tipo).keywords()) {
                boolean palabraCompleta = kw.endsWith(" ");
                String norm = normalizar(kw);
                if (norm.isBlank()) continue;
                if (palabraCompleta) exactos.add(norm);
                else prefijos.add(norm.substring(0, norm.length() - 1));
            }
            out.add(new SubtipoCompilado(tipo, List.copyOf(prefijos), List.copyOf(exactos),
                    VETO_POR_SUBTIPO.getOrDefault(tipo, SIN_VETO)));
        }
        return List.copyOf(out);
    }

    /**
     * Se aplica a las keywords Y a los nombres, así que ambos lados de la comparación viven en el
     * mismo alfabeto — el bug de fondo era justamente que no.
     */
    private static String normalizar(String s) {
        String base = AccentStripper.strip(s.toLowerCase());
        return " " + NO_ALFANUMERICO.matcher(base).replaceAll(" ").trim() + " ";
    }

    /**
     * Orden de preferencia de CATEGORÍA de proteína, pedido por el usuario. A diferencia de las
     * marcas, esto SÍ es un orden.
     */
    private static final List<String> SUPLEMENTO_CATEGORIA_PRIORIDAD =
            List.of("Proteína Isolada");

    /** Categorías que sólo se eligen cuando no hay ninguna otra cosa en el pool. */
    private static final Set<String> SUPLEMENTO_CATEGORIA_ULTIMO_RECURSO =
            Set.of("Proteína Vegetal");

    /**
     * Es un CONJUNTO, no un orden: todas compiten entre sí y el precio por unidad de medida decide
     * cuál gana — {@link #mejorGrupoDeMarca} las junta a todas y {@link #mejorValor} elige.
     */
    private static final Set<String> SUPLEMENTO_MARCAS_PREFERIDAS =
            Set.of("ENA", "Gold Nutrition", "Star Nutrition", "BSN", "Xtrenght");

    private static final Set<String> SUPLEMENTO_MARCAS_PRIORITARIAS = Set.of("BSN");

    /** Viven aparte porque una línea NO es una marca: */
    private static final String[] SUPLEMENTO_LINEAS_PREFERIDAS = {
        " syntha 6 "
    };

    /**
     * Subtipos donde "Regenerar" rota de MARCA y no sólo de URL: BSN primero, después la preferida
     * todavía no mostrada con mejor $/g.
     */
    private static final Set<String> SUBTIPOS_CON_ROTACION_DE_MARCA =
            Set.of("Proteína en Polvo", "Creatina");

    /**
     * Categoría canónica → subtipo, usado SÓLO como fallback cuando el nombre no dice nada (ver
     * {@link #clasificarPorSubtipo}).
     */
    private static final Map<String, String> SUBTIPO_POR_CATEGORIA = Map.ofEntries(
            Map.entry("Proteína",         "Proteína en Polvo"),
            Map.entry("Proteína Isolada", "Proteína en Polvo"),
            Map.entry("Proteína Vegetal", "Proteína en Polvo"),
            Map.entry("Barra Proteica",   "Barra Proteica"),
            Map.entry("Pancake Proteico", "Pancake / Waffle"),
            Map.entry("Snack Proteico",   "Snack Proteico"),
            Map.entry("Creatina",         "Creatina"),
            Map.entry("Pre-Workout",      "Pre-Workout"),
            Map.entry("BCAA",             "BCAA"),
            Map.entry("Gainer",           "Gainer"),
            Map.entry("Colágeno",         "Colágeno"),
            Map.entry("Magnesio",         "Magnesio"),
            Map.entry("Quemadores",       "Quemador"));

    /**
     * Los subtipos del combo, en orden de armado, para que el selector del frontend deje de
     * mantener su propia copia.
     */
    public static List<OutfitService.SupplementTipo> tiposDisponibles() {
        return SUPLEMENTO_SUBTIPOS.stream()
                .map(s -> new OutfitService.SupplementTipo(s.tipo(), s.grupo()))
                .collect(Collectors.toList());
    }

    /** All canonical supplement categories assigned by NormalizerService. */
    private static final Set<String> CATEGORIAS_SUPLEMENTO = Set.of(
            "Suplemento", "Proteína", "Creatina", "Colágeno", "Magnesio",
            "Pre-Workout", "BCAA", "Vitaminas", "Quemadores", "Gainer", "Alimentos",
            // Nutrition subcategories the classifier can assign directly — must be whitelisted here
            // or the product is filtered out before subtype matching.
            "Snack Proteico", "Pancake Proteico", "Barra Proteica",
            // Sin estas dos, el guard estático de abajo tira IllegalStateException en class-init —
            // que es exactamente lo que tiene que pasar: un producto de una categoría no
            // whitelisteada se filtra ANTES de clasificar subtipo, así que el combo simplemente
            // dejaría de ofrecer proteína vegetal sin un solo error.
            "Proteína Isolada", "Proteína Vegetal"
    );

    static {
        // Fail-fast en class-init, mismo criterio que el guard de PRECEDENCIA_CLASIFICACION: una
        // clave que no esté en el whitelist deja el mapeo MUERTO (el producto se filtra antes de
        // clasificar), y un valor que no sea un subtipo real mete productos bajo un tipo que
        // SUPLEMENTO_SUBTIPOS nunca recorre — se pierden sin un solo error.
        Set<String> subtiposValidos = SUPLEMENTO_SUBTIPOS.stream()
                .map(SubtipoSuplemento::tipo).collect(Collectors.toSet());
        SUBTIPO_POR_CATEGORIA.forEach((categoria, tipo) -> {
            if (!CATEGORIAS_SUPLEMENTO.contains(categoria)) {
                throw new IllegalStateException(
                        "SUBTIPO_POR_CATEGORIA mapea una categoría ausente de CATEGORIAS_SUPLEMENTO: " + categoria);
            }
            if (!subtiposValidos.contains(tipo)) {
                throw new IllegalStateException(
                        "SUBTIPO_POR_CATEGORIA apunta a un subtipo inexistente: " + tipo);
            }
        });
    }

    /**
     * Combo de suplementos (Proteína/Creatina/Quemador/Magnesio) a mostrar siempre junto al outfit,
     * independiente de género/estilo — best-effort por subtipo (subtipo sin candidatos se omite del
     * combo, mismo criterio que el accesorio del armador de outfits).
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(List<Product> productos) {
        return armarComboSuplementos(productos, 0);
    }

    /**
     * Combo de suplementos con presupuesto independiente opcional. presupuesto=0 → sin límite
     * (comportamiento original).
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto) {
        return armarComboSuplementos(productos, presupuesto, null);
    }

    List<OutfitService.SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto, Set<String> tipos) {
        return armarComboSuplementos(productos, presupuesto, tipos, null);
    }

    /**
     * Combo con URLs a excluir — lo que el usuario ya vio, para que "Regenerar" ofrezca el
     * siguiente en vez de repetir.
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(
            List<Product> productos, double presupuesto, Set<String> tipos, Set<String> excluirUrls) {
        if (productos == null) productos = List.of();
        final Set<String> excluir = excluirUrls != null ? excluirUrls : Set.of();
        List<Product> suplementos = productos.stream()
                .filter(p -> CATEGORIAS_SUPLEMENTO.contains(p.categoria()))
                .collect(Collectors.toList());

        // Se clasifica el pool ENTERO, no sólo los tipos pedidos: la asignación tiene que ser la
        // misma trate el request de un subtipo o de todos.
        Map<String, List<Product>> porTipo = clasificarPorSubtipo(suplementos);

        List<OutfitService.SupplementPick> combo = new ArrayList<>();
        double remainingBudget = presupuesto;
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            if (tipos != null && !tipos.isEmpty() && !tipos.contains(subtipo.tipo())) continue;
            List<Product> candidatos = porTipo.getOrDefault(subtipo.tipo(), List.of());
            if (candidatos.isEmpty()) continue;

            // Se cuenta sobre el pool entero, antes de sacar lo visto.
            Map<String, Integer> vistasPorMarca = SUBTIPOS_CON_ROTACION_DE_MARCA.contains(subtipo.tipo())
                    ? contarVistasPorMarca(candidatos, excluir) : null;

            // Lo ya mostrado sale del pool, salvo que no quede nada: ahí el ciclo vuelve a empezar
            // en vez de dejar la fila vacía.
            if (!excluir.isEmpty()) {
                List<Product> frescos = candidatos.stream()
                        .filter(p -> !excluir.contains(p.url()))
                        .collect(Collectors.toList());
                if (!frescos.isEmpty()) candidatos = frescos;
            }

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = candidatos.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = elegirPick(affordable, vistasPorMarca);
                } else {
                    elegido = candidatos.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElse(candidatos.get(0));
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = elegirPick(candidatos, vistasPorMarca);
            }
            combo.add(toSupplementPick(subtipo.tipo(), elegido));
        }
        return combo;
    }

    /**
     * Asigna cada producto a EXACTAMENTE UN subtipo — el primero que matchea en orden de
     * precedencia — en una sola pasada.
     */
    private Map<String, List<Product>> clasificarPorSubtipo(List<Product> suplementos) {
        Map<String, List<Product>> porTipo = new HashMap<>();
        for (Product p : suplementos) {
            // Un producto sin nombre se omite: el pick se muestra por nombre, así que colarlo por
            // su categoría dejaría una fila vacía en la UI.
            if (StringUtils.isBlank(p.nombre())) continue;
            String nombreNormalizado = normalizar(p.nombre());

            String tipo = porKeyword(nombreNormalizado);
            if (tipo == null) tipo = porCategoriaCanonica(p.categoria(), nombreNormalizado);
            if (tipo != null) porTipo.computeIfAbsent(tipo, k -> new ArrayList<>()).add(p);
        }
        return porTipo;
    }

    /** Primer subtipo cuyas keywords matchean, en orden de precedencia. null si ninguno. */
    private String porKeyword(String nombreNormalizado) {
        for (SubtipoCompilado subtipo : SUBTIPOS_POR_PRECEDENCIA) {
            if (subtipo.matches(nombreNormalizado)) return subtipo.tipo();
        }
        return null;
    }

    /**
     * El veto del subtipo destino se sigue chequeando: si no, esta ruta sería una puerta trasera
     * que lo saltea.
     */
    private String porCategoriaCanonica(String categoria, String nombreNormalizado) {
        if (categoria == null) return null;
        String tipo = SUBTIPO_POR_CATEGORIA.get(categoria);
        if (tipo == null) return null;
        Predicate<String> veto = VETO_POR_SUBTIPO.getOrDefault(tipo, SIN_VETO);
        return veto.test(nombreNormalizado) ? null : tipo;
    }

    /**
     * Claves de orden, de mayor a menor peso: marca o línea preferida
     * ({@link #SUPLEMENTO_MARCAS_PREFERIDAS}, {@link #SUPLEMENTO_LINEAS_PREFERIDAS}) — es un
     * FILTRO, no un orden: una marca de confianza le gana a una desconocida, pero entre las de
     * confianza no hay jerarquía; precio por unidad de medida ($/g, $/ml, $/cápsula) ascendente,
     * entre los candidatos de unidad comparable;
     */
    private Product elegirPick(List<Product> candidatos, Map<String, Integer> vistasPorMarca) {
        List<Product> porCategoria = mejorGrupoDeCategoria(candidatos);
        if (vistasPorMarca != null) {
            List<Product> enRotacion = siguienteEnRotacion(porCategoria, vistasPorMarca);
            if (!enRotacion.isEmpty()) return mejorValor(enRotacion);
        }
        return mejorValor(mejorGrupoDeMarca(porCategoria));
    }

    /**
     * Entre ellas BSN va primero; si no está, compiten todas y {@link #mejorValor} decide por $/g.
     */
    private List<Product> siguienteEnRotacion(List<Product> candidatos, Map<String, Integer> vistasPorMarca) {
        Map<String, List<Product>> porMarca = new HashMap<>();
        for (Product p : candidatos) {
            String marca = marcaDeRotacion(p);
            if (marca != null) porMarca.computeIfAbsent(marca, k -> new ArrayList<>()).add(p);
        }
        if (porMarca.isEmpty()) return List.of();

        int menosVista = porMarca.keySet().stream()
                .mapToInt(m -> vistasPorMarca.getOrDefault(m, 0)).min().orElseThrow();
        List<String> turno = porMarca.keySet().stream()
                .filter(m -> vistasPorMarca.getOrDefault(m, 0) == menosVista)
                .collect(Collectors.toList());
        for (String prioritaria : SUPLEMENTO_MARCAS_PRIORITARIAS) {
            if (turno.contains(prioritaria)) return porMarca.get(prioritaria);
        }
        return turno.stream().flatMap(m -> porMarca.get(m).stream()).collect(Collectors.toList());
    }

    private Map<String, Integer> contarVistasPorMarca(List<Product> candidatos, Set<String> excluir) {
        Map<String, Integer> vistas = new HashMap<>();
        for (Product p : candidatos) {
            if (!excluir.contains(p.url())) continue;
            String marca = marcaDeRotacion(p);
            if (marca != null) vistas.merge(marca, 1, Integer::sum);
        }
        return vistas;
    }

    /**
     * Marca preferida canónica del producto; un Syntha-6 cuenta como BSN. null si no es preferida.
     */
    private String marcaDeRotacion(Product p) {
        if (esPrioritario(p)) {
            return SUPLEMENTO_MARCAS_PRIORITARIAS.iterator().next();
        }
        if (p.marca() == null) return null;
        return SUPLEMENTO_MARCAS_PREFERIDAS.stream()
                .filter(m -> m.equalsIgnoreCase(p.marca()))
                .findFirst().orElse(null);
    }

    /**
     * Preferencia de CATEGORÍA, y corre por fuera de la de marca: primero se elige qué clase de
     * proteína, y recién adentro de esa clase manda la marca y después el valor.
     */
    private List<Product> mejorGrupoDeCategoria(List<Product> candidatos) {
        for (String categoria : SUPLEMENTO_CATEGORIA_PRIORIDAD) {
            List<Product> delGrupo = candidatos.stream()
                    .filter(p -> categoria.equals(p.categoria()))
                    .collect(Collectors.toList());
            if (!delGrupo.isEmpty()) return delGrupo;
        }
        List<Product> sinUltimoRecurso = candidatos.stream()
                .filter(p -> !SUPLEMENTO_CATEGORIA_ULTIMO_RECURSO.contains(p.categoria()))
                .collect(Collectors.toList());
        return sinUltimoRecurso.isEmpty() ? candidatos : sinUltimoRecurso;
    }

    /**
     * Candidatos de la marca prioritaria; si no hay, de cualquier marca o línea preferida; si
     * tampoco, todos.
     */
    private List<Product> mejorGrupoDeMarca(List<Product> candidatos) {
        List<Product> prioritarios = candidatos.stream()
                .filter(this::esPrioritario)
                .collect(Collectors.toList());
        if (!prioritarios.isEmpty()) return prioritarios;
        List<Product> preferidos = candidatos.stream()
                .filter(this::esPreferido)
                .collect(Collectors.toList());
        return preferidos.isEmpty() ? candidatos : preferidos;
    }

    private boolean esPrioritario(Product p) {
        if (p.marca() != null && SUPLEMENTO_MARCAS_PRIORITARIAS.stream()
                .anyMatch(m -> m.equalsIgnoreCase(p.marca()))) {
            return true;
        }
        return tieneLineaPreferida(p);
    }

    private boolean esPreferido(Product p) {
        if (p.marca() != null && SUPLEMENTO_MARCAS_PREFERIDAS.stream()
                .anyMatch(m -> m.equalsIgnoreCase(p.marca()))) {
            return true;
        }
        return tieneLineaPreferida(p);
    }

    private boolean tieneLineaPreferida(Product p) {
        String nombre = normalizar(p.nombre() == null ? "" : p.nombre());
        for (String linea : SUPLEMENTO_LINEAS_PREFERIDAS) {
            if (nombre.contains(linea)) return true;
        }
        return false;
    }

    /**
     * Sólo se dividen precios por magnitudes de la MISMA familia de unidad: $/gramo contra
     * $/cápsula no es un número que signifique algo.
     */
    private Product mejorValor(List<Product> candidatos) {
        List<Medido> medidos = candidatos.stream()
                .map(p -> new Medido(p, SupplementSizeParser.parse(p.nombre())))
                .collect(Collectors.toList());

        SupplementSizeParser.Familia dominante = familiaDominante(medidos);
        List<Medido> comparables = medidos.stream()
                .filter(m -> m.tamano().familia() == dominante)
                .collect(Collectors.toList());

        if (!comparables.isEmpty()) {
            return comparables.stream()
                    .min(Comparator
                            .comparingDouble((Medido m) -> m.producto().precio() / m.tamano().magnitud())
                            .thenComparingDouble(m -> -recommendationService.baseMlScore(m.producto()))
                            .thenComparing(m -> urlDe(m.producto())))
                    .map(Medido::producto)
                    .orElseThrow();
        }

        return candidatos.stream()
                .min(Comparator
                        .comparingDouble((Product p) -> -recommendationService.baseMlScore(p))
                        .thenComparingDouble(Product::precio)
                        .thenComparing(SupplementCombo::urlDe))
                .orElseThrow();
    }

    private record Medido(Product producto, SupplementSizeParser.Tamano tamano) { }

    /**
     * Empate → MASA, VOLUMEN, CONTEO en ese orden, para que el resultado no dependa del orden de
     * llegada del catálogo.
     */
    private static SupplementSizeParser.Familia familiaDominante(List<Medido> medidos) {
        Map<SupplementSizeParser.Familia, Integer> conteo = new HashMap<>();
        for (Medido m : medidos) {
            if (m.tamano().conocido()) conteo.merge(m.tamano().familia(), 1, Integer::sum);
        }
        SupplementSizeParser.Familia mejor = SupplementSizeParser.Familia.DESCONOCIDA;
        int mejorConteo = 0;
        for (SupplementSizeParser.Familia f : List.of(SupplementSizeParser.Familia.MASA,
                SupplementSizeParser.Familia.VOLUMEN, SupplementSizeParser.Familia.CONTEO)) {
            int c = conteo.getOrDefault(f, 0);
            if (c > mejorConteo) {
                mejor = f;
                mejorConteo = c;
            }
        }
        return mejor;
    }

    private static String urlDe(Product p) {
        return p.url() != null ? p.url() : "";
    }

    private OutfitService.SupplementPick toSupplementPick(String tipo, Product p) {
        String img = p.imagenUrl() != null ? p.imagenUrl() : "";
        if (img.startsWith("//")) img = "https:" + img;
        return new OutfitService.SupplementPick(
                tipo,
                p.sitio() != null ? p.sitio() : "",
                p.nombre() != null ? p.nombre() : "",
                p.precio(),
                p.url() != null ? p.url() : "",
                img,
                p.marca() != null ? p.marca() : "");
    }
}
