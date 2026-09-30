package ar.scraper.pages;

/**
 * Morashop's whole catalogue is reached through those leaves, so an empty set is not a small store
 * — it means the landing markup changed and the site is about to return zero products.
 */
public class MorashopDiscoveryException extends RuntimeException {
    public MorashopDiscoveryException(String seccionUrl) {
        super("Morashop: no se descubrio ninguna categoria hoja bajo " + seccionUrl
                + " — probablemente cambio el markup del landing. El catalogo entero "
                + "cuelga de esas hojas, asi que esto habria dado 0 productos en silencio.");
    }
}
