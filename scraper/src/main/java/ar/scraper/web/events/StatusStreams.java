package ar.scraper.web.events;

import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Every client owns a bounded queue and a virtual thread that does the blocking socket writes, so a
 * slow reader can never stall the bus or the publishers: when its queue is full the oldest events
 * are dropped and a {@code resync} is queued at the tail, telling the client to re-read whatever it
 * cares about.
 */
@Component
public class StatusStreams {

    private static final Logger LOG = LoggerFactory.getLogger(StatusStreams.class);

    static final int QUEUE_CAPACITY = 64;
    static final long EMITTER_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    static final long PING_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);

    private final StatusEvents bus;
    private final int capacity;
    private final long timeoutMs;
    private final long pingMs;
    private final AtomicInteger open = new AtomicInteger();

    @Autowired
    public StatusStreams(StatusEvents bus) {
        this(bus, QUEUE_CAPACITY, EMITTER_TIMEOUT_MS, PING_INTERVAL_MS);
    }

    StatusStreams(StatusEvents bus, int capacity, long timeoutMs, long pingMs) {
        this.bus = bus;
        this.capacity = capacity;
        this.timeoutMs = timeoutMs;
        this.pingMs = pingMs;
    }

    public int openStreams() {
        return open.get();
    }

    /**
     * Subscribes FIRST, then sends the snapshot, then drains: an event that happens while the
     * snapshot is being built is queued behind it instead of being lost..
     */
    public SseEmitter open(boolean admin, Supplier<Object> snapshot) {
        Client client = new Client(admin);
        client.subscription = bus.subscribe(client::offer);
        try {
            client.send(StatusEventJson.snapshot(snapshot.get()));
        } catch (RuntimeException | IOException e) {
            client.close();
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("cannot open the status stream", e);
        }
        client.start();
        return client.emitter;
    }

    private final class Client {
        private final SseEmitter emitter = new SseEmitter(timeoutMs);
        private final ClientQueue queue = new ClientQueue(capacity);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final boolean admin;
        private volatile StatusEvents.Subscription subscription;
        private volatile Thread pump;

        Client(boolean admin) {
            this.admin = admin;
            open.incrementAndGet();
            emitter.onCompletion(this::close);
            emitter.onTimeout(emitter::complete);
            emitter.onError(e -> close());
        }

        void offer(StatusEvent event) {
            if (closed.get()) return;
            if (event instanceof StatusEvent.DbChanged d && "cron_execution".equals(d.table()) && !admin) return;
            queue.offer(StatusEventJson.of(event));
        }

        void start() {
            pump = Thread.ofVirtual().name("sse-pump").start(this::pump);
        }

        private void pump() {
            try {
                while (!closed.get()) {
                    StatusEventJson.Wire next = queue.poll(pingMs);
                    if (next == null) {
                        emitter.send(SseEmitter.event().comment(" ping"));
                        continue;
                    }
                    send(next);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException gone) {
                LOG.debug("[SSE] client gone: {}", gone.toString());
                emitter.complete();
            } finally {
                close();
            }
        }

        void send(StatusEventJson.Wire wire) throws IOException {
            emitter.send(SseEmitter.event().name(wire.name()).data(wire.json(), MediaType.APPLICATION_JSON));
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            open.decrementAndGet();
            StatusEvents.Subscription sub = subscription;
            if (sub != null) sub.close();
            Thread t = pump;
            if (t != null) t.interrupt();
            queue.clear();
        }
    }
}
