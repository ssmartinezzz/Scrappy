package ar.scraper.pages;

/** The products API answered something that is not its {@code {data:{data[], count_datatotal}}} payload, or fewer rows than it announced. */
public class KevingstonPayloadException extends RuntimeException {
    public KevingstonPayloadException(String detalle) {
        super("Kevingston: products JSON inesperado — " + detalle);
    }
}
