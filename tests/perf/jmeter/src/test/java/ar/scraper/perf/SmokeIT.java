package ar.scraper.perf;

import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Smoke: un hilo, sin concurrencia.
 *
 * <p>NO mide capacidad. Mide cuánto tarda cada endpoint cuando nadie más
 * molesta, y ese número es la <b>baseline</b>: es contra él que se escriben los
 * presupuestos de {@link Config}. Es lo primero que se corre, siempre — sin
 * baseline, un rojo no distingue "la app está lenta" de "el techo estaba mal
 * puesto".
 *
 * <p>Diez iteraciones, no una: la PRIMERA request de cada endpoint paga el
 * establecimiento de conexión y el JIT todavía frío. Medido acá, {@code status}
 * dio med 2 ms y max 20 ms sobre diez iteraciones — ese 20 es el arranque, no el
 * endpoint, y con n=10 el p95 cae justo sobre él. Con pocas muestras el warm-up
 * ES el percentil.
 */
public class SmokeIT {

  @Test
  void cadaEndpointContestaDentroDeSuPresupuesto() throws Exception {
    TestPlanStats stats = testPlan(
        threadGroup("smoke", 1, 20, Config.mix(Config.token())),
        jtlWriter("target/jtl/smoke")
    ).run();

    Veredicto.dentroDePresupuesto(stats);
  }
}
