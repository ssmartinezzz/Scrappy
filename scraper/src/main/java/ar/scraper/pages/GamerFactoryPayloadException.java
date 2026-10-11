package ar.scraper.pages;

/** The search API answered something that is not its {@code {total, data[]}} payload, or fewer products than it announced. */
public class GamerFactoryPayloadException extends RuntimeException {
    public GamerFactoryPayloadException(String detalle) {
        super("Gamer Factory: search JSON inesperado — " + detalle);
    }
}
