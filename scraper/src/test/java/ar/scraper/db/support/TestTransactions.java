package ar.scraper.db.support;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.core.annotation.AnnotatedElementUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * {@code @Transactional} only works on a bean Spring proxies. Tests that build an adapter
 * with {@code new} get no proxy, and the annotation is silently inert. Wrapping here gives
 * the same boundary production has: a {@link DataSourceTransactionManager} on the raw data
 * source, repositories reading connections through a {@link TransactionAwareDataSourceProxy}.
 */
public final class TestTransactions {

    private TestTransactions() {}

    public static PlatformTransactionManager manager(DataSource raw) {
        return new DataSourceTransactionManager(raw);
    }

    public static DataSource aware(DataSource raw) {
        return new TransactionAwareDataSourceProxy(raw);
    }

    /** Returns {@code target} itself when nothing on it is transactional. */
    @SuppressWarnings("unchecked")
    public static <T> T proxy(T target, PlatformTransactionManager manager) {
        if (!isTransactional(target.getClass())) return target;
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor((TransactionManager) manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy(target.getClass().getClassLoader());
    }

    public static boolean isTransactional(Class<?> type) {
        if (AnnotatedElementUtils.hasAnnotation(type, Transactional.class)) return true;
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(TestTransactions::isTransactional);
    }

    private static boolean isTransactional(Method m) {
        return AnnotatedElementUtils.hasAnnotation(m, Transactional.class);
    }
}
