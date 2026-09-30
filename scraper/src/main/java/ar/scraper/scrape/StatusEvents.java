package ar.scraper.scrape;

import java.util.function.Consumer;

/** In-process bus for {@link StatusEvent}s: publishers never know who listens. */
public interface StatusEvents {

    /** Delivers to every current subscriber; a failing subscriber never affects the publisher or the others. */
    void publish(StatusEvent event);

    /** Closing the subscription stops delivery. Listeners must return quickly: enqueue, do not work. */
    Subscription subscribe(Consumer<StatusEvent> listener);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    StatusEvents NONE = new StatusEvents() {
        @Override
        public void publish(StatusEvent event) {
        }

        @Override
        public Subscription subscribe(Consumer<StatusEvent> listener) {
            return () -> { };
        }
    };
}
