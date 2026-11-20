package ar.scraper.pages;

/** An Odoo shop listing answered something that is not product cards. */
public class OdooPayloadException extends RuntimeException {
    public OdooPayloadException(String detalle) {
        super("Odoo: listado inesperado — " + detalle);
    }
}
