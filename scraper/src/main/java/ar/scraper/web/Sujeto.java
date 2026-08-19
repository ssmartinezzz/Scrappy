package ar.scraper.web;

import ar.scraper.identity.ActorResolver;

import java.util.UUID;

/**
 * Resolves the owner for an owner-scoped endpoint, or refuses.
 *
 * <p>Every personal surface needs the same three lines, and the failure branch
 * is the one that matters: with no subject there is <b>no safe query to run</b>.
 * Widening to "everybody" is the leak the scoping exists to prevent, and
 * narrowing to "nobody" would silently show an empty list to a user whose data
 * is fine. The only honest answer is to refuse, so this throws and the caller
 * turns it into a 401.</p>
 *
 * <p>In practice the filter chain already guarantees a subject on every route
 * that calls this — these are all AUTHENTICATED rows. This is the belt to that
 * pair of braces: if a future route is added to the wrong band, it fails closed
 * and loudly rather than serving somebody else's rows.</p>
 */
final class Sujeto {

    private Sujeto() {
    }

    /** Thrown when an owner-scoped surface is reached with no authenticated subject. */
    static final class SinSujeto extends RuntimeException {
        SinSujeto() {
            super("La operación es personal y no hay un sujeto autenticado.");
        }
    }

    static UUID de(ActorResolver actorResolver) {
        return actorResolver.currentUsuarioId().orElseThrow(SinSujeto::new);
    }
}
