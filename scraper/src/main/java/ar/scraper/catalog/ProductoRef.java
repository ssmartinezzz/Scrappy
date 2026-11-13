package ar.scraper.catalog;

/** A product by the two facts the promo read needs: its url (PK) and its site's display name. */
public record ProductoRef(String url, String sitio) {
}
