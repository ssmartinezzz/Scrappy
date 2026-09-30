package ar.scraper.security;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Route policy")
@Story("El conjunto de rutas vivas no cambia sin que alguien lo decida")
@DisplayName("Rutas vivas — el conjunto (método, path) coincide con el registrado")
class LiveRouteSetTest {

    @Test
    @DisplayName("repartir handlers entre controllers no agrega ni quita rutas")
    void elConjuntoDeRutasEsElRegistrado() throws IOException {
        var recurso = LiveRouteSetTest.class.getResourceAsStream("rutas-vivas.txt");
        assertThat(recurso).as("rutas-vivas.txt").isNotNull();
        List<String> esperadas = new String(recurso.readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank()).toList();

        var vivas = LiveRoutes.todas().stream()
                .map(r -> r.metodo().name() + " " + r.path())
                .toList();

        assertThat(vivas).as("una ruta mapeada dos veces es un arranque roto").doesNotHaveDuplicates();
        assertThat(new TreeSet<>(vivas)).containsExactlyInAnyOrderElementsOf(new TreeSet<>(esperadas));
    }
}
