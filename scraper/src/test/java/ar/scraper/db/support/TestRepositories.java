package ar.scraper.db.support;

import ar.scraper.db.PasswordResetRepository;
import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;

import javax.sql.DataSource;

/**
 * The account repositories as Spring wires them: over a transaction-aware data source and behind
 * the transactional proxy. Built with {@code new}, {@code @Transactional} on them is inert.
 */
public final class TestRepositories {

    private TestRepositories() {}

    public static UsuarioRepository usuarios(DataSource raw) {
        return TestTransactions.proxy(new UsuarioRepository(TestTransactions.aware(raw)),
                TestTransactions.manager(raw));
    }

    public static PasswordResetRepository passwordResets(DataSource raw) {
        return new PasswordResetRepository(TestTransactions.aware(raw));
    }

    public static RefreshTokenRepository refreshTokens(DataSource raw) {
        return new RefreshTokenRepository(TestTransactions.aware(raw));
    }
}
