package ar.scraper.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing.
 *
 * <p>Argon2id rather than bcrypt or PBKDF2 because it is memory-hard: an
 * attacker with GPUs cannot buy their way past it with parallelism alone, which
 * is the whole failure mode of the older functions. The encoded output carries
 * its own parameters and salt, so a future parameter change verifies existing
 * hashes unchanged — no migration, no flag day.</p>
 *
 * <p><b>Parameters: measured here, unmeasured where it counts.</b> The encoder
 * defaults are {@code m=16384} (16 MiB), {@code t=2}, {@code p=1}, and on this
 * Linux dev machine that costs <b>~22 ms to hash and ~22 ms to verify</b>
 * (20 iterations after warmup, 2026-09-22: median 22.0 / 21.3 ms, max 26.8).
 * That is a sane login cost, so the defaults stand.</p>
 *
 * <p><b>This number was 76 ms until 2026-09-22</b>, measured the same way and on
 * the same machine. The re-measurement came from the other end: the perf suite
 * (see {@code tests/perf/}) clocked a whole {@code POST /api/auth/login} at
 * <b>43 ms p95</b> under ten concurrent users — a full HTTP round trip, JWT
 * issuing and DB reads included, which cannot be half the cost of the verify it
 * contains. Re-running the benchmark gave 22 ms. Which of the two runs was the
 * anomaly is not known; what is known is that the figure documented here has to
 * be one somebody can reproduce today. Every downstream claim derived from it
 * (the login rate limiter's "attempts per second", the timing-attack argument in
 * {@code AuthEndpoints}) was corrected with it.</p>
 *
 * <p>What has <b>not</b> been measured is the portable Windows target this
 * project actually installs onto, and that is the machine the number matters on
 * — hashing cost is memory-bandwidth bound and a low-end laptop can be several
 * times slower. The parameters are therefore left at the defaults rather than
 * raised toward the OWASP guidance: tuning upward against a number from the
 * fast machine is how login ends up slow on the slow one. Recorded as an open
 * follow-up, not guessed at.</p>
 *
 * <p>{@link #verify} never throws. A malformed stored hash is a data problem,
 * not a reason to hand the caller an exception in the middle of an
 * authentication decision — "does not verify" is the only safe answer, and it
 * is also the correct one.</p>
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }

    /**
     * @return {@code true} only when {@code plaintext} matches {@code encoded};
     *         {@code false} for a wrong password AND for a hash that cannot be
     *         parsed at all.
     */
    public boolean verify(String plaintext, String encoded) {
        if (plaintext == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(plaintext, encoded);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
