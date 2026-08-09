package ar.scraper.web;

import ar.scraper.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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

    private record SubtipoSuplemento(String tipo, String[] keywords) { }

    /**
     * Subtipos del combo de suplementos, en el orden en que se arma el combo.
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
                    // Accented + unaccented forms — matchesSubtipo does not strip accents.
                    "cupcake", "pudding", "budin", "budín", "omelette", "omelet"
            }),
            new SubtipoSuplemento("Creatina", new String[]{"creatina", "creatine", "monohidrato"}),
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
        if (productos == null) productos = List.of();
        List<Product> suplementos = productos.stream()
                .filter(p -> CATEGORIAS_SUPLEMENTO.contains(p.categoria()))
                .collect(Collectors.toList());

        List<OutfitService.SupplementPick> combo = new ArrayList<>();
        double remainingBudget = presupuesto;
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            List<Product> candidatos = suplementos.stream()
                    .filter(p -> matchesSubtipo(p.nombre(), subtipo.keywords()))
                    .collect(Collectors.toList());
            if (candidatos.isEmpty()) continue;

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = candidatos.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = elegirPorMarcaPrioridad(affordable);
                } else {
                    // Ningún candidato cabe — elige el más barato (no bloquea el slot)
                    elegido = candidatos.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElse(candidatos.get(0));
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = elegirPorMarcaPrioridad(candidatos);
            }
            combo.add(toSupplementPick(subtipo.tipo(), elegido));
        }
        return combo;
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

        List<OutfitService.SupplementPick> combo = new ArrayList<>();
        double remainingBudget = presupuesto;
        for (SubtipoSuplemento subtipo : SUPLEMENTO_SUBTIPOS) {
            if (tipos != null && !tipos.isEmpty() && !tipos.contains(subtipo.tipo())) continue;
            List<Product> candidatos = suplementos.stream()
                    .filter(p -> matchesSubtipo(p.nombre(), subtipo.keywords()))
                    .collect(Collectors.toList());
            if (candidatos.isEmpty()) continue;

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = candidatos.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = elegirPorMarcaPrioridad(affordable);
                } else {
                    elegido = candidatos.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElse(candidatos.get(0));
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = elegirPorMarcaPrioridad(candidatos);
            }
            combo.add(toSupplementPick(subtipo.tipo(), elegido));
        }
        return combo;
    }

    private boolean matchesSubtipo(String nombre, String[] keywords) {
        if (nombre == null || nombre.isBlank()) return false;
        String t = nombre.toLowerCase();
        for (String kw : keywords) {
            if (t.contains(kw)) return true;
        }
        return false;
    }

    private Product elegirPorMarcaPrioridad(List<Product> candidatos) {
        for (String marca : SUPLEMENTO_MARCA_PRIORIDAD) {
            for (Product p : candidatos) {
                if (marca.equalsIgnoreCase(p.marca())) return p;
            }
        }
        return candidatos.get(ThreadLocalRandom.current().nextInt(candidatos.size()));
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
