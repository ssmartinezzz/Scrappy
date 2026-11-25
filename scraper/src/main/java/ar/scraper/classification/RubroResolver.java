package ar.scraper.classification;

import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * Resolves the rubro of a product as an ordered list of {@link RubroRule}s over the taxonomy tables
 * ({@link RubroCatalogo}); the first rule that answers wins. A new rubro is rows in a migration, not a new branch here.
 */
public class RubroResolver {

    private static final String RUBRO_FINAL = "indumentaria";

    private final SiteRegistry siteRegistry;
    private final RubroCatalogo catalogo;
    private final List<RubroRule> reglas;

    public RubroResolver(SiteRegistry siteRegistry, RubroCatalogo catalogo) {
        this.siteRegistry = siteRegistry;
        this.catalogo = catalogo;
        this.reglas = List.of(
                this::categoriaQuePisaAlSitio,
                this::sitioForzado,
                this::subCategoria,
                this::categoria,
                this::rubroPorDefectoDelSitio,
                this::rubroGuardado);
    }

    public String resolver(String sitioKey, String cat, String rubroExistente) {
        return resolver(sitioKey, cat, rubroExistente, "");
    }

    /** {@code sub} is the sub-category of {@code cat}. */
    public String resolver(String sitioKey, String cat, String rubroExistente, String sub) {
        RubroRule.Entrada entrada = new RubroRule.Entrada(siteRegistry.porKey(sitioKey), cat, sub, rubroExistente);
        return reglas.stream()
                .map(regla -> regla.aplicar(entrada))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(RUBRO_FINAL);
    }

    /** The most specific row of the category decides, and only when it says it beats a forced site. */
    private Optional<String> categoriaQuePisaAlSitio(RubroRule.Entrada e) {
        return filaMasEspecifica(e)
                .filter(RubroCatalogo.CategoriaRubro::pisaForzado)
                .map(RubroCatalogo.CategoriaRubro::rubro);
    }

    /** A forced site keeps the product unless the category's rubro weighs more than the site's forced one. */
    private Optional<String> sitioForzado(RubroRule.Entrada e) {
        String forzado = e.sitio().rubroForzado();
        if (StringUtils.isBlank(forzado)) return Optional.empty();
        Optional<RubroCatalogo.CategoriaRubro> fila = catalogo.fila(e.categoria());
        if (fila.isEmpty() || pesoForzado(forzado) >= pesoCategoria(fila.get().rubro())) return Optional.of(forzado);
        return Optional.of(fila.get().rubro());
    }

    /** A sub row decides only on a site with no forced rubro. */
    private Optional<String> subCategoria(RubroRule.Entrada e) {
        if (StringUtils.isNotBlank(e.sitio().rubroForzado()) || StringUtils.isEmpty(e.subCategoria())) {
            return Optional.empty();
        }
        return catalogo.fila(e.categoria(), e.subCategoria()).map(RubroCatalogo.CategoriaRubro::rubro);
    }

    private Optional<String> categoria(RubroRule.Entrada e) {
        return catalogo.fila(e.categoria()).map(RubroCatalogo.CategoriaRubro::rubro);
    }

    private Optional<String> rubroPorDefectoDelSitio(RubroRule.Entrada e) {
        String porDefecto = e.sitio().rubroPorDefecto();
        return StringUtils.isNotBlank(porDefecto) ? Optional.of(porDefecto) : Optional.empty();
    }

    private Optional<String> rubroGuardado(RubroRule.Entrada e) {
        return StringUtils.isNotBlank(e.rubroExistente()) ? Optional.of(e.rubroExistente()) : Optional.empty();
    }

    private Optional<RubroCatalogo.CategoriaRubro> filaMasEspecifica(RubroRule.Entrada e) {
        Optional<RubroCatalogo.CategoriaRubro> conSub = catalogo.fila(e.categoria(), e.subCategoria());
        return conSub.isPresent() ? conSub : catalogo.fila(e.categoria());
    }

    private int pesoForzado(String rubro) {
        return catalogo.rubro(rubro).map(RubroCatalogo.Rubro::pesoForzado).orElse(0);
    }

    private int pesoCategoria(String rubro) {
        return catalogo.rubro(rubro).map(RubroCatalogo.Rubro::pesoCategoria).orElse(0);
    }
}
