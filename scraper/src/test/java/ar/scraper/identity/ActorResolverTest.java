package ar.scraper.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 1.
 *
 * <p>{@link ActorResolver} is the ONE seam through which the acting identity
 * is resolved (architecture/session-readiness, obs #773). Today there is no
 * authentication, so it returns a well-known constant — but every consumer
 * calls {@code current()}, never reads an actor inline, so only this class
 * changes when sessions land.</p>
 */
class ActorResolverTest {

    private final ActorResolver resolver = new ActorResolver();

    @Test
    void currentIsNeverNull() {
        assertThat(resolver.current()).isNotNull();
    }

    @Test
    void currentIsNeverBlank() {
        assertThat(resolver.current()).isNotBlank();
    }

    @Test
    void currentIsTheLocalConstantToday() {
        assertThat(resolver.current()).isEqualTo("local");
    }
}
