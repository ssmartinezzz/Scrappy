package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TechSpecsIndexer} — pure, against a mock {@link TechSpecsPort}: what
 * reaches the port, and what never does. Molde: {@code PcBuilderTest}'s
 * {@code producto(...)} helper.
 */
class TechSpecsIndexerTest {

    private final TechSpecsPort port = mock(TechSpecsPort.class);
    private final TechSpecsIndexer indexer = new TechSpecsIndexer(port);

    private Product producto(String nombre, String categoria, String rubro, String url) {
        return new Product("TestSitio", nombre, 1000, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", rubro, false);
    }

    @Test
    @DisplayName("empty catalog never calls the port")
    void emptyCatalogoNeverCallsThePort() {
        indexer.indexar(List.of());

        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("a non-tech product is skipped")
    void nonTechProductIsSkipped() {
        Product remera = producto("Remera Dry Fit", "Remera", "indumentaria", "https://t/remera");

        indexer.indexar(List.of(remera));

        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("a tech product with a blank url is skipped")
    void techProductWithBlankUrlIsSkipped() {
        Product sinUrl = producto("AMD Ryzen 5 5600X", "CPU", "tecnologia", "");

        indexer.indexar(List.of(sinUrl));

        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("a tech product reaches the port already parsed")
    void techProductReachesThePortParsed() {
        Product cpu = producto("AMD Ryzen 5 5600X AM4", "CPU", "tecnologia", "https://t/cpu");

        indexer.indexar(List.of(cpu));

        TechSpecs esperado = TechSpecsParser.parse(cpu.nombre(), cpu.categoria());
        verify(port).upsertSpecs(argThat(batch ->
                batch.size() == 1
                        && batch.get(0).url().equals("https://t/cpu")
                        && batch.get(0).specs().equals(esperado)));
    }

    @Test
    @DisplayName("an all-abstention spec is still written — parsed, nothing readable, not skipped")
    void allAbstentionSpecIsStillWritten() {
        Product monitor = producto("Monitor Samsung 24 pulgadas", "Monitor", "tecnologia", "https://t/monitor");
        assertThat(TechSpecsParser.parse(monitor.nombre(), monitor.categoria())).isEqualTo(TechSpecs.EMPTY);

        indexer.indexar(List.of(monitor));

        verify(port).upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto("https://t/monitor", TechSpecs.EMPTY)));
    }

    @Test
    @DisplayName("only tech products with a non-blank url reach the batch, mixed catalog")
    void onlyTechAndNonBlankUrlProductsReachTheBatch() {
        Product cpu = producto("Intel Core i5-12400F", "CPU", "tecnologia", "https://t/cpu");
        Product remera = producto("Remera", "Remera", "indumentaria", "https://t/remera");
        Product sinUrl = producto("Motherboard MSI B550", "Motherboard", "tecnologia", "");

        indexer.indexar(List.of(cpu, remera, sinUrl));

        verify(port).upsertSpecs(argThat(batch ->
                batch.size() == 1 && batch.get(0).url().equals("https://t/cpu")));
    }
}
