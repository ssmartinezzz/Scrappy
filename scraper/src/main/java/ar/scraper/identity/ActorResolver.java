package ar.scraper.identity;

import org.springframework.stereotype.Component;

/**
 * Sole seam through which any component resolves "who is acting" (the human
 * or process performing a write), per architecture/session-readiness
 * (obs #773). The project has no authentication today, so {@link #current()}
 * returns a well-known constant — never {@code null}, never blank — instead
 * of leaving the actor uncaptured.
 *
 * <p>When session management lands, only this class's body changes: read
 * the session principal, keep {@code "local"} as the explicit
 * system/unauthenticated fallback. No call site elsewhere changes.</p>
 *
 * <p>This class performs no role check, no permission check, and no
 * authorization decision — it records identity, it does not verify it.</p>
 */
@Component
public final class ActorResolver {

    private static final String LOCAL_ACTOR = "local";

    public String current() {
        return LOCAL_ACTOR;
    }
}
