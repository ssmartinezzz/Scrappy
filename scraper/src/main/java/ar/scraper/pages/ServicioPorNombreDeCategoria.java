package ar.scraper.pages;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Flags category nodes whose products are not goods: installation and assembly services, and gift
 * cards (never a product on any site). A few sites also carry an exact-name node that is not a
 * product there (see {@link #NODOS_EXCLUIDOS_POR_SITIO}); those stay out of the shared rule so a
 * same-named leaf on another site is untouched.
 */
public final class ServicioPorNombreDeCategoria implements CriterioDeServicio {

    private static final Pattern SERVICIO = Pattern.compile(
            "^\\s*(instalaci[oó]n(es)?|armados?|servicios?|colocaci[oó]n"
                    + "|gift\\s*cards?|tarjetas?\\s+de\\s+regalo)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Lower-cased site name to the exact (lower-cased, trimmed) node names that are not products there. */
    private static final Map<String, Set<String>> NODOS_EXCLUIDOS_POR_SITIO = Map.of(
            "elmundodeljuguete", Set.of("bolsas"));

    private final Set<String> nodosExcluidos;

    public ServicioPorNombreDeCategoria() {
        this(Set.of());
    }

    private ServicioPorNombreDeCategoria(Set<String> nodosExcluidos) {
        this.nodosExcluidos = nodosExcluidos;
    }

    /** The shared rule plus whatever exact-name nodes {@code sitio} is known to carry that are not products. */
    public static ServicioPorNombreDeCategoria para(String sitio) {
        String clave = sitio == null ? "" : sitio.toLowerCase(Locale.ROOT);
        return new ServicioPorNombreDeCategoria(NODOS_EXCLUIDOS_POR_SITIO.getOrDefault(clave, Set.of()));
    }

    @Override
    public boolean esServicio(String nombreCategoria) {
        if (nombreCategoria == null) return false;
        return SERVICIO.matcher(nombreCategoria).find()
                || nodosExcluidos.contains(nombreCategoria.strip().toLowerCase(Locale.ROOT));
    }
}
