package ar.scraper.web.events;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One client's outbox. Bounded: a full queue drops its OLDEST entries to make room and guarantees
 * exactly one {@code resync} sits behind them, because a client that missed events must re-read
 * state rather than trust what is left. A resync that is already queued is never duplicated.
 */
final class ClientQueue {

    private final ArrayBlockingQueue<StatusEventJson.Wire> queue;
    private final Object lock = new Object();
    private boolean resyncQueued;

    ClientQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    void offer(StatusEventJson.Wire wire) {
        synchronized (lock) {
            if (wire.isResync()) {
                if (resyncQueued) return;
                resyncQueued = true;
            }
            boolean dropped = false;
            while (!queue.offer(wire)) {
                dropOldest();
                dropped = true;
            }
            if (dropped && !resyncQueued) {
                resyncQueued = true;
                StatusEventJson.Wire resync = new StatusEventJson.Wire(StatusEventJson.RESYNC, "{}");
                while (!queue.offer(resync)) dropOldest();
            }
        }
    }

    /** The next entry, or null when none arrived within the timeout. */
    StatusEventJson.Wire poll(long timeoutMs) throws InterruptedException {
        StatusEventJson.Wire next = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (next != null && next.isResync()) {
            synchronized (lock) {
                resyncQueued = false;
            }
        }
        return next;
    }

    void clear() {
        synchronized (lock) {
            queue.clear();
            resyncQueued = false;
        }
    }

    int size() {
        return queue.size();
    }

    private void dropOldest() {
        StatusEventJson.Wire gone = queue.poll();
        if (gone != null && gone.isResync()) resyncQueued = false;
    }
}
