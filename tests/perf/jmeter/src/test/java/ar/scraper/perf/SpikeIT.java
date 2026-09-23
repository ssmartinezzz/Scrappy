package ar.scraper.perf;

import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Spike: un pico de golpe, no una rampa.
 *
 * <p>Contesta otra pregunta que {@link StressIT}: no "cuánto aguanta" sino
 * "¿se recupera?". Lo que se mira es la meseta tranquila DESPUÉS del pico — si
 * la latencia no vuelve a donde estaba, algo quedó saturado (el pool de
 * conexiones, una cola, un timeout que todavía está esperando).
 */
public class SpikeIT {

  @Test
  void cientoCincuentaDeGolpeYVuelta() throws Exception {
    TestPlanStats stats = testPlan(
        threadGroup("spike")
            .rampToAndHold(5,   Duration.ofSeconds(10), Duration.ofSeconds(30))  // tranquilo
            .rampToAndHold(150, Duration.ofSeconds(1),  Duration.ofSeconds(60))  // el pico
            .rampToAndHold(5,   Duration.ofSeconds(5),  Duration.ofSeconds(60))  // ¿vuelve?
            .children(Config.mix(Config.token())),
        jtlWriter("target/jtl/spike")
    ).run();

    System.out.printf("p95 global: %d ms · %d errores en %d requests%n",
        stats.overall().sampleTime().perc95().toMillis(),
        stats.overall().errorsCount(),
        stats.overall().samplesCount());
  }
}
