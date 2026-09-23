package ar.scraper.perf;

import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Escribe los planes como archivos {@code .jmx} para abrirlos en la GUI de
 * JMeter.
 *
 * <p>Lo que se construye en Java <b>es</b> un plan de JMeter: no hay traducción
 * ni equivalente aproximado. Esto sirve para inspeccionarlo con el árbol de la
 * GUI, para depurarlo con el View Results Tree, o para pasárselo a alguien que
 * trabaja desde ahí.
 *
 * <p>La dirección es de ida: el {@code .jmx} es una salida, no una fuente. Lo
 * que se versiona y se revisa en un PR es el Java — un {@code .jmx} es XML
 * generado, ilegible en un diff.
 *
 * <pre>  tests/perf/jmeter/run.sh jmx</pre>
 */
public class ExportarJmx {

  public static void main(String[] args) throws Exception {
    Path destino = Path.of(args.length > 0 ? args[0] : "target/jmx");
    Files.createDirectories(destino);

    // Un token de mentira: el .jmx es para mirar la FORMA del plan, y meterle
    // una credencial real adentro sería escribir un secreto a un archivo.
    String token = "PEGAR_UN_TOKEN_ACA";

    testPlan(threadGroup("smoke", 1, 20, Config.mix(token)))
        .saveAsJmx(destino.resolve("smoke.jmx").toString());

    testPlan(
        threadGroup("carga")
            .rampToAndHold(20, Duration.ofSeconds(30), Duration.ofMinutes(3))
            .children(Config.mixConPausa(token)))
        .saveAsJmx(destino.resolve("carga.jmx").toString());

    System.out.println("planes escritos en " + destino.toAbsolutePath());
  }
}
