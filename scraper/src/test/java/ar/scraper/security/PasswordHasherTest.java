package ar.scraper.security;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 1 — Argon2id hashing.
 *
 * <p>The round-trip is the boring half. The two assertions that matter are that
 * the same password hashed twice produces <b>different</b> output — proving a
 * per-hash salt, without which a stolen table becomes a rainbow-table lookup —
 * and that the plaintext never appears anywhere inside the encoded form.</p>
 */
@Epic("Security")
@Feature("Password storage")
@Story("Argon2id hash/verify round-trip with a per-hash salt")
@DisplayName("PasswordHasher — Argon2id")
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    @DisplayName("a password verifies against its own hash")
    void hashThenVerifyRoundTrips() {
        String hash = hasher.hash("correcta-y-larga-1234");
        assertThat(hasher.verify("correcta-y-larga-1234", hash)).isTrue();
    }

    @Test
    @DisplayName("a wrong password does not verify")
    void wrongPasswordDoesNotVerify() {
        String hash = hasher.hash("correcta-y-larga-1234");
        assertThat(hasher.verify("incorrecta", hash)).isFalse();
    }

    @Test
    @DisplayName("the encoded hash announces itself as argon2id")
    void theEncodedFormIsArgon2id() {
        assertThat(hasher.hash("cualquiera-1234")).startsWith("$argon2id$");
    }

    @Test
    @DisplayName("the same password hashed twice yields different output — there is a salt")
    void thereIsAPerHashSalt() {
        String primera = hasher.hash("misma-password-1234");
        String segunda = hasher.hash("misma-password-1234");
        assertThat(primera)
                .as("identical output would mean no salt, and a stolen table would be a lookup away")
                .isNotEqualTo(segunda);
        assertThat(hasher.verify("misma-password-1234", primera)).isTrue();
        assertThat(hasher.verify("misma-password-1234", segunda)).isTrue();
    }

    @Test
    @DisplayName("the plaintext never appears inside the encoded hash")
    void theEncodedFormNeverContainsThePlaintext() {
        assertThat(hasher.hash("plaintext-reconocible-1234"))
                .doesNotContain("plaintext-reconocible-1234");
    }

    @Test
    @DisplayName("verify against a malformed hash is false, never an exception")
    void verifyAgainstGarbageIsFalseNotAnException() {
        assertThat(hasher.verify("cualquiera", "no-es-un-hash")).isFalse();
        assertThat(hasher.verify("cualquiera", "")).isFalse();
    }
}
