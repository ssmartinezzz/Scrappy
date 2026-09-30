package ar.scraper.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Declaring any DataSource bean makes Boot's DataSourceAutoConfiguration back off, so the
 * Hikari pool is declared here with the same binding Boot used ({@code spring.datasource.*}
 * plus {@code spring.datasource.hikari.*}).
 *
 * <p>Repositories get the {@link TransactionAwareDataSourceProxy}: inside a
 * {@code @Transactional} method its {@code getConnection()} returns the transaction's
 * connection, outside one it behaves like the pool. The transaction manager and Flyway
 * work on the raw pool.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@EnableTransactionManagement
class TransactionConfig {

    @Bean
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource hikariPool(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    PlatformTransactionManager transactionManager(@Qualifier("hikariPool") HikariDataSource pool) {
        return new DataSourceTransactionManager(pool);
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("hikariPool") HikariDataSource pool) {
        return new TransactionAwareDataSourceProxy(pool);
    }
}
