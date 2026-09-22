package ar.scraper.perf;

import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Carga: el tráfico que se espera de verdad.
 *
 * <p>Acá el veredicto significa algo. Si un endpoint se pasa de su presupuesto
 * con el tráfico normal, es un problema hoy — no una proyección.
 */
public class CargaIT {

  @Test
  void veinteUsuariosDuranteTresMinutos() throws Exception {
    TestPlanStats stats = testPlan(
        threadGroup("carga")
            .rampToAndHold(20, Duration.ofSeconds(30), Duration.ofMinutes(3))
            .children(Config.mixConPausa(Config.token())),
        jtlWriter("target/jtl/carga")
    ).run();

    Veredicto.dentroDePresupuesto(stats);
  }
}
