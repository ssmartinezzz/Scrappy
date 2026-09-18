package ar.scraper.indices;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndiceServiceTest {

    private static PuntoIndice punto(Indice indice, String fecha, double valor) {
        return new PuntoIndice(indice, LocalDate.parse(fecha), valor);
    }

    /** In-memory {@link IndicePort}: {@code guardar} upserts by (indice, fecha), like the real table. */
    private static final class IndicePortEnMemoria implements IndicePort {
        private final Map<Indice, Map<LocalDate, Double>> tabla = new HashMap<>();

        @Override
        public void guardar(List<PuntoIndice> puntos) {
            for (PuntoIndice p : puntos) {
                tabla.computeIfAbsent(p.indice(), k -> new HashMap<>()).put(p.fecha(), p.valor());
            }
        }

        @Override
        public List<PuntoIndice> serie(Indice indice) {
            Map<LocalDate, Double> filas = tabla.getOrDefault(indice, Map.of());
            List<PuntoIndice> result = new ArrayList<>();
            filas.forEach((fecha, valor) -> result.add(new PuntoIndice(indice, fecha, valor)));
            return result;
        }
    }

    @Test
    void sinDatosPersistidosNiRefrescoElDeflactorEsNeutro() {
        IndiceService service = new IndiceService(new IndicePortEnMemoria(), Map.of());
        service.cargarDesdeDB();

        Deflactor d = service.deflactor(Indice.IPC, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"));

        assertThat(d).isEqualTo(Deflactor.NEUTRO);
    }

    @Test
    void cargarDesdeDBLevantaLoYaPersistidoSinNecesitarRed() {
        IndicePortEnMemoria db = new IndicePortEnMemoria();
        db.guardar(List.of(punto(Indice.IPC, "2026-01-31", 100.0), punto(Indice.IPC, "2026-02-28", 110.0)));
        IndiceService service = new IndiceService(db, Map.of());

        service.cargarDesdeDB();

        Deflactor d = service.deflactor(Indice.IPC, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"));
        assertThat(d.confianza()).isEqualTo(Confianza.OBSERVADO);
        assertThat(d.factor()).isEqualTo(1.1);
    }

    @Test
    void refrescarDescargaPersisteYPublicaLaSerieNueva() {
        IndicePortEnMemoria db = new IndicePortEnMemoria();
        FuenteIndicePort fuente = indice -> List.of(
                punto(Indice.IPC, "2026-01-31", 100.0), punto(Indice.IPC, "2026-02-28", 120.0));
        IndiceService service = new IndiceService(db, Map.of(Indice.IPC, fuente));
        service.cargarDesdeDB();

        service.refrescar();

        Deflactor d = service.deflactor(Indice.IPC, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"));
        assertThat(d.factor()).isEqualTo(1.2);
        assertThat(db.serie(Indice.IPC)).hasSize(2);
        assertThat(service.ultimaActualizacion()).isNotEqualTo("sin datos");
    }

    @Test
    void unaFuenteQueFallaConservaLaSerieAnteriorDeEseIndiceSinTocarLasOtras() {
        IndicePortEnMemoria db = new IndicePortEnMemoria();
        db.guardar(List.of(punto(Indice.IPC, "2026-01-31", 100.0), punto(Indice.IPC, "2026-02-28", 110.0)));
        FuenteIndicePort ipcQueFalla = indice -> { throw new FuenteIndiceException("caída de red"); };
        FuenteIndicePort usdOk = indice -> List.of(
                punto(Indice.USD_OFICIAL, "2026-09-17", 1500.0), punto(Indice.USD_OFICIAL, "2026-09-18", 1510.0));
        IndiceService service = new IndiceService(db, Map.of(Indice.IPC, ipcQueFalla, Indice.USD_OFICIAL, usdOk));
        service.cargarDesdeDB();

        service.refrescar();

        Deflactor ipc = service.deflactor(Indice.IPC, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-28"));
        assertThat(ipc.factor()).isEqualTo(1.1); // untouched, from before refrescar()

        Deflactor usd = service.deflactor(Indice.USD_OFICIAL, LocalDate.parse("2026-09-17"), LocalDate.parse("2026-09-18"));
        assertThat(usd.confianza()).isEqualTo(Confianza.OBSERVADO);
    }

    @Test
    void resumenCalculaVariacionesPorFechaNoPorPosicion() {
        IndicePortEnMemoria db = new IndicePortEnMemoria();
        // 15 monthly points; a size-based "12 back" would land on index 2 — this
        // asserts the DATE at index 3 (13 months before the last) is used instead.
        List<PuntoIndice> puntos = new ArrayList<>();
        LocalDate fecha = LocalDate.parse("2025-01-31");
        double valor = 100.0;
        for (int i = 0; i < 14; i++) {
            puntos.add(punto(Indice.IPC, fecha.toString(), valor));
            fecha = fecha.plusMonths(1).withDayOfMonth(fecha.plusMonths(1).lengthOfMonth());
            valor *= 1.02;
        }
        db.guardar(puntos);
        IndiceService service = new IndiceService(db, Map.of());
        service.cargarDesdeDB();

        ResumenIndice resumen = service.resumen(Indice.IPC);

        assertThat(resumen.confianza()).isEqualTo(Confianza.OBSERVADO);
        assertThat(resumen.variacionMensual()).isCloseTo(2.0, org.assertj.core.api.Assertions.within(0.01));
        assertThat(resumen.ultimos()).hasSize(13);
    }

    @Test
    void resumenDeIndiceSinDatosEsSinDatos() {
        IndiceService service = new IndiceService(new IndicePortEnMemoria(), Map.of());
        service.cargarDesdeDB();

        ResumenIndice resumen = service.resumen(Indice.USD_OFICIAL);

        assertThat(resumen.confianza()).isEqualTo(Confianza.SIN_DATOS);
        assertThat(resumen.ultimos()).isEmpty();
    }
}
