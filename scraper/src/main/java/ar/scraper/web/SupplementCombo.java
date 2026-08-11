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

    private record SubtipoSuplemento(String tipo, String[] keywords) { }

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
            new SubtipoSuplemento("Proteína en Polvo", new String[]{
                    "proteina", "protein", "whey", "isolate", "concentrate",
                    "caseina", "casein", "proteina isolada", "proteina hidrolizada"
            }),
            new SubtipoSuplemento("Barra Proteica", new String[]{
                    "barra proteica", "barra protein", "barra de proteina", "bar proteica",
                    "barra energetica", "barrita proteica", "barrita protein", "barrita"
            }),
            new SubtipoSuplemento("Pancake / Waffle", new String[]{
                    "pancake", "panqueque", "waffle", "hotcake proteico",
                    "preparo pancake", "mezcla pancake", "mix pancake"
            }),
            new SubtipoSuplemento("Snack Proteico", new String[]{
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
            new SubtipoSuplemento("Vitamina C", new String[]{
                    "vitamina c", "vitamin c", "acido ascorbico", "ascórbico", "ascorbico"
            }),
            new SubtipoSuplemento("Multivitamínico", new String[]{
                    "multivitaminico", "multivitamin", "polivitaminico", "complejo vitaminico",
                    "complejo vitamínico", "multivit"
            }),
            new SubtipoSuplemento("Vitamina D", new String[]{
                    "vitamina d", "vitamin d", "colecalciferol", "vitamina d3", "vit d"
            }),
            new SubtipoSuplemento("Omega 3", new String[]{
                    "omega 3", "omega3", "omega-3", "aceite de pescado", "fish oil", "dha", "epa"
            }),
            new SubtipoSuplemento("Complejo B", new String[]{
                    "complejo b", "vitamina b", "vitaminas b", "b12", "b6", "b complex",
                    "cianocobalamina", "metilcobalamina"
            }),
            new SubtipoSuplemento("Zinc", new String[]{
                    "zinc", "gluconato de zinc", "picolinato de zinc", "citrato de zinc"
            }),
            new SubtipoSuplemento("Magnesio", new String[]{"magnesio", "magnesium", "citrato de magnesio"}),
            new SubtipoSuplemento("Mayonesa", new String[]{
                    "mayonesa fit", "mayonesa light", "mayonesa proteica", "mayonesa zero",
                    "mayo fit", "mayo proteica", "mayo light",
                    "mayonesa"
            }),
            new SubtipoSuplemento("Ketchup / Salsa", new String[]{
                    "ketchup", "ketchup fit", "ketchup zero", "ketchup sin azucar",
                    "salsa fit", "salsa zero", "salsa de tomate fit",
                    "topping proteico", "topping fit",
                    "aderezo fit", "aderezo proteico"
            }),
            new SubtipoSuplemento("Mostaza", new String[]{
                    "mostaza fit", "mostaza light", "mostaza zero", "mostaza dijón",
                    "mostaza dijon", "mostaza americana", "mostaza de grano",
                    "salsa mostaza"
            }),
            new SubtipoSuplemento("Maple / Sirope", new String[]{
                    "maple", "maple fit", "maple sin azucar", "maple zero",
                    "jarabe de arce", "sirope", "sirope fit", "sirope zero",
                    "sirope sin azucar"
            })
    );

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
     * Regla compuesta: "<alimento> … con proteína" no es proteína en polvo.
     *
     * <p>Enumerar esa forma frase por frase pierde para siempre — "avena con proteína",
     * "galletitas con proteína", "queso con proteína", y la que salga la semana que
     * viene. La estructura sí es estable: un formato de comida más la proteína
     * apareciendo como agregado. Un polvo no se anuncia "con proteína", se anuncia
     * "Proteína Whey" o "Whey Protein" — el "con" es lo que delata al alimento
     * fortificado.</p>
     */
    private static boolean esProteinaAgregadaAUnAlimento(String nombreNormalizado) {
        if (!nombreNormalizado.contains(" con proteina")) return false;
        for (String noun : FORMATO_ALIMENTO) {
            if (nombreNormalizado.contains(" " + noun)) return true;
        }
        return false;
    }

    /**
     * Vetos por subtipo. El mecanismo es general; hoy sólo lo usa "Proteína en Polvo",
     * que es el único bucket cuyas keywords ("proteina", "protein") aparecen en
     * productos de otro formato por el simple hecho de declarar su composición.
     */
    private static final Map<String, Predicate<String>> VETO_POR_SUBTIPO = Map.of(
            "Proteína en Polvo",
            vetoDeFrases(FORMATO_NO_POLVO).or(SupplementCombo::esProteinaAgregadaAUnAlimento));

    /** Ningún veto — el caso de los 20 subtipos que no necesitan uno. */
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
     * Orden de preferencia de marca para el combo de suplementos (confirmado por
     * el usuario): ENA y STAR ya tienen stock real en el catálogo; BCC ("La Roja")
     * no tiene productos hoy, pero queda en la lista para entrar sola el día que
     * se scrapee esa marca, sin tocar este código de nuevo.
     */
    private static final List<String> SUPLEMENTO_MARCA_PRIORIDAD = List.of("ENA", "STAR", "BCC");

    /** All canonical supplement categories assigned by NormalizerService. */
    private static final Set<String> CATEGORIAS_SUPLEMENTO = Set.of(
            "Suplemento", "Proteína", "Creatina", "Colágeno", "Magnesio",
            "Pre-Workout", "BCAA", "Vitaminas", "Quemadores", "Gainer", "Alimentos",
            // Nutrition subcategories the classifier can assign directly — must be
            // whitelisted here or the product is filtered out before subtype matching.
            "Snack Proteico", "Pancake Proteico", "Barra Proteica"
    );

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
        if (productos == null) productos = List.of();
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
            if (p.nombre() == null || p.nombre().isBlank()) continue;
            String nombreNormalizado = normalizar(p.nombre());
            for (SubtipoCompilado subtipo : SUBTIPOS_POR_PRECEDENCIA) {
                if (subtipo.matches(nombreNormalizado)) {
                    porTipo.computeIfAbsent(subtipo.tipo(), k -> new ArrayList<>()).add(p);
                    break;
                }
            }
        }
        return porTipo;
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
