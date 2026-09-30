package ar.scraper.web.events;

import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Progress events arrive once per finished site but a burst can be dense; they are coalesced to
 * at most one per {@link #PROGRESS_GAP_MS}, always keeping the latest. Every other event is
 * delivered immediately and, when progress is pending, AFTER flushing it, so a listener never
 * sees a terminal status before the last progress that preceded it.
 */
@Component
public class InProcessStatusEvents implements StatusEvents {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessStatusEvents.class);

    static final long PROGRESS_GAP_MS = 250;

    private final List<Consumer<StatusEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final long gapMs;
    private final LongSupplier nowMs;
    private final BiConsumer<Long, Runnable> delayer;
    private final ScheduledExecutorService ownScheduler;

    private StatusEvent.ScrapeProgress pendingProgress;
    private boolean flushScheduled;
    private long lastProgressAt = Long.MIN_VALUE / 2;

    @Autowired
    public InProcessStatusEvents() {
        this(PROGRESS_GAP_MS, System::currentTimeMillis,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "status-events-flush");
                    t.setDaemon(true);
                    return t;
                }));
    }

    private InProcessStatusEvents(long gapMs, LongSupplier nowMs, ScheduledExecutorService scheduler) {
        this(gapMs, nowMs, (delay, task) -> scheduler.schedule(task, delay, TimeUnit.MILLISECONDS), scheduler);
    }

    /** Test seam: a manual clock and a manual delayer make coalescing deterministic. */
    InProcessStatusEvents(long gapMs, LongSupplier nowMs, BiConsumer<Long, Runnable> delayer) {
        this(gapMs, nowMs, delayer, null);
    }

    private InProcessStatusEvents(long gapMs, LongSupplier nowMs, BiConsumer<Long, Runnable> delayer,
                                  ScheduledExecutorService ownScheduler) {
        this.gapMs = gapMs;
        this.nowMs = nowMs;
        this.delayer = delayer;
        this.ownScheduler = ownScheduler;
    }

    @Override
    public void publish(StatusEvent event) {
        synchronized (lock) {
            if (event instanceof StatusEvent.ScrapeProgress progress) {
                publishProgress(progress);
                return;
            }
            flushPending();
            dispatch(event);
        }
    }

    @Override
    public Subscription subscribe(Consumer<StatusEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void publishProgress(StatusEvent.ScrapeProgress progress) {
        long now = nowMs.getAsLong();
        long wait = lastProgressAt + gapMs - now;
        if (wait <= 0 && !flushScheduled) {
            pendingProgress = null;
            lastProgressAt = now;
            dispatch(progress);
            return;
        }
        pendingProgress = progress;
        if (!flushScheduled) {
            flushScheduled = true;
            delayer.accept(Math.max(wait, 1), this::flushScheduledProgress);
        }
    }

    private void flushScheduledProgress() {
        synchronized (lock) {
            flushScheduled = false;
            flushPending();
        }
    }

    private void flushPending() {
        StatusEvent.ScrapeProgress progress = pendingProgress;
        if (progress == null) return;
        pendingProgress = null;
        lastProgressAt = nowMs.getAsLong();
        dispatch(progress);
    }

    private void dispatch(StatusEvent event) {
        for (Consumer<StatusEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOG.warn("[EVENTS] a listener failed on {}: {}", event.getClass().getSimpleName(), e.toString());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownScheduler != null) ownScheduler.shutdownNow();
    }
}
