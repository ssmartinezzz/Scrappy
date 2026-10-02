package ar.scraper.fuentes;

import ar.scraper.indices.FuenteIndiceException;
import ar.scraper.indices.FuenteIndicePort;
import ar.scraper.indices.Indice;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class FuentesDescargarTest {

    private static final String DOLAR = "[{\"venta\": 1530, \"fecha\": \"2026-09-14\"}]";
    private static final String IPC_ARGENTINADATOS = "[{\"valor\": 2.0, \"fecha\": \"2016-12-31\"}]";
    private static final String IPC_DATOS_GOB = "{\"data\": [[\"2016-12-01\", 100.0]]}";

    private static void descargaCuerpo(FuenteIndicePort fuente, Indice indice, String body, int puntos) throws Exception {
        try (MockedStatic<HttpJson> http = mockStatic(HttpJson.class)) {
            http.when(() -> HttpJson.get(anyString())).thenReturn(body);
            assertThat(fuente.descargar(indice)).hasSize(puntos);
        }
    }

    private static Throwable descargaFallando(FuenteIndicePort fuente, Exception falla) {
        try (MockedStatic<HttpJson> http = mockStatic(HttpJson.class)) {
            http.when(() -> HttpJson.get(anyString())).thenThrow(falla);
            return org.assertj.core.api.Assertions.catchThrowable(() -> fuente.descargar(Indice.IPC));
        }
    }

    @Test
    void cadaFuenteParseaElCuerpoQueBajaDeHttp() throws Exception {
        descargaCuerpo(new ArgentinaDatosDolarFuente(), Indice.USD_OFICIAL, DOLAR, 1);
        descargaCuerpo(new ArgentinaDatosIpcFuente(), Indice.IPC, IPC_ARGENTINADATOS, 1);
        descargaCuerpo(new DatosGobIpcFuente(), Indice.IPC, IPC_DATOS_GOB, 1);
    }

    @Test
    void unCuerpoQueNoParseaSalePorLaExcepcionDeLaFuenteSinReenvolver() {
        for (FuenteIndicePort fuente : List.<FuenteIndicePort>of(new ArgentinaDatosDolarFuente(),
                new ArgentinaDatosIpcFuente(), new DatosGobIpcFuente())) {
            try (MockedStatic<HttpJson> http = mockStatic(HttpJson.class)) {
                http.when(() -> HttpJson.get(anyString())).thenReturn("{}");
                assertThatThrownBy(() -> fuente.descargar(Indice.IPC))
                        .isExactlyInstanceOf(FuenteIndiceException.class)
                        .hasMessageStartingWith("respuesta de ");
            }
        }
    }

    @Test
    void unaFallaDeRedSeEnvuelveConElNombreDeLaClaseYLaCausa() {
        IOException caida = new IOException("HTTP 503 de x");

        for (FuenteIndicePort fuente : List.<FuenteIndicePort>of(new ArgentinaDatosDolarFuente(),
                new ArgentinaDatosIpcFuente(), new DatosGobIpcFuente())) {
            Throwable t = descargaFallando(fuente, caida);
            assertThat(t).isExactlyInstanceOf(FuenteIndiceException.class);
            assertThat(t).hasMessage(fuente.getClass().getSimpleName() + ": HTTP 503 de x");
            assertThat(t).hasCause(caida);
        }
    }

    @Test
    void laExcepcionDeLaFuenteQueTiraElDescargadorPasaIntacta() {
        FuenteIndiceException propia = new FuenteIndiceException("propia");

        assertThat(descargaFallando(new DatosGobIpcFuente(), propia)).isSameAs(propia);
    }
}
