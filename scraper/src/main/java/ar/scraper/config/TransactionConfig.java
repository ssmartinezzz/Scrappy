package ar.scraper.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
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
 * Class-based proxies on purpose: an explicit {@code @EnableTransactionManagement} makes Boot's
 * auto-configuration back off, and the default would be JDK proxies.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
@EnableTransactionManagement(proxyTargetClass = true)
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
