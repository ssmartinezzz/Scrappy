package ar.scraper.web;

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

/**
 * Builds the supplement combo (protein, creatine, vitamins, fit condiments…).
 *
 * <p>Extracted verbatim from {@link OutfitService} (backlog A3). Supplements are
 * a different domain from clothing that happened to live inside a class named
 * OutfitService: they have their own catalogue categories, their own subtype
 * keyword matching and their own brand preference order, and share none of the
 * slot/style machinery.</p>
 *
 * <p>{@code SupplementPick} stays nested on OutfitService — callers and tests
 * name it {@code OutfitService.SupplementPick}.</p>
 */
class SupplementCombo {

    /**
     * Only used for {@code baseMlScore}, the tiebreak for candidates whose package
     * size cannot be read off the name — the same ranking the "Para ti" feed and the
     * budget builder already use, rather than a third opinion invented here.
     */
    private final RecommendationService recommendationService;

    SupplementCombo(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * PRIMER campo estático de la clase, a propósito. Los estáticos se inicializan en
     * orden de declaración, y varios de los que siguen normalizan keywords al construirse
     * ({@link #VETO_POR_SUBTIPO}, {@link #SUBTIPOS_POR_PRECEDENCIA}) — un Pattern
     * declarado más abajo llegaría null a su propio uso, con un
     * ExceptionInInitializerError como único síntoma.
     */
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");

    /**
     * Un subtipo del combo.
     *
     * <p>{@code grupo} es el encabezado bajo el que el selector agrupa el tipo
     * ({@code null} = "Otros"). Es metadata de taxonomía, no de presentación, y vive acá
     * por la misma razón que el resto: el frontend mantenía su propia copia de la lista
     * Y de los grupos, así que cada subtipo nuevo había que agregarlo dos veces — y un
     * olvido dejaba un tipo que el builder devuelve y la UI no puede seleccionar.</p>
     */
    private record SubtipoSuplemento(String tipo, String grupo, boolean comida, String[] keywords) {
        SubtipoSuplemento(String tipo, String[] keywords) { this(tipo, null, false, keywords); }
        SubtipoSuplemento(String tipo, String grupo, String[] keywords) { this(tipo, grupo, false, keywords); }

        /**
         * Un subtipo de COMIDA. La bandera arrastra dos consecuencias, y las dos salen
         * del mismo hecho — es un alimento, no un suplemento:
         *
         * <ol>
         *   <li>queda fuera del combo que acompaña al outfit de Gym
         *       ({@link #TIPOS_COMBO_OUTFIT}). Ese combo se arma con TODOS los subtipos,
         *       así que cada tipo nuevo le agrega una tarjeta a una grilla que ya tiene
         *       21: el stack sugerido dejaría de ser una sugerencia. El builder de
         *       {@code /suplementos}, donde el usuario elige, los ofrece completos;</li>
         *   <li>hereda el veto de sabor ({@link #esElSaborDeUnPolvo}): sus keywords son
         *       sustantivos culinarios, y un polvo los usa para nombrar su gusto.</li>
         * </ol>
         */
        static SubtipoSuplemento comida(String tipo, String grupo, String[] keywords) {
            return new SubtipoSuplemento(tipo, grupo, true, keywords);
        }
    }

    /**
     * Subtipos del combo de suplementos, en el orden en que se arma el combo —
     * que es también el orden en que se consume el presupuesto. NO es el orden de
     * clasificación: para eso está {@link #PRECEDENCIA_CLASIFICACION}, donde los
     * subtipos específicos corren antes que el bucket genérico de proteína.
     * Cada producto con categoria=="Suplemento" se reclasifica por nombre (no
     * toca el campo categoria canónico — evita romper el whitelist de accesorio
     * Gym ni los facets del dashboard, que dependen del string "Suplemento").
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
                    // GRANGER-style protein snacks (product-owner request): match the
                    // bare noun so branded food (categoria "Alimentos") surfaces here.
                    // Accented forms are redundant now that keywords are normalized
                    // like the names they match, but harmless — both fold to the same
                    // token, so the duplicate is a no-op rather than a second rule.
                    "cupcake", "pudding", "budin", "budín", "omelette", "omelet"
            }),
            new SubtipoSuplemento("Creatina", new String[]{"creatina", "creatine", "monohidrato"}),
            // BCAA y Pre-Workout leen las keywords CANÓNICAS de GarmentTaxonomy en vez de
            // una cuarta copia acá: son las mismas con las que el clasificador asigna las
            // categorías "BCAA" y "Pre-Workout", así que las dos capas no pueden divergir.
            new SubtipoSuplemento("BCAA", GarmentTaxonomy.KW_BCAA_SUP),
            new SubtipoSuplemento("Pre-Workout", GarmentTaxonomy.KW_PRE_WORKOUT_SUP),
            // "gainer" pelado además de los tokens canónicos: KW_GAINERS trae
            // "mass gainer"/"hipercalorico", y en el catálogo aparecen productos que son
            // sólo "Gainer Xtreme". Como el bucket de proteína ya no los acepta, sin este
            // token quedarían fuera del combo por completo.
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
                    // "cafe" pelado NO: es uno de los sabores más comunes de un whey.
                    // Las tres formas de abajo nombran al café como producto.
                    "cafe molido", "cafe en grano", "cafe instantaneo"
            }),
            SubtipoSuplemento.comida("Pasta de Maní", "Alimentos", new String[]{
                    "pasta de mani", "manteca de mani", "mantequilla de mani",
                    "crema de mani", "pasta de almendra", "manteca de almendra",
                    "mantequilla de almendra"
                    // "peanut butter" NO: en este catálogo aparece como SABOR de un
                    // isolate ("RAW Proteína Itholate … CHOCOLATE PEANUT BUTTER") tanto
                    // como en un frasco. El veto de sabor ya lo resolvería, pero la
                    // forma castellana es la que nombra al frasco y alcanza.
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

    /**
     * Los subtipos que arma el combo que acompaña al outfit de Gym — todos menos los
     * de comida. Ver {@link SubtipoSuplemento#comida}.
     */
    static final Set<String> TIPOS_COMBO_OUTFIT = SUPLEMENTO_SUBTIPOS.stream()
            .filter(s -> !s.comida())
            .map(SubtipoSuplemento::tipo)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * Orden de clasificación — de específico a genérico, y deliberadamente distinto
     * al de {@link #SUPLEMENTO_SUBTIPOS} (que es el orden de salida del combo).
     *
     * <p>Una barra de proteína es una barra, no un polvo. Antes cada subtipo filtraba
     * el pool completo por su cuenta, así que un nombre que traía tokens de dos
     * subtipos ("Barra de Proteína Whey") se emitía DOS veces: el mismo URL como
     * polvo y como barra. Con asignación única el orden pasa a ser semántico —
     * quien corre primero se lo lleva — y por eso los subtipos específicos van
     * antes que "Proteína en Polvo", y "Multivitamínico" antes que las vitaminas
     * individuales que un multivitamínico nombra de paso.</p>
     */
    private static final List<String> PRECEDENCIA_CLASIFICACION = List.of(
            "Barra Proteica", "Pancake / Waffle", "Snack Proteico",
            "Creatina", "Pre-Workout", "BCAA", "Gainer", "Quemador",
            "Multivitamínico", "Vitamina C", "Vitamina D", "Complejo B",
            "Omega 3", "Zinc", "Magnesio",
            "Mayonesa", "Ketchup / Salsa", "Mostaza", "Maple / Sirope",
            "Mermelada / Dulce", "Miel / Endulzante",
            // Los subtipos de comida corren DESPUÉS de todo suplemento y ANTES del polvo.
            // Entre ellos el orden también es semántico, y cada par tiene un producto real
            // detrás: "Pasta de Maní" antes que "Frutos Secos" (un frasco de pasta de maní
            // dice "mani"), "Avena / Harina" antes que "Frutos Secos" ("Harina de Almendras"
            // es harina) y antes que "Granola / Cereal" ("Cereal de Avena" es avena).
            "Postre Proteico", "Bebida Proteica", "Infusiones",
            "Pasta de Maní", "Avena / Harina", "Granola / Cereal",
            "Galletas / Tostadas", "Fideos / Arroz", "Snack Salado", "Frutos Secos",
            // Proteína en Polvo va al final, y Colágeno DESPUÉS todavía: hay whey
            // fortificada con colágeno y es whey. Un colágeno puro no nombra proteína,
            // así que no matchea el bucket de polvo y cae acá igual. Es el mismo
            // razonamiento que dejó KW_COLAGENO debajo de KW_PROTEINA en el clasificador.
            "Proteína en Polvo", "Colágeno");

    /**
     * Formatos que descalifican a un candidato del subtipo "Proteína en Polvo": tiene
     * proteína, pero no es un pote de polvo.
     *
     * <p>Son FRASES, no sustantivos pelados, y eso es la restricción central: los
     * nombres de sabor usan exactamente los mismos sustantivos que un veto de formato
     * querría atrapar. "Whey sabor Leche Chocolatada" y "sabor Yogur Griego" son whey.
     * Vetar " leche " o " yogur " mataría productos legítimos, así que el veto sólo
     * dispara cuando el sustantivo viene junto a la proteína, que es cuando nombra al
     * producto en vez de a su gusto.</p>
     *
     * <p>El gainer y el hipercalórico salieron de esta lista: ahora son un subtipo
     * propio por encima del polvo, así que la precedencia los saca del bucket de
     * proteína sola — y de paso los vuelve elegibles como lo que son. Un subtipo es
     * mejor mecanismo que un veto: no descarta, clasifica.</p>
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
     * Sustantivos de formato comida/bebida, para la regla COMPUESTA de abajo. Acá sí van
     * pelados, porque por sí solos no vetan nada: sólo cuentan si el nombre además dice
     * "con proteína".
     */
    private static final String[] FORMATO_ALIMENTO = {
            "leche", "yogur", "yoghurt", "helado", "flan", "postre", "mousse", "gelatina",
            "pan", "galletita", "galleta", "budin", "muffin", "alfajor", "tortita",
            "avena", "granola", "cereal", "queso", "chips", "snack",
            "agua", "jugo", "cafe", "bebida", "crema", "mantequilla", "fideos", "pasta",
            "waffle", "pancake", "panqueque", "brownie", "cookie", "cupcake", "pudding",
    };

    /**
     * Palabras con las que un polvo se anuncia a sí mismo. Se usan para ubicar
     * DÓNDE aparece la proteína en el nombre, no para decidir si aparece.
     */
    private static final String[] CABEZA_PROTEINA = {
            "proteina", "protein", "whey", "isolate", "caseina", "casein"
    };

    /**
     * Regla estructural: si un sustantivo de formato comida aparece ANTES que la
     * palabra proteína, el producto es un alimento fortificado, no un polvo.
     *
     * <p>La versión anterior exigía la frase literal {@code " con proteina"}, y eso
     * hacía que el conector del envase decidiera el resultado. Dos frascos de pasta de
     * maní del catálogo real caían de lados distintos:</p>
     *
     * <pre>
     *   "Pasta de Mani con Proteina Power Cookies"   → vetado
     *   "Pasta De Maní … 370g Alta Proteína"          → NO vetado, se ofrecía como polvo
     * </pre>
     *
     * <p>Enumerar conectores pierde para siempre: "alta proteína", "rica en proteína",
     * "fuente de proteína", "+ proteína", "20g de proteína". Lo estable es el ORDEN.
     * Un polvo se nombra por la cabeza — "Proteína Whey", "Whey Protein Isolate" —
     * mientras que un alimento fortificado arranca por el alimento y menciona la
     * proteína después, como claim.</p>
     *
     * <p>Los sabores quedan a salvo por construcción, que es lo que un veto de
     * "menciona un sustantivo de comida" no lograba: un sabor SIEMPRE va detrás de la
     * cabeza ("Proteína Whey sabor Cookies &amp; Cream", "Whey sabor Leche
     * Chocolatada"), así que la proteína aparece primero y la regla no dispara.</p>
     */
    private static boolean esProteinaAgregadaAUnAlimento(String nombreNormalizado) {
        int proteina = primeraAparicion(nombreNormalizado, CABEZA_PROTEINA);
        if (proteina < 0) return false;
        int alimento = primeraAparicion(nombreNormalizado, FORMATO_ALIMENTO);
        return alimento >= 0 && alimento < proteina;
    }

    /**
     * Espejo exacto de {@link #esProteinaAgregadaAUnAlimento}, para los subtipos de
     * comida: si el nombre arranca por la cabeza de un polvo y ningún sustantivo de
     * comida la precede, el sustantivo culinario que matcheó es el SABOR del polvo y no
     * el producto.
     *
     * <p>Es la misma observación de orden, leída del otro lado, y por eso las dos reglas
     * no pueden contradecirse: "Whey Protein sabor Leche Chocolatada" no es una bebida
     * láctea, "Proteína Whey sabor Yogur Griego" no es un postre y "Whey sabor Chocolate
     * Chips" no es un snack salado — en las tres la proteína se nombra primero. Al revés,
     * "Leche con Proteína 1L" y "Avena Alta en Proteína 500g" arrancan por el alimento,
     * así que el veto no dispara y el subtipo de comida se los queda.</p>
     *
     * <p>Sin esta regla, cada sustantivo de comida agregado acá le robaba productos al
     * bucket de proteína en polvo — el mismo bug que {@code FORMATO_NO_POLVO} cerró en
     * la otra dirección, reintroducido desde el lado de los alimentos.</p>
     */
    private static boolean esElSaborDeUnPolvo(String nombreNormalizado) {
        int proteina = primeraAparicion(nombreNormalizado, CABEZA_PROTEINA);
        if (proteina < 0) return false;
        int alimento = primeraAparicion(nombreNormalizado, FORMATO_ALIMENTO);
        return alimento < 0 || alimento > proteina;
    }

    /** Índice de la primera de estas palabras en el nombre padeado, o -1. */
    private static int primeraAparicion(String nombreNormalizado, String[] palabras) {
        int mejor = -1;
        for (String palabra : palabras) {
            int i = nombreNormalizado.indexOf(" " + palabra);
            if (i >= 0 && (mejor < 0 || i < mejor)) mejor = i;
        }
        return mejor;
    }

    /**
     * Vetos por subtipo: el predicado que descalifica un match de keywords que la
     * forma del nombre delata como otra cosa.
     *
     * <p>Quién tiene veto y por qué vive en {@link #compilarVetos()}, que es donde se
     * arma. Tenerlo en un solo lugar es el punto: esta línea llegó a afirmar que el
     * mecanismo "hoy sólo lo usa Proteína en Polvo" mientras cuatro líneas más abajo
     * los doce subtipos de comida ya heredaban el suyo.</p>
     */
    private static final Map<String, Predicate<String>> VETO_POR_SUBTIPO = compilarVetos();

    /**
     * "Proteína en Polvo" tiene el suyo — es el único bucket cuyas keywords aparecen en
     * productos de otro formato por el solo hecho de declarar su composición — y cada
     * subtipo de comida hereda el espejo, {@link #esElSaborDeUnPolvo}. Se deriva de la
     * bandera y no se lista a mano: un subtipo de comida nuevo no puede olvidarse el veto.
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

    /** Ningún veto — el caso de los subtipos de suplemento, que no necesitan uno. */
    private static final Predicate<String> SIN_VETO = n -> false;

    /**
     * Compila frases a un veto. Se ancla como prefijo igual que las keywords, así
     * "leche con proteina" también atrapa "…proteinas".
     */
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
     * Un subtipo con sus keywords ya normalizadas y ya padeadas, para que un request
     * no las re-derive ni concatene un pad por comparación.
     *
     * <p>{@code prefijos} ancla al ARRANQUE de una palabra y admite sufijo, que es lo
     * que mantiene viva la flexión del castellano ("barrita" sigue matcheando
     * "Barritas"). {@code exactos} sale de las keywords DECLARADAS con espacio final
     * — la convención que ya usaban este archivo y {@code GarmentTaxonomy} para decir
     * "palabra completa, sin sufijo": "cla " no debe convertir "Clásico" en un
     * quemador.</p>
     *
     * <p>{@code vetos} se chequea PRIMERO y descalifica: un veto que matchea gana sobre
     * cualquier keyword que también matchee (ver {@link #FORMATO_NO_POLVO}).</p>
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
        // SUPLEMENTO_SUBTIPOS sin lugar en la precedencia nunca se clasificaría, que
        // es exactamente la clase de bug silencioso que este orden viene a cerrar.
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
                String norm = normalizar(kw); // viene padeado a ambos lados
                if (norm.isBlank()) continue;
                // Palabra completa conserva el pad derecho; el prefijo lo suelta.
                if (palabraCompleta) exactos.add(norm);
                else prefijos.add(norm.substring(0, norm.length() - 1));
            }
            out.add(new SubtipoCompilado(tipo, List.copyOf(prefijos), List.copyOf(exactos),
                    VETO_POR_SUBTIPO.getOrDefault(tipo, SIN_VETO)));
        }
        return List.copyOf(out);
    }

    /**
     * minúsculas → acentos removidos → toda corrida de no-alfanuméricos colapsada a
     * un espacio → padeado con espacios.
     *
     * <p>Que la puntuación se vuelva límite de palabra es a propósito: "Proteína/Whey"
     * y "OMEGA-3" tokenizan igual que sus formas con espacio, algo que un
     * {@code contains()} pelado sobre el nombre crudo no podía hacer. Se aplica a las
     * keywords Y a los nombres, así que ambos lados de la comparación viven en el
     * mismo alfabeto — el bug de fondo era justamente que no.</p>
     */
    private static String normalizar(String s) {
        String base = AccentStripper.strip(s.toLowerCase());
        return " " + NO_ALFANUMERICO.matcher(base).replaceAll(" ").trim() + " ";
    }

    /**
     * Orden de preferencia de marca para el combo de suplementos, confirmado por el
     * usuario. Es un ORDEN, no un conjunto: {@link #mejorGrupoDeMarca} se queda con
     * el primero que tenga stock, y recién ahí el valor desempata.
     *
     * <p>Los strings tienen que coincidir con los que emite
     * {@code BrandExtractor.MARCAS} — es de donde sale {@code Product.marca()}. La
     * versión anterior de esta lista ("ENA", "STAR", "BCC") no matcheaba nada porque
     * esa lista curada no conocía ni una marca de suplementos, así que toda marca
     * caía al nombre del sitio y esta preferencia era código muerto.</p>
     */
    private static final List<String> SUPLEMENTO_MARCA_PRIORIDAD =
            List.of("ENA", "Gold Nutrition", "Star Nutrition", "BSA", "Xtrenght");

    /**
     * Categoría canónica → subtipo, usado SÓLO como fallback cuando el nombre no dice
     * nada (ver {@link #clasificarPorSubtipo}).
     *
     * <p>Cierra el agujero de fondo: el builder re-derivaba el subtipo del nombre crudo
     * e ignoraba la categoría que el clasificador YA había resuelto. Un producto cuya
     * señal de proteína venía de la colección del sitio y no de su título — "Nitro Tech
     * 908g" — quedaba invisible, aunque su {@code categoria} dijera "Proteína".</p>
     *
     * <p>Es fallback y no clave primaria porque una keyword del nombre es estrictamente
     * más específica: "Barra de Proteína Whey" tiene categoria "Proteína" y es una barra.
     * Las keywords deciden primero; esto sólo habla cuando se quedaron calladas.</p>
     *
     * <p>Deliberadamente NO mapea "Vitaminas", "Suplemento" ni "Alimentos": la primera no
     * permite elegir entre Vitamina C / D / Complejo B, y las otras dos no dicen nada.
     * Adivinar sería peor que omitir, que es lo que ya pasa hoy con esos productos.</p>
     */
    private static final Map<String, String> SUBTIPO_POR_CATEGORIA = Map.ofEntries(
            Map.entry("Proteína",         "Proteína en Polvo"),
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
     * Los subtipos del combo, en orden de armado, para que el selector del frontend deje
     * de mantener su propia copia. Expuesto vía {@code GET /api/suplementos/tipos}.
     */
    static List<OutfitService.SupplementTipo> tiposDisponibles() {
        return SUPLEMENTO_SUBTIPOS.stream()
                .map(s -> new OutfitService.SupplementTipo(s.tipo(), s.grupo()))
                .collect(Collectors.toList());
    }

    /** All canonical supplement categories assigned by NormalizerService. */
    private static final Set<String> CATEGORIAS_SUPLEMENTO = Set.of(
            "Suplemento", "Proteína", "Creatina", "Colágeno", "Magnesio",
            "Pre-Workout", "BCAA", "Vitaminas", "Quemadores", "Gainer", "Alimentos",
            // Nutrition subcategories the classifier can assign directly — must be
            // whitelisted here or the product is filtered out before subtype matching.
            "Snack Proteico", "Pancake Proteico", "Barra Proteica"
    );

    static {
        // Fail-fast en class-init, mismo criterio que el guard de PRECEDENCIA_CLASIFICACION:
        // una clave que no esté en el whitelist deja el mapeo MUERTO (el producto se filtra
        // antes de clasificar), y un valor que no sea un subtipo real mete productos bajo un
        // tipo que SUPLEMENTO_SUBTIPOS nunca recorre — se pierden sin un solo error.
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
     * Combo de suplementos (Proteína/Creatina/Quemador/Magnesio) a mostrar siempre
     * junto al outfit, independiente de género/estilo — best-effort por subtipo
     * (subtipo sin candidatos se omite del combo, mismo criterio que el accesorio
     * del armador de outfits).
     * Backward-compat overload: sin límite de presupuesto (comportamiento original).
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(List<Product> productos) {
        return armarComboSuplementos(productos, 0);
    }

    /**
     * Combo de suplementos con presupuesto independiente opcional.
     * presupuesto=0 → sin límite (comportamiento original).
     * Budget-aware: por subtipo, filtra candidatos por precio ≤ remaining. Si ninguno
     * cabe dentro del presupuesto restante, elige el más barato disponible (no bloquea
     * el slot — combo completo > slot vacío).
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto) {
        return armarComboSuplementos(productos, presupuesto, null);
    }

    /**
     * Combo de suplementos filtrado por tipos solicitados (subset de SUPLEMENTO_SUBTIPOS).
     * tipos vacío o null → usa todos los subtipos (backward-compat con el overload de 2 args).
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(List<Product> productos, double presupuesto, Set<String> tipos) {
        return armarComboSuplementos(productos, presupuesto, tipos, null);
    }

    /**
     * Combo con URLs a excluir — lo que el usuario ya vio, para que "Regenerar"
     * ofrezca el siguiente en vez de repetir.
     *
     * <p>El pick es deterministico a propósito (marca → $/unidad → score → url), así
     * que un request idéntico devuelve lo mismo: es el arreglo de que el builder
     * cambiara de suplemento solo por volver a preguntar. Por eso la variedad viene
     * del cliente diciendo qué ya se le mostró, y no de volver a meter azar.</p>
     *
     * <p>Si un subtipo se queda sin candidatos tras excluir, la exclusión se ignora
     * <em>para ese subtipo</em> y el ciclo vuelve a empezar por el mejor. Una fila
     * vacía es peor producto que una repetida: el usuario pidió un stack, no que el
     * panel se encoja a medida que clickea.</p>
     */
    List<OutfitService.SupplementPick> armarComboSuplementos(
            List<Product> productos, double presupuesto, Set<String> tipos, Set<String> excluirUrls) {
        if (productos == null) productos = List.of();
        final Set<String> excluir = excluirUrls != null ? excluirUrls : Set.of();
        List<Product> suplementos = productos.stream()
                .filter(p -> CATEGORIAS_SUPLEMENTO.contains(p.categoria()))
                .collect(Collectors.toList());

        // Se clasifica el pool ENTERO, no sólo los tipos pedidos: la asignación tiene
        // que ser la misma trate el request de un subtipo o de todos. Si se filtrara
        // antes, pedir sólo "Proteína en Polvo" volvería a mostrar barras como polvo —
        // el bug de doble asignación, disfrazado de filtro.
        Map<String, List<Product>> porTipo = clasificarPorSubtipo(suplementos);

        List<OutfitService.SupplementPick> combo = new ArrayList<>();
        double remainingBudget = presupuesto;
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            if (tipos != null && !tipos.isEmpty() && !tipos.contains(subtipo.tipo())) continue;
            List<Product> candidatos = porTipo.getOrDefault(subtipo.tipo(), List.of());
            if (candidatos.isEmpty()) continue;

            // Lo ya mostrado sale del pool, salvo que no quede nada: ahí el ciclo
            // vuelve a empezar en vez de dejar la fila vacía.
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
                    elegido = elegirPick(affordable);
                } else {
                    // Nada entra en el presupuesto restante: acá lo que importa es gastar
                    // lo mínimo posible, no el mejor $/kg — de ahí el precio absoluto.
                    elegido = candidatos.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElse(candidatos.get(0));
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = elegirPick(candidatos);
            }
            combo.add(toSupplementPick(subtipo.tipo(), elegido));
        }
        return combo;
    }

    /**
     * Asigna cada producto a EXACTAMENTE UN subtipo — el primero que matchea en orden
     * de precedencia — en una sola pasada.
     *
     * <p>Reemplaza un stream completo por subtipo (17 pasadas sobre el pool, con un
     * {@code toLowerCase()} nuevo de cada nombre en cada una) por una pasada que
     * normaliza cada nombre una única vez. Un producto sin subtipo simplemente no
     * entra al mapa: el combo lo omite, igual que antes.</p>
     */
    private Map<String, List<Product>> clasificarPorSubtipo(List<Product> suplementos) {
        Map<String, List<Product>> porTipo = new HashMap<>();
        for (Product p : suplementos) {
            // Un producto sin nombre se omite: el pick se muestra por nombre, así que
            // colarlo por su categoría dejaría una fila vacía en la UI.
            if (p.nombre() == null || p.nombre().isBlank()) continue;
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
     * Subtipo derivado de la categoría canónica, cuando el nombre no alcanzó.
     *
     * <p>El veto del subtipo destino se sigue chequeando: si no, esta ruta sería una
     * puerta trasera que lo saltea. Una "Leche con Proteína" tiene categoria "Proteína",
     * así que sin este chequeo el fallback la reinsertaría como proteína en polvo justo
     * después de que el veto la hubiera descartado.</p>
     */
    private String porCategoriaCanonica(String categoria, String nombreNormalizado) {
        if (categoria == null) return null;
        String tipo = SUBTIPO_POR_CATEGORIA.get(categoria);
        if (tipo == null) return null;
        Predicate<String> veto = VETO_POR_SUBTIPO.getOrDefault(tipo, SIN_VETO);
        return veto.test(nombreNormalizado) ? null : tipo;
    }

    /**
     * Elige el pick de un subtipo. Antes era "el primer producto de la marca preferida
     * que aparezca, y si no hay ninguno, uno AL AZAR" — o sea que el mismo catálogo
     * devolvía otro suplemento en cada request, y entre dos potes de la misma marca
     * ganaba el que la lista trajera primero.
     *
     * <p>Claves de orden, de mayor a menor peso:</p>
     * <ol>
     *   <li>marca preferida ({@link #SUPLEMENTO_MARCA_PRIORIDAD}) — es una preferencia
     *       confirmada por el usuario, así que sigue mandando sobre el valor;</li>
     *   <li>precio por unidad de medida ($/g, $/ml, $/cápsula) ascendente, entre los
     *       candidatos de unidad comparable;</li>
     *   <li>{@code baseMlScore} descendente, para los que no declaran tamaño;</li>
     *   <li>url ascendente, para que el pick sea estable entre requests.</li>
     * </ol>
     *
     * <p>Que la marca gane sobre el valor es deliberado, no un descuido: invertir las
     * dos primeras claves es mover una línea, el día que se prefiera el mejor $/kg del
     * catálogo por encima de la marca de confianza.</p>
     */
    private Product elegirPick(List<Product> candidatos) {
        return mejorValor(mejorGrupoDeMarca(candidatos));
    }

    /** Candidatos de la marca preferida de mayor prioridad presente; si no hay, todos. */
    private List<Product> mejorGrupoDeMarca(List<Product> candidatos) {
        for (String marca : SUPLEMENTO_MARCA_PRIORIDAD) {
            List<Product> delGrupo = candidatos.stream()
                    .filter(p -> marca.equalsIgnoreCase(p.marca()))
                    .collect(Collectors.toList());
            if (!delGrupo.isEmpty()) return delGrupo;
        }
        return candidatos;
    }

    /**
     * Mejor relación precio/tamaño del grupo. Sólo se dividen precios por magnitudes de
     * la MISMA familia de unidad: $/gramo contra $/cápsula no es un número que signifique
     * algo. Se toma la familia mayoritaria del grupo y los que quedan afuera (otra familia,
     * o sin tamaño legible) caen al desempate por score.
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

    /** Un candidato con su tamaño ya parseado, para no re-parsear el nombre por comparación. */
    private record Medido(Product producto, SupplementSizeParser.Tamano tamano) { }

    /**
     * Familia de unidad mayoritaria entre los candidatos con tamaño legible.
     * Empate → MASA, VOLUMEN, CONTEO en ese orden, para que el resultado no dependa
     * del orden de llegada del catálogo. Ninguno legible → DESCONOCIDA, que no
     * matchea a nadie y manda todo el grupo al desempate por score.
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
