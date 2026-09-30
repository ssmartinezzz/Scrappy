package ar.scraper.security;

import java.util.UUID;

/**
 * Who is making this request, as the security context carries it. Storing only the username would
 * force a lookup per scoped query; storing only the id would put an opaque UUID in audit rows
 * nobody can read.
 */
public record AuthenticatedSubject(UUID id, String username) {

    @Override
    public String toString() {
        // Keep it the username, so the actor string stays readable rather than becoming a UUID.
        return username;
    }
}
