package ar.scraper.web.dto;

import ar.scraper.catalog.Facets;
import ar.scraper.json.ProductJson;
import ar.scraper.catalog.ProductKey;
import ar.scraper.model.Product;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/** Payloads of the catalog listing, facets and product detail endpoints. */
public final class CatalogoDtos {

    private CatalogoDtos() {}

    /** {@code data} of {@code GET /api/data}; the pagination block travels in the envelope's {@code page}. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Catalogo {
        private Meta meta;
        private List<ProductoRow> productos;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Meta {
        private String moneda;
        private double precioMin;
        private double precioMax;
        private double rangMin;
        private double rangMax;
        private String fecha;
        private FacetsDto facets;
        private Map<String, Long> marcas;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, String> errores;
    }

    /** Facet histograms; {@code rubros} is only published by {@code /api/data}. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FacetsDto {
        private Map<String, Long> talles;
        private Map<String, Long> generos;
        private Map<String, Long> categorias;
        private Map<String, Long> marcas;
        private Map<String, Long> badges;
        private Map<String, Long> subCategorias;
        private Map<String, Long> fits;
        private Map<String, Long> estampados;
        private Map<String, Long> escotes;
        private Map<String, Long> colorDominantes;
        private Map<String, Long> rubros;
        private long gymratCount;
        private long packCount;

        public static FacetsDto vacio() {
            Map<String, Long> m = Map.of();
            return new FacetsDto(m, m, m, m, m, m, m, m, m, m, null, 0, 0);
        }

        public static FacetsDto of(Facets f, Map<String, Long> rubros, long gymrat, long packs) {
            return new FacetsDto(f.talles(), f.generos(), f.categorias(), f.marcas(), f.badges(),
                    f.subCategorias(), f.fits(), f.estampados(), f.escotes(), f.colorDominantes(),
                    rubros, gymrat, packs);
        }
    }

    /** One catalog row of {@code /api/data}. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ProductoRow {
        private String key;
        private String sitio;
        private String nombre;
        private double precio;
        private Double precioOrig;
        private boolean descuento;
        private String url;
        private String img;
        private String categoria;
        private String genero;
        private String marca;
        private String rubro;
        private boolean gymrat;
        private boolean marcaPremium;
        private int cantidadUnidades;
        private boolean esPack;
        private double precioUnitario;
        @JsonProperty("sub_categoria")
        private String subCategoria;
        private List<String> talles;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Ml ml;
        private Senal senal;
        private SenalFinanciacion senalFinanciacion;

        public static ProductoRow of(Product p, String presetActivoLabel) {
            String img = ProductJson.safe(p.imagenUrl());
            if (img.startsWith("//")) img = "https:" + img;
            var r = new ProductoRow();
            r.key = ProductKey.of(p.url());
            r.sitio = ProductJson.safe(p.sitio());
            r.nombre = ProductJson.safe(p.nombre());
            r.precio = p.precio();
            r.precioOrig = p.precioOriginal();
            r.descuento = p.tieneDescuento();
            r.url = ProductJson.safe(p.url());
            r.img = img;
            r.categoria = ProductJson.safe(p.categoria());
            r.genero = ProductJson.safe(p.genero());
            r.marca = ProductJson.safe(p.marca());
            r.rubro = p.rubro() != null ? p.rubro() : "indumentaria";
            r.gymrat = p.gymrat();
            r.marcaPremium = p.marcaPremium();
            r.cantidadUnidades = p.cantidadUnidades();
            r.esPack = p.esPack();
            r.precioUnitario = ProductJson.precioUnitario(p);
            r.subCategoria = ProductJson.safe(p.subCategoria());
            r.talles = p.talles() != null ? p.talles() : List.of();
            if (p.ml() != null) {
                var m = p.ml();
                r.ml = new Ml(m.badge() != null ? m.badge() : "",
                        m.badges() != null ? m.badges() : List.of(),
                        m.scoreP(), m.ofertaReal(),
                        m.tendencia() != null ? m.tendencia() : "estable",
                        m.pctilCategoria(), m.zScore(),
                        m.segment() != null ? m.segment() : "standard");
            }
            Product.SenalCompra s = p.senal() != null ? p.senal() : Product.SenalCompra.EMPTY;
            r.senal = new Senal(s.senal(), s.scoreCompra(), s.confianzaDeflactor().name().toLowerCase());
            Product.SenalFinanciacion f = p.finan() != null ? p.finan() : Product.SenalFinanciacion.EMPTY;
            r.senalFinanciacion = new SenalFinanciacion(f.senal(), f.ahorroReal(), f.vp(), presetActivoLabel);
            return r;
        }
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Ml {
        private String badge;
        private List<String> badges;
        private int scoreP;
        private boolean ofertaReal;
        private String tendencia;
        private int pctil;
        // Lombok would name this getter getZScore(), which Jackson exposes as "zscore".
        @JsonProperty("zScore")
        private double zScore;
        private String segment;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Senal {
        private String senal;
        private int scoreCompra;
        private String confianza;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SenalFinanciacion {
        private String senal;
        private double ahorroReal;
        private double vp;
        private String presetLabel;
    }

    /** {@code data} of {@code GET /api/producto/{key}}; both parts are built by dynamic JSON helpers. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ProductoDetalle {
        private ObjectNode producto;
        private JsonNode historial;
    }
}
