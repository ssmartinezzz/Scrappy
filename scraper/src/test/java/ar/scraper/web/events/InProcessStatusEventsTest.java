package ar.scraper.web.events;

import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Status push")
@Feature("Event bus")
@Story("In-process bus: isolation, ordering and progress coalescing")
@DisplayName("InProcessStatusEvents")
class InProcessStatusEventsTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> dueAt = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private InProcessStatusEvents bus;
    private final List<StatusEvent> heard = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bus = new InProcessStatusEvents(250, clock::get, (delay, task) -> {
            delays.add(delay);
            dueAt.add(clock.get() + delay);
            scheduled.add(task);
        });
        bus.subscribe(heard::add);
    }

    private static StatusEvent.ScrapeProgress progress(int completados) {
        return new StatusEvent.ScrapeProgress(10, completados, completados * 5, List.of());
    }

    private static StatusEvent.ScrapeStatus status(ScraperStatus s, String msg) {
        return new StatusEvent.ScrapeStatus(s, msg);
    }

    /** Moves the clock and runs every delayed task that came due, like a real scheduler would. */
    private void advance(long ms) {
        long target = clock.get() + ms;
        while (true) {
            int next = -1;
            for (int i = 0; i < scheduled.size(); i++) {
                if (dueAt.get(i) <= target && (next < 0 || dueAt.get(i) < dueAt.get(next))) next = i;
            }
            if (next < 0) break;
            clock.set(Math.max(clock.get(), dueAt.get(next)));
            dueAt.remove(next);
            scheduled.remove(next).run();
        }
        clock.set(target);
    }

    @Test
    @DisplayName("every subscriber hears an event until it closes its subscription")
    void deliversUntilClosed() {
        List<StatusEvent> second = new ArrayList<>();
        StatusEvents.Subscription sub = bus.subscribe(second::add);

        bus.publish(status(ScraperStatus.RUNNING, "a"));
        sub.close();
        bus.publish(status(ScraperStatus.DONE, "b"));

        assertThat(heard).hasSize(2);
        assertThat(second).containsExactly(status(ScraperStatus.RUNNING, "a"));
    }

    @Test
    @DisplayName("a listener that throws affects neither the publisher nor the other listeners")
    void failingListenerIsIsolated() {
        List<StatusEvent> last = new ArrayList<>();
        bus.subscribe(e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(last::add);

        bus.publish(new StatusEvent.Resync());

        assertThat(heard).containsExactly(new StatusEvent.Resync());
        assertThat(last).containsExactly(new StatusEvent.Resync());
    }

    @Test
    @DisplayName("the first progress goes out at once; a burst inside the gap keeps only the latest")
    void progressIsCoalesced() {
        bus.publish(progress(1));
        clock.addAndGet(10);
        bus.publish(progress(2));
        clock.addAndGet(10);
        bus.publish(progress(3));

        assertThat(heard).containsExactly(progress(1));
        assertThat(delays).hasSize(1);
        assertThat(delays.get(0)).isEqualTo(240);

        advance(240);

        assertThat(heard).containsExactly(progress(1), progress(3));
    }

    @Test
    @DisplayName("progress after the gap has passed goes out immediately")
    void progressAfterTheGapIsImmediate() {
        bus.publish(progress(1));
        clock.addAndGet(300);
        bus.publish(progress(2));

        assertThat(heard).containsExactly(progress(1), progress(2));
        assertThat(scheduled).isEmpty();
    }

    @Test
    @DisplayName("a status event flushes the pending progress first, so order is preserved")
    void statusFlushesPendingProgressFirst() {
        bus.publish(progress(1));
        bus.publish(progress(9));
        bus.publish(status(ScraperStatus.DONE, "Completado"));

        assertThat(heard).containsExactly(progress(1), progress(9), status(ScraperStatus.DONE, "Completado"));

        advance(500);
        assertThat(heard).as("the delayed flush finds nothing left").hasSize(3);
    }

    @Test
    @DisplayName("a steady stream never exceeds four progress events per second")
    void progressRateIsBounded() {
        for (int i = 0; i < 1000; i++) {
            bus.publish(progress(i));
            advance(5);
        }
        advance(300);

        long seconds = 5;
        assertThat(heard.size()).isLessThanOrEqualTo((int) (seconds * 4 + 4));
        assertThat(heard.get(heard.size() - 1)).isEqualTo(progress(999));
    }
}
