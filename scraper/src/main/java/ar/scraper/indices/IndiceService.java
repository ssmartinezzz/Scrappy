package ar.scraper.indices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only entry point consumers ({@code ml/}, {@code web/}) use to read a
 * macro index. Holds the loaded {@link Serie} per {@link Indice} in a
 * volatile map so a read never blocks on a network refresh; {@link #refrescar()}
 * builds the next map fully before publishing it, so a reader never observes
 * a state where one index updated and another did not.
 */
@Service
public class IndiceService {

    private static final Logger LOG = LoggerFactory.getLogger(IndiceService.class);
    private static final int ULTIMOS_PUNTOS_RESUMEN = 13;

    private final IndicePort indicePort;
    private final Map<Indice, FuenteIndicePort> fuentesPorIndice;

    private volatile Map<Indice, Serie> series = Map.of();
    private volatile String ultimaActualizacion = "sin datos";

    public IndiceService(IndicePort indicePort, Map<Indice, FuenteIndicePort> fuentesPorIndice) {
        this.indicePort = indicePort;
        this.fuentesPorIndice = fuentesPorIndice;
    }

    public void cargarDesdeDB() {
        Map<Indice, Serie> cargadas = new EnumMap<>(Indice.class);
        for (Indice indice : Indice.values()) {
            cargadas.put(indice, new Serie(indice, indicePort.serie(indice)));
        }
        series = Map.copyOf(cargadas);
    }

    public void refrescar() {
        Map<Indice, Serie> nuevas = new EnumMap<>(series);
        boolean algunaOk = false;
        for (Indice indice : Indice.values()) {
            FuenteIndicePort fuente = fuentesPorIndice.get(indice);
            if (fuente == null) continue;
            try {
                indicePort.guardar(fuente.descargar(indice));
                nuevas.put(indice, new Serie(indice, indicePort.serie(indice)));
                algunaOk = true;
            } catch (FuenteIndiceException e) {
                LOG.warn("[INDICES] {} sin actualizar, se conserva la serie anterior: {}", indice, e.getMessage());
            }
        }
        series = Map.copyOf(nuevas);
        if (algunaOk) ultimaActualizacion = LocalDate.now().toString();
    }

    public Deflactor deflactor(Indice indice, LocalDate desde, LocalDate hasta) {
        return serieDe(indice).deflactor(desde, hasta);
    }

    public Deflactor deflactorParaRubro(String rubro, LocalDate desde, LocalDate hasta) {
        return deflactor(DeflactorPorRubro.resolver(rubro), desde, hasta);
    }

    public Optional<Double> variacionMensual(Indice indice) {
        return serieDe(indice).variacionHace(1);
    }

    public ResumenIndice resumen(Indice indice) {
        Serie serie = serieDe(indice);
        if (serie.estaVacia()) return ResumenIndice.sinDatos(indice);
        PuntoIndice ultimo = serie.ultimo().orElseThrow();
        List<PuntoIndice> puntos = serie.puntos();
        List<PuntoIndice> ultimos = puntos.subList(Math.max(0, puntos.size() - ULTIMOS_PUNTOS_RESUMEN), puntos.size());
        return new ResumenIndice(
                indice, ultimo.valor(), ultimo.fecha(),
                serie.variacionHace(1).orElse(null),
                serie.variacionHace(12).orElse(null),
                serie.variacionHace(3).orElse(null),
                Confianza.OBSERVADO, ultimos);
    }

    public String ultimaActualizacion() {
        return ultimaActualizacion;
    }

    private Serie serieDe(Indice indice) {
        return series.getOrDefault(indice, Serie.vacia(indice));
    }
}
