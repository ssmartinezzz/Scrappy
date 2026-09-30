package ar.scraper.web;

import ar.scraper.web.support.Wire;

import ar.scraper.indices.IndiceService;

import ar.scraper.catalog.ProductPort;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.favoritos.FavoritosPort;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import ar.scraper.web.support.SujetoDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Favoritos")
@DisplayName("FavoritosController — Favoritos endpoints")
class FavoritosControllerTest {

    private ScraperService service;
    private IndiceService indiceService;
    private ScraperConfig config;
    private DatabaseService db;
    private FavoritosPort favoritosPort;
    private ProductPort productos;
    private FavoritosController controller;
    private CatalogoController catalogo;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire FavoritosController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        indiceService      = mock(IndiceService.class);
        config                = mock(ScraperConfig.class);
        db                    = mock(DatabaseService.class);
        favoritosPort         = mock(FavoritosPort.class);
        productos             = mock(ProductPort.class);
        when(db.favoritos()).thenReturn(favoritosPort);
        when(db.productos()).thenReturn(productos);
        SujetoDePrueba.entrar("ADMIN");
        controller = new FavoritosController(db.favoritos(), db.productos(), new ar.scraper.security.ActorResolver());
        catalogo = new CatalogoController(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(), config, indiceService);
    }

    // ── GET /api/favoritos ───────────────────────────────────────────────

    @Test
    @Story("GET /api/favoritos")
    void getFavoritosReturnsEmptyArrayWhenNoFavorites() {
        givenNoFavoritos();

        var resp = controller.getFavoritos();
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(0);
    }

    @Step("Stub DB with no favoritos")
    private void givenNoFavoritos() {
        when(favoritosPort.listarFavoritos(any())).thenReturn(List.of());
    }

    @Test
    @Story("GET /api/favoritos")
    void getFavoritosReturnsFavoritosWithAllFields() {
        String url = "https://sporting.com.ar/zapatillas-1";
        givenActiveFavoritoRow(url);

        var resp = controller.getFavoritos();
        JsonNode body = Wire.data(resp);

        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).get("url").asText()).isEqualTo(url);
        assertThat(body.get(0).get("sitio").asText()).isEqualTo("Sporting");
        assertThat(body.get(0).get("descontinuado").asBoolean()).isFalse();
    }

    @Step("Stub DB with an active favorito row for {url}")
    private void givenActiveFavoritoRow(String url) {
        var row = Map.of(
                "url", url, "sitio", "Sporting", "nombre", "Zapatillas Nike",
                "added_at", "2025-01-01", "last_checked_at", "2025-01-15");
        when(favoritosPort.listarFavoritos(any())).thenReturn(List.of(row));
        when(productos.obtenerProducto(url)).thenReturn(Optional.<Product>empty());
        when(productos.esProductoActivo(url)).thenReturn(true);
    }

    @Test
    @Story("GET /api/favoritos")
    void getFavoritosMarksDiscontinuedWhenProductInactive() {
        String url = "https://sitio.com/p/1";
        givenInactiveFavoritoRow(url);

        var resp = controller.getFavoritos();
        JsonNode body = Wire.data(resp);

        assertThat(body.get(0).get("descontinuado").asBoolean()).isTrue();
    }

    @Step("Stub DB with an inactive (discontinued) favorito row for {url}")
    private void givenInactiveFavoritoRow(String url) {
        var row = Map.of("url", url, "sitio", "S", "nombre", "N",
                "added_at", "2025-01-01", "last_checked_at", "2025-01-01");
        when(favoritosPort.listarFavoritos(any())).thenReturn(List.of(row));
        when(productos.obtenerProducto(url)).thenReturn(Optional.<Product>empty());
        when(productos.esProductoActivo(url)).thenReturn(false);
    }

    // ── POST /api/favoritos ──────────────────────────────────────────────

    @Test
    @Story("POST /api/favoritos")
    void addFavoritoReturns400WhenUrlBlank() {
        Allure.parameter("url", "");
        Allure.parameter("sitio", "Sporting");

        var resp = addFavorito("", "Sporting", null);
        JsonNode error = Wire.error(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(error.get("code").asText()).isEqualTo("solicitud_invalida");
        verify(favoritosPort, never()).guardarFavorito(any(), any(), any(), any());
    }

    @Test
    @Story("POST /api/favoritos")
    void addFavoritoReturns400WhenSitioBlank() {
        Allure.parameter("url", "https://a.com/1");
        Allure.parameter("sitio", "");

        var resp = addFavorito("https://a.com/1", "", null);
        JsonNode error = Wire.error(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(error.get("code").asText()).isEqualTo("solicitud_invalida");
    }

    @Test
    @Story("POST /api/favoritos")
    void addFavoritoReturns200AndPersistsWhenValid() {
        var resp = addFavorito("https://a.com/1", "Sporting", "Nike Air Max");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(favoritosPort).guardarFavorito(any(), eq("https://a.com/1"), eq("Sporting"), eq("Nike Air Max"));
    }

    @Step("Add favorito: url={url}, sitio={sitio}, nombre={nombre}")
    private org.springframework.http.ResponseEntity<?> addFavorito(String url, String sitio, String nombre) {
        var payload = nombre == null
                ? Map.of("url", url, "sitio", sitio)
                : Map.of("url", url, "sitio", sitio, "nombre", nombre);
        return Wire.answer(() -> controller.addFavorito(payload));
    }

    // ── DELETE /api/favoritos ────────────────────────────────────────────

    @Test
    @Story("DELETE /api/favoritos")
    void deleteFavoritoAlwaysReturnsOkAndCallsDb() {
        var resp = deleteFavorito("https://a.com/1");
        JsonNode body = Wire.data(resp);

        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(favoritosPort).eliminarFavorito(any(), eq("https://a.com/1"));
    }

    @Step("Delete favorito {url}")
    private org.springframework.http.ResponseEntity<?> deleteFavorito(String url) {
        return controller.deleteFavorito(url);
    }

    // ── DELETE /api/data ─────────────────────────────────────────────────

    @Test
    @Story("DELETE /api/data")
    void eliminarProductoCallsDbAndServiceAndReturnsOk() {
        String url = "https://a.com/1";

        var resp = catalogo.eliminarProducto(url);
        JsonNode body = Wire.data(resp);

        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(productos).marcarDescontinuado(url);
        verify(service).eliminarProductoDeMemoria(url);
    }
}
