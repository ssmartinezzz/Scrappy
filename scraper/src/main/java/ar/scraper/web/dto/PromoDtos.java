package ar.scraper.web.dto;

import ar.scraper.json.ProductJson;
import ar.scraper.model.promo.PromoVigente;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Wire shapes of the payment promos. Field names are pinned: they are the API contract. */
public final class PromoDtos {

    private PromoDtos() {}

    public record Medio(@JsonProperty("nombre") String nombre, @JsonProperty("tipo") String tipo) {}

    public record Red(@JsonProperty("nombre") String nombre) {}

    /** The full promo: {@code GET /api/promos} and the detail's {@code promos}. */
    public record Promo(@JsonProperty("sitio") String sitio, @JsonProperty("medio") Medio medio,
                        @JsonProperty("red") Red red, @JsonProperty("tipo") String tipo,
                        @JsonProperty("cuotas") Integer cuotas, @JsonProperty("pct") Double pct,
                        @JsonProperty("alcance") String alcance,
                        @JsonProperty("vigenciaDesde") String vigenciaDesde,
                        @JsonProperty("vigenciaHasta") String vigenciaHasta,
                        @JsonProperty("textoFuente") String textoFuente,
                        @JsonProperty("vistoAt") String vistoAt) {

        public static Promo of(PromoVigente p) {
            return new Promo(p.sitio(), new Medio(p.medio(), p.tipoMedio().sql()),
                    p.red() == null ? null : new Red(p.red()), p.tipo().sql(), p.cuotas(), aDouble(p.pct()),
                    p.alcance().sql(), p.vigenciaDesde() == null ? null : p.vigenciaDesde().toString(),
                    p.vigenciaHasta() == null ? null : p.vigenciaHasta().toString(), p.textoFuente(),
                    ProductJson.iso(p.vistoAt()));
        }
    }

    /** The catalog row's single best promo: just what a badge needs. */
    public record Mejor(@JsonProperty("medio") String medio, @JsonProperty("red") String red,
                        @JsonProperty("tipo") String tipo, @JsonProperty("cuotas") Integer cuotas,
                        @JsonProperty("pct") Double pct) {

        public static Mejor of(PromoVigente p) {
            return new Mejor(p.medio(), p.red(), p.tipo().sql(), p.cuotas(), aDouble(p.pct()));
        }
    }

    private static Double aDouble(BigDecimal pct) {
        return pct == null ? null : pct.doubleValue();
    }
}
