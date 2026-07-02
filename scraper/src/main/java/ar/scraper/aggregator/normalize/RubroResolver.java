package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

/**
 * Determina el rubro (tecnologia/suplementos/indumentaria) de un producto.
 *
 * <p>Extraído verbatim del bloque {@code rubro}/{@code catEsTextil}/
 * {@code catEsSuppl} en {@code NormalizerService.normalizarProducto} (Work
 * Unit 8 de la modularización SOLID del aggregator) — pure relocation, no
 * behavior change. Usa {@link SiteClassification} y {@link CategoryGroups}.</p>
 */
@Component
public class RubroResolver {

    /**
     * Resuelve el rubro: forzar por sitio, luego por categoría, luego usar
     * el existente.
     *
     * @param sitioKey        clave normalizada del sitio (ver {@link SiteClassification#sitioKey(String)})
     * @param cat             categoría ya resuelta por {@code CategoryClassifier}
     * @param rubroExistente  rubro previo del producto (puede ser {@code null} o vacío)
     */
    public String resolver(String sitioKey, String cat, String rubroExistente) {
        boolean catEsTextil = CategoryGroups.esIndumentariaOCalzado(cat);
        boolean catEsSuppl  = CategoryGroups.esCategoriaSuplemento(cat);

        if (SiteClassification.TECH_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]","")))
                && !catEsTextil) {
            return "tecnologia";
        } else if (catEsSuppl) {
            return "suplementos";
        } else if (SiteClassification.SUPPL_SITIOS.stream().anyMatch(s -> sitioKey.contains(s.replaceAll("[^a-z0-9]","")))
                   && !catEsTextil) {
            return "suplementos";
        } else if (catEsTextil) {
            return "indumentaria";
        } else if (rubroExistente != null && !rubroExistente.isBlank()) {
            return rubroExistente;
        } else {
            return "indumentaria";
        }
    }
}
