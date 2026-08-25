package ar.scraper.aggregator.normalize;

import org.springframework.stereotype.Component;

/**
 * Determina el rubro (tecnologia/suplementos/oficina/indumentaria) de un producto.
 *
 * <p>Extraído verbatim del bloque {@code rubro}/{@code catEsTextil}/
 * {@code catEsSuppl} en {@code NormalizerService.normalizarProducto} (Work
 * Unit 8 de la modularización SOLID del aggregator) — pure relocation, no
 * behavior change. Usa {@link SiteClassification} y {@link CategoryGroups}.</p>
 *
 * <p>close-1nf-and-3nf-foundation extension (design E1/E3): el sitio forzado
 * pasa de dos {@code stream().anyMatch(sitioKey::contains)} sobre
 * {@code TECH_SITIOS}/{@code SUPPL_SITIOS} — una comparación por substring que
 * podía enmascarar un sitio distinto ("foreverbstrd" contiene "forever") — a
 * un lookup de igualdad exacta sobre {@link SiteRegistry#rubroForzado}.
 * {@code RubroResolverEqualityParityTest} prueba que el resultado es idéntico
 * al del substring viejo sobre los 23 sitios reales × 81 categorías × 5
 * rubros previos — el conjunto de sitios donde había margen para que
 * cambiara.</p>
 *
 * <p>add-inpro-office-store: {@code oficina} entra como cuarto rubro, con una
 * rama que es espejo EXACTO de la de {@code tecnologia}, guard
 * {@code !catEsTextil} incluido. No es copiar y pegar: ese guard es lo que hace
 * que una tienda de rubro único que además vende una remera con su logo
 * clasifique esa remera como {@code indumentaria}. El sitio fuerza el rubro de
 * lo que le es propio, no de todo lo que toca.</p>
 *
 * <p>Y va por {@code rubro_forzado}, nunca por la categoría: una silla la vende
 * una tienda de oficina, pero que aparezca una silla en una tienda de ropa no
 * convierte a esa tienda en otra cosa. Por eso
 * {@code CategoryGroups.categoriasOficina()} existe para enumerar el
 * vocabulario y NO se consulta acá — al revés de
 * {@code esCategoriaSuplemento}, donde la categoría sí manda porque un
 * suplemento es un suplemento lo venda quien lo venda.</p>
 */
@Component
public class RubroResolver {

    private final SiteRegistry siteRegistry;

    public RubroResolver(SiteRegistry siteRegistry) {
        this.siteRegistry = siteRegistry;
    }

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
        } else if (rubroExistente != null && !rubroExistente.isBlank()) {
            return rubroExistente;
        } else {
            return "indumentaria";
        }
    }
}
