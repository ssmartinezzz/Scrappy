package ar.scraper.pages;

/** Un challenge sin resolver: el run debe reportarlo, no caer en un "0 productos" silencioso. */
public class CloudflareBlockedException extends RuntimeException {

    public CloudflareBlockedException(String message) {
        super(message);
    }
}