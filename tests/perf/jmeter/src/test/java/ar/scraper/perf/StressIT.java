package ar.scraper.perf;

import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Stress: subir hasta que duela, para encontrar DÓNDE duele.
 *
 * <p>Se espera que rompa. El dato que se busca no es verde/rojo: es el número
 * de usuarios en el que la latencia se dispara o aparecen los errores. Por eso
 * este plan <b>no</b> llama al veredicto — un rojo acá no significa lo mismo
 * que un rojo en {@link CargaIT}. Lo que deja es el escalón en el que el
 * sistema se dio vuelta, que se lee en la salida y en el JTL.
 */
public class StressIT {

  @Test
  void escalonesHastaDoscientosUsuarios() throws Exception {
    TestPlanStats stats = testPlan(
        threadGroup("stress")
            // Escalones, no una rampa lisa: cada meseta es una medición estable
            // a esa carga. Una rampa continua mezcla todos los niveles en un
            // solo percentil y no deja leer en cuál se rompió.
            .rampToAndHold(25,  Duration.ofSeconds(15), Duration.ofMinutes(1))
            .rampToAndHold(50,  Duration.ofSeconds(15), Duration.ofMinutes(1))
            .rampToAndHold(100, Duration.ofSeconds(15), Duration.ofMinutes(1))
            .rampToAndHold(200, Duration.ofSeconds(15), Duration.ofMinutes(1))
            .rampTo(0, Duration.ofSeconds(10))
            .children(Config.mix(Config.token())),
        jtlWriter("target/jtl/stress")
    ).run();

    System.out.printf("p95 global: %d ms · %d errores en %d requests%n",
        stats.overall().sampleTime().perc95().toMillis(),
        stats.overall().errorsCount(),
        stats.overall().samplesCount());
  }
}
