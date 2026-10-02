package ar.scraper.pcs;

import ar.scraper.model.Product;

import java.util.List;
import org.apache.commons.lang3.StringUtils;
import lombok.RequiredArgsConstructor;

/**
 * The builder itself never reads through here — it keeps reading specs off the snapshot; this class
 * only feeds the future {@code /catalogo} specs filter.
 */
@RequiredArgsConstructor
public class TechSpecsIndexer {

    private final TechSpecsPort port;

    public void indexar(List<Product> productos) {
        List<TechSpecsPort.SpecsDeProducto> specs = productos.stream()
                .filter(Product::esTech)
                .filter(p -> StringUtils.isNotBlank(p.url()))
                .map(p -> new TechSpecsPort.SpecsDeProducto(
                        p.url(), p.categoria(), TechSpecsParser.parse(p.nombre(), p.categoria())))
                .toList();
        if (specs.isEmpty()) return;
        port.upsertSpecs(specs);
    }
}
