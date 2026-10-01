package ar.scraper.pcs;

/**
 * Unlike {@link Gama}, there is no abstention sentinel here: a build always has a concrete
 * {@code Uso}, never "couldn't read one off a name" — this is what the CALLER asked for, not
 * something parsed off a product.
 */
public enum Uso {
    GAMING, HOMELAB
}
