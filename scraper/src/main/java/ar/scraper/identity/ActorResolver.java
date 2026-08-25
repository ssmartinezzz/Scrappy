package ar.scraper.identity;

import ar.scraper.security.AuthenticatedSubject;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Sole seam through which any component resolves "who is acting" (the human
 * or process performing a write), per architecture/session-readiness
 * (obs #773). {@link #current()} never returns {@code null} and never returns
 * blank, so an audit row can always name somebody.
 *
 * <p><b>This is the body-only change that seam was designed for.</b> It now
 * reads the authenticated principal that {@link ar.scraper.security.JwtAuthFilter}
 * put in the security context. Not one call site elsewhere changed, which is
 * the whole return on having routed every "who is acting" question through
 * here in the first place.</p>
 *
 * <p>{@code "local"} survives as the explicit fallback for work with no request
 * behind it — startup seeding, a scheduled cronjob, anything running off a
 * thread that never saw an HTTP call. Returning it is a real answer, not a
 * failure: those writes genuinely are the system's own.</p>
 *
 * <p>This class performs no role check, no permission check, and no
 * authorization decision — it records identity, it does not verify it.</p>
 */
@Component
public final class ActorResolver {

    private static final String LOCAL_ACTOR = "local";

    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || auth.getName().isBlank()) {
            return LOCAL_ACTOR;
        }
        return auth.getName();
    }

    /**
     * The subject's id, for owner-scoped queries.
     *
     * <p>Returns {@link Optional} rather than a fallback on purpose. {@link #current()}
     * can sensibly answer {@code "local"} for system work, because an audit row
     * saying "the system did it" is true and useful. A <i>scoped query</i> has no
     * equivalent: there is no id that means "everybody", and inventing one would be
     * the leak this whole mechanism exists to prevent. A caller with no subject must
     * refuse to answer, not widen the query.</p>
     */
    public Optional<UUID> currentUsuarioId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof AuthenticatedSubject sujeto) {
            return Optional.of(sujeto.id());
        }
        return Optional.empty();
    }
}
