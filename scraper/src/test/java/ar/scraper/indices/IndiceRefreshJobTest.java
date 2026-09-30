package ar.scraper.indices;

import ar.scraper.config.IndiceRefreshRunner;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;

class IndiceRefreshJobTest {

    @Test
    void bootHookRunsAfterFlywayAsAnApplicationRunner() throws Exception {
        IndiceService service = mock(IndiceService.class);
        IndiceRefreshRunner runner = new IndiceRefreshRunner(new IndiceRefreshJob(service));

        assertThat(runner).isInstanceOf(ApplicationRunner.class);
        runner.run(null);

        var order = inOrder(service);
        order.verify(service).cargarDesdeDB();
        order.verify(service, timeout(2000)).refrescar();
    }

    @Test
    void nothingInTheAreaTouchesTheDatabaseFromPostConstruct() {
        for (Class<?> type : new Class<?>[]{IndiceService.class, IndiceRefreshJob.class, IndiceRefreshRunner.class}) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(PostConstruct.class))
                    .map(Method::getName))
                    .as("%s must not run DB work before Flyway has migrated", type.getSimpleName())
                    .isEmpty();
        }
    }
}
