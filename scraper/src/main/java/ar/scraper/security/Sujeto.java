package ar.scraper.security;

import java.util.UUID;

/**
 * Widening to "everybody" is the leak the scoping exists to prevent, and narrowing to "nobody"
 * would silently show an empty list to a user whose data is fine.
 */
public final class Sujeto {

    private Sujeto() {
    }

    public static final class SinSujeto extends RuntimeException {
        SinSujeto() {
            super("La operación es personal y no hay un sujeto autenticado.");
        }
    }

    public static UUID de(ActorResolver actorResolver) {
        return actorResolver.currentUsuarioId().orElseThrow(SinSujeto::new);
    }
}
