package ar.scraper.config;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.TestDatabaseServices;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestTransactions;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Transactional} is silently inert on an object built with {@code new}, on a private
 * or self-invoked method, and on a class Spring does not manage. These tests fail loudly
 * instead of leaving a unit half-written.
 */
@Epic("Persistence")
@Feature("Transactions")
@Story("Transactional adapters are proxied")
@DisplayName("@Transactional adapters — declared correctly and proxied in context and fixture")
class TransactionalBeansTest extends PostgresTestBase {

    private static List<Class<?>> classesWithTransactionalMethods() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition d : scanner.findCandidateComponents("ar.scraper")) {
            Class<?> type = Class.forName(d.getBeanClassName());
            boolean production = !type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes");
            if (production && TestTransactions.isTransactional(type)) found.add(type);
        }
        return found;
    }

    @Test
    @DisplayName("every transactional class is a Spring bean, open to proxying, with public methods that roll back on checked exceptions")
    void transactionalDeclarationsCanWork() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Class<?> type : classesWithTransactionalMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(type, Component.class)) problems.add(type + " is not a bean");
            if (Modifier.isFinal(type.getModifiers())) problems.add(type + " is final");
            for (Method m : type.getDeclaredMethods()) {
                Transactional tx = AnnotatedElementUtils.findMergedAnnotation(m, Transactional.class);
                if (tx == null) continue;
                if (!Modifier.isPublic(m.getModifiers())) problems.add(m + " is not public");
                if (Modifier.isFinal(m.getModifiers())) problems.add(m + " is final");
                if (!List.of(tx.rollbackFor()).contains(Exception.class)) {
                    problems.add(m + " does not declare rollbackFor = Exception.class");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("in a context wired with the real TransactionConfig, each transactional bean is an AOP proxy")
    void contextBeansAreProxies() throws Exception {
        SimpleDriverDataSource simple = (SimpleDriverDataSource) dataSource();
        List<Class<?>> types = classesWithTransactionalMethods();
        assertThat(types).as("classes with @Transactional found").isNotEmpty();
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfigProbe.class)
                .withBean(SiteRegistry.class, () -> SiteRegistry.forTesting(Map.of()))
                .withBean(RubroResolver.class, () -> new RubroResolver(SiteRegistry.forTesting(Map.of())))
                // collaborators of the transactional beans that are not transactional themselves
                .withBean(ar.scraper.db.PasswordResetRepository.class)
                .withBean(ar.scraper.db.RefreshTokenRepository.class)
                .withBean(ar.scraper.security.PasswordHasher.class)
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(ar.scraper.security.reset.ResetRateLimiter.class)
                .withBean(ar.scraper.security.reset.PasswordResetChannel.class, () -> (destino, enlace) -> { })
                .withPropertyValues(
                        "spring.datasource.url=" + simple.getUrl(),
                        "spring.datasource.username=" + simple.getUsername(),
                        "spring.datasource.password=" + simple.getPassword(),
                        "password.reset.link-base=http://localhost");
        for (Class<?> type : types) {
            @SuppressWarnings("unchecked") Class<Object> bean = (Class<Object>) type;
            runner = runner.withBean(bean);
        }
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            for (Class<?> type : types) {
                assertThat(AopUtils.isAopProxy(ctx.getBean(type))).as("%s is a proxy", type.getSimpleName()).isTrue();
            }
        });
    }

    @Test
    @DisplayName("the hand-wired test fixture proxies every port whose class is transactional")
    void fixturePortsAreProxies() throws Exception {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        List<String> notProxied = new ArrayList<>();
        int checked = 0;
        for (Field f : DatabaseService.class.getDeclaredFields()) {
            if (!f.getName().endsWith("Port")) continue;
            f.setAccessible(true);
            Object port = f.get(db);
            Class<?> target = AopUtils.getTargetClass(port);
            if (TestTransactions.isTransactional(target)) {
                checked++;
                if (!AopUtils.isAopProxy(port)) notProxied.add(f.getName());
            }
        }
        assertThat(checked).isGreaterThan(0);
        assertThat(notProxied).isEmpty();
    }

    @Configuration
    @Import(TransactionConfig.class)
    static class TransactionConfigProbe {}
}
