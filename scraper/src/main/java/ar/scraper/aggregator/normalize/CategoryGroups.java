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
        "Barra Proteica","Pancake Proteico","Snack Proteico"
    );

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
}
