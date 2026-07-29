package ar.scraper.db.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 0 (task 0.3).
 *
 * <p>Proves the anti-silent-skip guard as pure functions, independent of an
 * actual Docker/Postgres environment: {@link PostgresTestBase#requireDb()}
 * reads the {@code scraper.test.requireDb} system property, and
 * {@link PostgresTestBase#describeUnavailable} chains both failure causes
 * into one message instead of swallowing the Docker failure silently.</p>
 */
class PostgresTestBaseGuardTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("scraper.test.requireDb");
    }

    @Test
    void requireDbDefaultsToFalseWhenPropertyAbsent() {
        System.clearProperty("scraper.test.requireDb");

        assertThat(PostgresTestBase.requireDb()).isFalse();
    }

    @Test
    void requireDbIsTrueWhenPropertySetToTrue() {
        System.setProperty("scraper.test.requireDb", "true");

        assertThat(PostgresTestBase.requireDb()).isTrue();
    }

    @Test
    void requireDbIsFalseWhenPropertySetToSomethingElse() {
        System.setProperty("scraper.test.requireDb", "yes");

        assertThat(PostgresTestBase.requireDb()).isFalse();
    }

    @Test
    void describeUnavailableChainsTheDockerFailureMessageInsteadOfSwallowingIt() {
        Throwable dockerFailure = new IllegalStateException("Docker not available");

        String reason = PostgresTestBase.describeUnavailable(dockerFailure, null);

        assertThat(reason).contains("Docker not available");
    }

    @Test
    void describeUnavailableChainsBothFailuresWhenPortableLocalAlsoFailed() {
        Throwable dockerFailure = new IllegalStateException("Docker not available");
        Throwable portableFailure = new IllegalStateException("no _tools/pgsql found");

        String reason = PostgresTestBase.describeUnavailable(dockerFailure, portableFailure);

        assertThat(reason).contains("Docker not available");
        assertThat(reason).contains("no _tools/pgsql found");
    }
}
