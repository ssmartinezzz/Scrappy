package ar.scraper.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link #current()} never returns {@code null} and never returns blank, so an audit row can always
 * name somebody.
 */
@Component
public final class ActorResolver {

    private static final String LOCAL_ACTOR = "local";

    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || StringUtils.isBlank(auth.getName())) {
            return LOCAL_ACTOR;
        }
        return auth.getName();
    }

    /**
     * {@link #current()} can sensibly answer {@code "local"} for system work, because an audit row
     * saying "the system did it" is true and useful.
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
