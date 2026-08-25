package ar.scraper.security;

import java.util.UUID;

/**
 * Who is making this request, as the security context carries it.
 *
 * <p>Both halves are needed and neither substitutes for the other: the
 * {@code id} is what every owner-scoped query binds against, and the
 * {@code username} is what the audit trail records. Storing only the username
 * would force a lookup per scoped query; storing only the id would put an
 * opaque UUID in audit rows nobody can read.</p>
 */
public record AuthenticatedSubject(UUID id, String username) {

    @Override
    public String toString() {
        // Spring reads this for Authentication#getName. Keep it the username, so
        // the actor string stays readable rather than becoming a UUID.
        return username;
    }
}
