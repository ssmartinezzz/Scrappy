package ar.scraper.pages;

/** The listing endpoint answered something that is not PrestaShop's listing JSON (HTML, empty, no {@code products}). */
public class PrestashopPayloadException extends RuntimeException {
    public PrestashopPayloadException(String detalle) {
        super("PrestaShop: listing JSON inesperado — " + detalle);
    }
}
