package ar.scraper.web.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientQueue — bounded, drop-oldest, one resync behind a gap")
class ClientQueueTest {

    private static StatusEventJson.Wire wire(int n) {
        return new StatusEventJson.Wire("scrape.status", "{\"n\":" + n + "}");
    }

    private static final StatusEventJson.Wire RESYNC = new StatusEventJson.Wire("resync", "{}");

    private static List<StatusEventJson.Wire> drain(ClientQueue q) throws InterruptedException {
        List<StatusEventJson.Wire> out = new ArrayList<>();
        StatusEventJson.Wire w;
        while ((w = q.poll(1)) != null) out.add(w);
        return out;
    }

    @Test
    @DisplayName("within capacity nothing is dropped and no resync is invented")
    void withinCapacity() throws Exception {
        ClientQueue q = new ClientQueue(4);
        for (int i = 0; i < 4; i++) q.offer(wire(i));

        assertThat(drain(q)).containsExactly(wire(0), wire(1), wire(2), wire(3));
    }

    @Test
    @DisplayName("overflow drops the oldest, keeps the newest, and keeps exactly one resync queued")
    void overflowKeepsTheNewestAndAddsOneResync() throws Exception {
        ClientQueue q = new ClientQueue(4);
        for (int i = 0; i < 10; i++) q.offer(wire(i));

        List<StatusEventJson.Wire> out = drain(q);
        assertThat(out).hasSize(4);
        assertThat(out.stream().filter(StatusEventJson.Wire::isResync)).hasSize(1);
        assertThat(out.stream().filter(w -> !w.isResync())).containsExactly(wire(7), wire(8), wire(9));
        assertThat(out.indexOf(RESYNC)).as("the gap is always before the resync").isPositive();
    }

    @Test
    @DisplayName("a resync already queued is not duplicated, neither by overflow nor by an explicit one")
    void resyncIsNeverDuplicated() throws Exception {
        ClientQueue q = new ClientQueue(3);
        q.offer(RESYNC);
        q.offer(RESYNC);
        for (int i = 0; i < 6; i++) q.offer(wire(i));
        q.offer(RESYNC);

        assertThat(drain(q).stream().filter(StatusEventJson.Wire::isResync)).hasSize(1);
    }

    @Test
    @DisplayName("once the resync was delivered, a later overflow queues a new one")
    void resyncCanBeQueuedAgainAfterDelivery() throws Exception {
        ClientQueue q = new ClientQueue(2);
        for (int i = 0; i < 5; i++) q.offer(wire(i));
        drain(q);

        for (int i = 10; i < 15; i++) q.offer(wire(i));

        List<StatusEventJson.Wire> out = drain(q);
        assertThat(out).contains(RESYNC);
        assertThat(out).contains(wire(14));
    }

    @Test
    @DisplayName("a queued resync that is itself dropped is replaced, never lost")
    void aDroppedResyncIsReplaced() throws Exception {
        ClientQueue q = new ClientQueue(2);
        q.offer(RESYNC);
        for (int i = 0; i < 5; i++) q.offer(wire(i));

        List<StatusEventJson.Wire> out = drain(q);
        assertThat(out).hasSize(2);
        assertThat(out.stream().filter(StatusEventJson.Wire::isResync)).hasSize(1);
        assertThat(out).last().isEqualTo(wire(4));
    }

    @Test
    @DisplayName("poll returns null on timeout instead of blocking forever")
    void pollTimesOut() throws Exception {
        assertThat(new ClientQueue(2).poll(20)).isNull();
    }
}
