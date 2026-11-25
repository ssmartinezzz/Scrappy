package ar.scraper.classification;

import java.util.Optional;

/** One step of {@link RubroResolver}'s ordered list: a rubro when it decides, empty to let the next rule try. */
@FunctionalInterface
interface RubroRule {

    record Entrada(SiteRegistry.Sitio sitio, String categoria, String subCategoria, String rubroExistente) {}

    Optional<String> aplicar(Entrada entrada);
}
