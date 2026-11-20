package ar.scraper.pages;

/** The Salesforce Commerce Cloud grid answered something that is not product tiles, or fewer than it announced. */
public class SfccPayloadException extends RuntimeException {
    public SfccPayloadException(String detalle) {
        super("SFCC: listado inesperado — " + detalle);
    }
}
