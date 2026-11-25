package ar.scraper.web.cache;

import ar.scraper.catalog.CatalogoActualizado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The memoized gzip bodies live and die with the view instance")
class CuerposGzipTest {

    private static byte[] plano(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    private static String descomprimir(byte[] gz) throws Exception {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the gzip decodes to exactly the serializer's bytes")
    void roundTrip() throws Exception {
        var cuerpos = new CuerposGzip();
        var vista = new ArrayList<>(List.of("a"));

        assertThat(descomprimir(cuerpos.de(vista, () -> plano("{\"data\":[\"ñandú\"]}")))).isEqualTo("{\"data\":[\"ñandú\"]}");
    }

    @Test
    @DisplayName("N concurrent callers on one instance serialize once and share the bytes")
    void serializesOnceUnderConcurrency() throws Exception {
        var cuerpos = new CuerposGzip();
        var vista = new ArrayList<>(List.of("a"));
        int n = 16;
        var serializaciones = new AtomicInteger();
        var listos = new CountDownLatch(n);
        var soltar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<byte[]>> resultados = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                resultados.add(pool.submit(() -> {
                    listos.countDown();
                    return cuerpos.de(vista, () -> {
                        serializaciones.incrementAndGet();
                        try {
                            soltar.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return plano("cuerpo");
                    });
                }));
            }
            assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
            soltar.countDown();
            byte[] primero = resultados.get(0).get(10, TimeUnit.SECONDS);
            for (Future<byte[]> f : resultados) assertThat(f.get(10, TimeUnit.SECONDS)).isSameAs(primero);
        } finally {
            pool.shutdownNow();
        }
        assertThat(serializaciones).hasValue(1);
    }

    @Test
    @DisplayName("a failing serializer caches nothing and propagates; the next call serializes again")
    void failureIsNotCached() throws Exception {
        var cuerpos = new CuerposGzip();
        var vista = new ArrayList<>(List.of("a"));

        assertThatThrownBy(() -> cuerpos.de(vista, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(descomprimir(cuerpos.de(vista, () -> plano("ok")))).isEqualTo("ok");
    }

    @Test
    @DisplayName("two instances with equal content are two entries: the key is identity")
    void keyIsIdentity() throws Exception {
        var cuerpos = new CuerposGzip();
        var a = new ArrayList<>(List.of("x"));
        var b = new ArrayList<>(List.of("x"));
        var serializaciones = new AtomicInteger();

        cuerpos.de(a, () -> plano("" + serializaciones.incrementAndGet()));
        cuerpos.de(b, () -> plano("" + serializaciones.incrementAndGet()));
        cuerpos.de(a, () -> plano("" + serializaciones.incrementAndGet()));

        assertThat(serializaciones).hasValue(2);
    }

    @Test
    @DisplayName("a catalog change drops every body at once")
    void catalogChangeInvalidates() throws Exception {
        var cuerpos = new CuerposGzip();
        var vista = new ArrayList<>(List.of("a"));
        var serializaciones = new AtomicInteger();

        cuerpos.de(vista, () -> plano("v" + serializaciones.incrementAndGet()));
        cuerpos.alCambiarElCatalogo(new CatalogoActualizado(2));

        assertThat(descomprimir(cuerpos.de(vista, () -> plano("v" + serializaciones.incrementAndGet())))).isEqualTo("v2");
    }

    @Test
    @DisplayName("the memo does not keep the view alive")
    void doesNotRetainTheView() throws Exception {
        var cuerpos = new CuerposGzip();
        WeakReference<Object> referencia = memorizar(cuerpos);

        for (int i = 0; i < 100 && referencia.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }

        assertThat(referencia.get()).isNull();
    }

    private static WeakReference<Object> memorizar(CuerposGzip cuerpos) {
        var vista = new ArrayList<>(List.of("a"));
        cuerpos.de(vista, () -> plano("cuerpo"));
        return new WeakReference<>(vista);
    }
}
