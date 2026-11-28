package ar.scraper.scrape;

import ar.scraper.classification.SiteClassification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The most recent finished run and the sites that ended in {@code ERROR}. */
public record UltimaCorrida(UUID uuid, Instant finishedAt, List<SitioFallido> fallidos) {

    /** Error text the run writes for a site still unfinished when the global budget ran out. */
    public static final String ERROR_DEADLINE_GLOBAL = "Deadline global";

    /** {@code sitio} is the registry name, the spelling {@code POST /api/scrape?sitios=} takes. */
    public record SitioFallido(String sitio, String error, boolean porDeadline) { }

    /**
     * Rewrites each failed site to the scrape registry's spelling. The {@code sitio} table can name a
     * site differently ("Under Armour" vs the registry's "Underarmour"), and the scrape selection
     * matches the registry name exactly, so the table's name would be silently skipped on retry.
     * Both spellings share the same {@link SiteClassification#sitioKey}; a site no longer in the
     * registry keeps its table name.
     */
    public UltimaCorrida conNombresDelRegistro(Collection<String> nombresRegistro) {
        Map<String, String> porKey = nombresRegistro.stream().collect(Collectors.toMap(
                SiteClassification::sitioKey, Function.identity(), (a, b) -> a));
        List<SitioFallido> renombrados = fallidos.stream()
                .map(f -> new SitioFallido(
                        porKey.getOrDefault(SiteClassification.sitioKey(f.sitio()), f.sitio()),
                        f.error(), f.porDeadline()))
                .toList();
        return new UltimaCorrida(uuid, finishedAt, renombrados);
    }
}
