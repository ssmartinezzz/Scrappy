package ar.scraper.pages;

/** A catalogue URL answered its first page with no product cards: the layout changed, or the URL died. */
public class MagentoPayloadException extends RuntimeException {
    public MagentoPayloadException(String catalogo) {
        super("Magento: 0 productos en la primera página de " + catalogo + " — el HTML del listado cambió o la URL ya no existe");
    }
}
