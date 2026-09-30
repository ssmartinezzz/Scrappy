package ar.scraper.ml;

import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PythonRunnerStatusEventsTest {

    @Test
    void reservingTheTrainingSlotAnnouncesItExactlyOnce() {
        List<StatusEvent> heard = new ArrayList<>();
        PythonRunner runner = new PythonRunner(new StatusEvents() {
            @Override
            public void publish(StatusEvent event) {
                heard.add(event);
            }

            @Override
            public Subscription subscribe(Consumer<StatusEvent> listener) {
                return () -> { };
            }
        });

        assertThat(runner.intentarReservarSecuenciaIndiceVisual()).isTrue();
        assertThat(runner.intentarReservarSecuenciaIndiceVisual()).isFalse();

        assertThat(heard).hasSize(1);
        StatusEvent.MlStatus ml = (StatusEvent.MlStatus) heard.get(0);
        assertThat(ml.kind()).isEqualTo(StatusEvent.MlStatus.Kind.TRAINING);
        assertThat(ml.running()).isTrue();
        assertThat(ml.phase()).isEqualTo("starting");
        assertThat(ml.startedAt()).isNotBlank();
    }
}
