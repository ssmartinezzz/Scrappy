package ar.scraper.classification;

import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

/**
 * {@code oficina} entra como cuarto rubro, con una rama que es espejo EXACTO de la de
 * {@code tecnologia}, guard {@code !catEsTextil} incluido.
 */
@RequiredArgsConstructor
public class RubroResolver {

    private final SiteRegistry siteRegistry;

    public String resolver(String sitioKey, String cat, String rubroExistente) {
        return resolver(sitioKey, cat, rubroExistente, "");
    }

    /**
     * {@code sub} is the sub-category of {@code cat}; a (categoria, sub) pair with an entry in
     * {@link CategoryGroups#rubroDeSubcategoria} decides the rubro, but only on a site with no
     * {@code rubro_forzado}: every forced branch above it returns first, so forced sites behave as before.
     * A blank or unmapped sub changes nothing.
     */
    public String resolver(String sitioKey, String cat, String rubroExistente, String sub) {
        boolean catEsTextil = CategoryGroups.esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = CategoryGroups.esCategoriaSuplemento(cat);
        boolean catEsConstr = CategoryGroups.esCategoriaConstruccion(cat);
        boolean catEsHogar  = CategoryGroups.esCategoriaHogar(cat);
        SiteRegistry.Sitio sitio = siteRegistry.porKey(sitioKey);
        String rubroForzado = sitio.rubroForzado();
        String rubroPorDefecto = sitio.rubroPorDefecto();
        String rubroDeSub = CategoryGroups.rubroDeSubcategoria(cat, sub);

        if ("tecnologia".equals(rubroForzado) && !catEsTextil) {
            return "tecnologia";
        } else if ("oficina".equals(rubroForzado) && !catEsTextil && !catEsSuppl) {
            return "oficina";
        } else if (CategoryGroups.esCategoriaBelleza(cat)) {
            return "belleza";
        } else if (catEsConstr) {
            return "construccion";
        } else if (catEsHogar) {
            return "hogar";
        } else if (catEsSuppl) {
            return "suplementos";
        } else if ("suplementos".equals(rubroForzado) && !catEsTextil) {
            return "suplementos";
        } else if ("construccion".equals(rubroForzado) && !catEsTextil) {
            return "construccion";
        } else if ("hogar".equals(rubroForzado) && !catEsTextil) {
            return "hogar";
        } else if (catEsTextil) {
            return "indumentaria";
        } else if (StringUtils.isBlank(rubroForzado) && !rubroDeSub.isEmpty()) {
            return rubroDeSub;
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.categoriasOficina().contains(cat)) {
            return "oficina";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaDeporte(cat)) {
            return "deporte";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaLibros(cat)) {
            return "libros";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaJuguetes(cat)) {
            return "juguetes";
        } else if (StringUtils.isBlank(rubroForzado) && CategoryGroups.esCategoriaTech(cat)) {
            return "tecnologia";
        } else if (StringUtils.isNotBlank(rubroPorDefecto)) {
            return rubroPorDefecto;
        } else if (StringUtils.isNotBlank(rubroExistente)) {
            return rubroExistente;
        } else {
            return "indumentaria";
        }
    }
}
