package ar.scraper.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import java.time.Duration;
import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * El costo de autenticarse, medido aparte de todo lo demás.
 *
 * <p>Argon2id es memory-bound <i>a propósito</i>: ~22 ms de verify (re-medidos
 * 2026-09-22) son el precio de que un hash robado no se pueda romper a fuerza
 * de GPU. El login entero clava 43 ms p95 con diez usuarios concurrentes.
 * Mezclarlo con los GET del
 * catálogo arruinaría las dos mediciones a la vez — el login taparía cualquier
 * cambio en los endpoints, y los endpoints diluirían el login.
 *
 * <p>Es el único POST de la suite. Todo lo demás es lectura.
 */
public class LoginIT {

  @Test
  void elLoginNoSeDisparaBajoConcurrencia() throws Exception {
    String cuerpo = "{\"username\":\"%s\",\"password\":\"%s\"}"
        .formatted(System.getenv("PERF_USERNAME"), System.getenv("PERF_PASSWORD"));

    TestPlanStats stats = testPlan(
        threadGroup("login")
            .rampToAndHold(10, Duration.ofSeconds(10), Duration.ofMinutes(1))
            .children(
                httpSampler("login", Config.HOST + "/api/auth/login")
                    .post(cuerpo, ContentType.APPLICATION_JSON)),
        jtlWriter("target/jtl/login")
    ).run();

    assertThat(stats.overall().sampleTime().perc95())
        .as("p95 del login")
        .isLessThan(Config.PRESUPUESTO_LOGIN);
  }
}
