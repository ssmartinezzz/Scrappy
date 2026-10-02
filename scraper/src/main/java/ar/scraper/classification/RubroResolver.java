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
        boolean catEsTextil = CategoryGroups.esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = CategoryGroups.esCategoriaSuplemento(cat);
        String rubroForzado = siteRegistry.rubroForzado(sitioKey);

        if ("tecnologia".equals(rubroForzado) && !catEsTextil) {
            return "tecnologia";
        } else if ("oficina".equals(rubroForzado) && !catEsTextil && !catEsSuppl) {
            return "oficina";
        } else if (catEsSuppl) {
            return "suplementos";
        } else if ("suplementos".equals(rubroForzado) && !catEsTextil) {
            return "suplementos";
        } else if (catEsTextil) {
            return "indumentaria";
        } else if (StringUtils.isNotBlank(rubroExistente)) {
            return rubroExistente;
        } else {
            return "indumentaria";
        }
    }
}
