package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Write-only bridge from the live catalog to {@code producto_tech_specs}
 * (V35): parses every {@code rubro=tecnologia} product's name once per run
 * and hands the batch to {@link TechSpecsPort}. The builder itself never
 * reads through here — it keeps reading specs off the snapshot (D3d); this
 * class only feeds the future {@code /catalogo} specs filter (D3c).
 */
@Component
public class TechSpecsIndexer {

    private final TechSpecsPort port;

    public TechSpecsIndexer(TechSpecsPort port) {
        this.port = port;
    }

    public void indexar(List<Product> productos) {
        List<TechSpecsPort.SpecsDeProducto> specs = productos.stream()
                .filter(Product::esTech)
                .filter(p -> p.url() != null && !p.url().isBlank())
                .map(p -> new TechSpecsPort.SpecsDeProducto(
                        p.url(), TechSpecsParser.parse(p.nombre(), p.categoria())))
                .toList();
        if (specs.isEmpty()) return;
        port.upsertSpecs(specs);
    }
}
