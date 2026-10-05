package ar.scraper.perf;

import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.uniformRandomTimer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import us.abstracta.jmeter.javadsl.core.threadgroups.BaseThreadGroup.ThreadGroupChild;

/**
 * Lo que comparten todos los planes: a dónde pegarle, con qué credencial, qué
 * endpoints y con qué presupuesto de latencia.
 */
public final class Config {

  public static final String HOST =
      System.getenv().getOrDefault("PERF_API_BASE_URL", "http://localhost:3000");

  /**
   * Un endpoint y su presupuesto.
   *
   * <p><b>peso</b> — cuántas veces aparece en cada iteración. Sin pesos, el plan
   * le pega igual de seguido a {@code /api/status} que a {@code /api/data}, y
   * eso no se parece a ningún usuario real. El perfil de tráfico es parte del
   * test.
   *
   * <p><b>presupuestoP95</b> — el techo de latencia de ESE endpoint. Un techo
   * global sería mentira en los dos sentidos: 500 ms deja a {@code /api/facets}
   * subir 20x sin encender una luz, y deja a {@code /api/grupos} rojo desde el
   * día uno. {@code /api/grupos} re-agrupa el catálogo entero por request y
   * {@code /api/pcs/builder} corre un branch-and-bound: son caros por diseño,
   * no por regresión.
   */
  public record Endpoint(String nombre, String path, int peso, Duration presupuestoP95) {

    static Endpoint de(String nombre, String path, int peso, long p95Ms) {
      return new Endpoint(nombre, path, peso, Duration.ofMillis(p95Ms));
    }
  }

  // ⚠ Los p95 de acá son PROPUESTAS, no mediciones. Corré SmokeIT primero, leé
  // la baseline, y recién ahí escribí el número real.
  //
  // Sólo lectura: ningún POST/PUT/DELETE fuera del login. Un test de carga que
  // escribe contamina el catálogo y hace que la segunda corrida ya no mida lo
  // mismo que la primera.
  // Presupuestos MEDIDOS contra el catálogo real (23.217 productos, 2026-10-05),
  // con `carga`: 20 usuarios, 3 min, 2795 requests, cero errores. Cada techo sale
  // del p95 más alto entre la baseline (1 usuario) y la carga.
  //
  // La regla: presupuesto = max(2 × p95, p95 + 25 ms), redondeado. El factor 2 da
  // aire para que una regresión chica no ponga todo rojo; el "+25 ms" es un piso
  // absoluto, porque el doble de 3 ms es ruido del reloj, no un presupuesto.
  //
  // ⚠ LA INTUICIÓN ESTABA AL REVÉS, y por eso se mide. La primera versión de este
  // archivo le daba 2000-2500 ms a los armadores y a /api/grupos por "caros por
  // diseño", y 300 ms a /api/facets por "precalculado". La medición del 2026-09-22
  // dio pcs_builder 23 ms, grupos 97 y facets 150. Y un techo medido también se
  // vence: para el 2026-10-05 facets y grupos bajaron a 4 ms, y sus techos de 300 y
  // 200 ya no detectaban ni una regresión de 50x. Re-medir cuando cambia el catálogo
  // o el backend.
  //
  // Sólo lectura: ningún POST/PUT/DELETE fuera del login. Un test de carga que
  // escribe contamina el catálogo y hace que la segunda corrida ya no mida lo
  // mismo que la primera.
  public static final List<Endpoint> ENDPOINTS = List.of(
      // un campo en memoria — p95 medido 3 ms
      Endpoint.de("status", "/api/status", 1, 30),
      // IPC + dólar ya refrescados por un job aparte — p95 medido 3 ms
      Endpoint.de("indices", "/api/indices", 1, 30),
      // agregación por marca sobre el snapshot — p95 medido 4 ms
      Endpoint.de("marcas", "/api/marcas-browser", 1, 30),
      // la primera pantalla de /catalogo, y el endpoint más pedido — p95 medido 24 ms
      Endpoint.de("data", "/api/data?page=0&size=24", 5, 50),
      // el mismo SQL con WHERE + ORDER BY — p95 medido 41 ms, el segundo más lento
      Endpoint.de("data_filtrado", "/api/data?page=0&size=24&rubro=tecnologia&orden=precio_asc", 3, 90),
      // conteos SQL por faceta — p95 medido 4 ms (150 el 2026-09-22)
      Endpoint.de("facets", "/api/facets", 2, 30),
      // agrupa el catálogo una vez por versión de snapshot y lo memoiza — p95 medido 4 ms
      Endpoint.de("grupos", "/api/grupos?minSitios=2&page=0&size=20", 2, 30),
      // mejor pick por categoría sobre el snapshot — p95 medido 7 ms
      Endpoint.de("mejores", "/api/mejores", 1, 40),
      // arma el FeedbackModel y pega 2 queries — p95 medido 66 ms, EL más lento
      Endpoint.de("recomendados", "/api/recomendados?page=0&size=24", 2, 140),
      // MCKP con branch-and-bound — p95 medido 13 ms. `categorias` es obligatorio
      Endpoint.de("outfits_builder", "/api/outfits/builder?categorias=Remera,Jean,Zapatilla&presupuesto=150000&estilo=gym", 1, 40),
      // una pasada por precedencia sobre 33 subtipos — p95 medido 13 ms. `tipos` es obligatorio
      Endpoint.de("suplementos_builder", "/api/suplementos/builder?tipos=Prote%C3%ADna%20en%20Polvo,Creatina&presupuesto=100000", 1, 40),
      // parsea TechSpecs al armar y corre siete slots con sus vetos — p95 medido 39 ms, el tercero más lento
      Endpoint.de("pcs_builder", "/api/pcs/builder?presupuesto=2000000&conGpu=true&gama=alta", 1, 80));

  /**
   * p95 medido 137 ms con 10 hilos sin pausa (2026-10-05). Locust mide 41 ms porque
   * sus usuarios esperan 1-3 s entre logins; acá los diez verifies de Argon2id compiten
   * entre sí. Cada suite tiene el techo de su propia carga. Ver README.
   */
  public static final Duration PRESUPUESTO_LOGIN = Duration.ofMillis(280);

  private Config() {}

  /**
   * Los samplers del mix, cada endpoint repetido tantas veces como su peso.
   *
   * <p>El nombre del sampler es lo que agrupa las estadísticas: sin él, cada
   * query string sería una fila distinta en el reporte y no habría un percentil
   * por endpoint que mirar.
   */
  public static ThreadGroupChild[] mix(String token) {
    List<ThreadGroupChild> hijos = new ArrayList<>();
    for (Endpoint e : ENDPOINTS) {
      for (int i = 0; i < e.peso(); i++) {
        hijos.add(httpSampler(e.nombre(), HOST + e.path())
            .header("Authorization", "Bearer " + token));
      }
    }
    return hijos.toArray(new ThreadGroupChild[0]);
  }

  /**
   * El mismo mix, con tiempo de lectura entre request y request.
   *
   * <p>No es relleno: un usuario real mira la pantalla entre click y click. Sin
   * esa pausa, 20 hilos generan el tráfico de varios cientos de usuarios y
   * "20 usuarios" deja de querer decir nada.
   */
  public static ThreadGroupChild[] mixConPausa(String token) {
    List<ThreadGroupChild> hijos = new ArrayList<>();
    hijos.add(uniformRandomTimer(Duration.ofMillis(500), Duration.ofMillis(2000)));
    hijos.addAll(List.of(mix(token)));
    return hijos.toArray(new ThreadGroupChild[0]);
  }

  /**
   * UN login, antes del plan, y el token lo comparten todos los hilos.
   *
   * <p>El error clásico de una suite de performance es loguearse adentro del
   * plan. Ahí el número que sale no es la latencia del endpoint: es la de
   * Argon2id (~22 ms de verify medidos), muchas veces lo que tarda un GET.
   * Bajar un endpoint a la mitad de su costo no movería la aguja y el test
   * diría que no pasó nada.
   *
   * <p>El costo de loguearse se mide aparte, en {@link LoginIT}, que es donde
   * esa pregunta sí es la pregunta.
   */
  public static String token() throws Exception {
    String usuario = System.getenv("PERF_USERNAME");
    String password = System.getenv("PERF_PASSWORD");
    if (usuario == null || password == null || usuario.isBlank() || password.isBlank()) {
      throw new IllegalStateException("faltan PERF_USERNAME / PERF_PASSWORD (ver README.md)");
    }

    String cuerpo = "{\"username\":\"" + usuario + "\",\"password\":\"" + password + "\"}";
    HttpResponse<String> r = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(HOST + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    if (r.statusCode() != 200) {
      throw new IllegalStateException(
          "login respondió " + r.statusCode() + " — sin token no hay nada que medir");
    }
    // El cuerpo es {"data":{"accessToken":"...", ...}}; alcanza con recortarlo, y así el
    // módulo no arrastra una dependencia de JSON para leer un solo campo.
    int desde = r.body().indexOf("\"accessToken\":\"") + 15;
    return r.body().substring(desde, r.body().indexOf('"', desde));
  }
}
