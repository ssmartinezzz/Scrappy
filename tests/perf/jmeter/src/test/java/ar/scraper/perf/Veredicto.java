package ar.scraper.perf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

/**
 * Cuándo está mal: el presupuesto por endpoint decide si el test pasa.
 *
 * <p>Sin esto el plan corre, imprime un reporte lindo y termina en verde pase
 * lo que pase. Un test que no puede ponerse rojo no es un test.
 */
final class Veredicto {

  private Veredicto() {}

  /** Falla nombrando TODOS los endpoints que se pasaron, no sólo el primero. */
  static void dentroDePresupuesto(TestPlanStats stats) {
    List<String> fallas = new ArrayList<>();

    for (Config.Endpoint e : Config.ENDPOINTS) {
      var porEndpoint = stats.byLabel(e.nombre());
      if (porEndpoint == null || porEndpoint.samplesCount() == 0) {
        continue;
      }
      Duration p95 = porEndpoint.sampleTime().perc95();
      if (p95.compareTo(e.presupuestoP95()) > 0) {
        fallas.add("  ✗ %s: p95 %d ms > presupuesto %d ms"
            .formatted(e.nombre(), p95.toMillis(), e.presupuestoP95().toMillis()));
      }
    }

    // Un error bajo carga no es "lento", es roto. Se chequea aparte del tiempo:
    // un endpoint que falla rápido tiene un p95 excelente.
    long errores = stats.overall().errorsCount();
    long total = stats.overall().samplesCount();
    if (total == 0) {
      fallas.add("  ✗ cero requests — el test no llegó a correr");
    } else if ((double) errores / total > 0.01) {
      fallas.add("  ✗ %d errores en %d requests (> 1%%)".formatted(errores, total));
    }

    if (!fallas.isEmpty()) {
      throw new AssertionError("presupuesto de latencia excedido:\n" + String.join("\n", fallas));
    }
  }
}
