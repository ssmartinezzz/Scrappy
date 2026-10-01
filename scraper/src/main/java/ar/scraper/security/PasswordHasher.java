package ar.scraper.security;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing. Argon2id rather than bcrypt or PBKDF2 because it is memory-hard: an
 * attacker with GPUs cannot buy their way past it with parallelism alone, which is the whole
 * failure mode of the older functions.
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }

    public boolean verify(String plaintext, String encoded) {
        if (plaintext == null || StringUtils.isBlank(encoded)) {
            return false;
        }
        try {
            return encoder.matches(plaintext, encoded);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
