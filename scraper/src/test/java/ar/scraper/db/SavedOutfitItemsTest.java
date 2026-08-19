package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V14 — los ítems de un outfit guardado son filas, no un blob.
 *
 * <p>Lo que estos tests fijan no es la forma de la tabla: es lo que la forma
 * HABILITA y el blob impedía. Preguntarle a la base qué outfits contienen un
 * producto era imposible con un JSON adentro de una columna, y es exactamente
 * la pregunta que hay que poder hacer cuando `saved_outfits` gane un usuario.</p>
 *
 * <p>Semántica FOTO + precio actual: la fila guarda lo que el producto ERA al
 * guardarse, y {@code precioActual} sale por LEFT JOIN del catálogo vivo.</p>
 */
@Epic("Persistence")
@Feature("Multi-value normalization")
@Story("saved_outfit_item — V14")
@DisplayName("V14 — un outfit guardado se puede consultar por producto")
class SavedOutfitItemsTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        db.upsertProductos(List.of(
                producto("https://s.com/remera", "Remera", 15000),
                producto("https://s.com/short", "Short", 8000)));
    }

    private int guardarOutfit(String nombre, String... urls) {
        StringBuilder slots = new StringBuilder("[");
        for (int i = 0; i < urls.length; i++) {
            if (i > 0) slots.append(",");
            slots.append("{\"slot\":\"torso\",\"url\":\"").append(urls[i])
                 .append("\",\"sitio\":\"Sitio\",\"nombre\":\"Producto\",\"precio\":15000,")
                 .append("\"img\":\"i\",\"categoria\":\"Remera\",\"marca\":\"Nike\"}");
        }
        return db.guardarOutfit(yo(), nombre, slots.append("]").toString(), null, 15000);
    }

    @Test
    @DisplayName("Se puede preguntar qué outfits guardados contienen un producto")
    void queOutfitsContienenEsteProducto() throws Exception {
        guardarOutfit("Con remera", "https://s.com/remera");
        guardarOutfit("Con short", "https://s.com/short");
        guardarOutfit("Con las dos", "https://s.com/remera", "https://s.com/short");

        assertThat(nombresDeOutfitsCon("https://s.com/remera"))
                .containsExactlyInAnyOrder("Con remera", "Con las dos");
    }

    @Test
    @DisplayName("Los ítems vuelven en orden y con la foto de cuando se guardaron")
    void losItemsVuelvenConSuFoto() {
        guardarOutfit("Mi outfit", "https://s.com/remera", "https://s.com/short");

        List<Map<String, Object>> items = slotsDe("Mi outfit");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("url")).isEqualTo("https://s.com/remera");
        assertThat(items.get(0).get("precio")).isEqualTo(15000.0);
        assertThat(items.get(0).get("slot")).isEqualTo("torso");
        assertThat(items.get(1).get("url")).isEqualTo("https://s.com/short");
    }

    @Test
    @DisplayName("precioActual trae el precio de HOY, distinto del precio guardado")
    void precioActualEsElDeHoy() {
        guardarOutfit("Mi outfit", "https://s.com/remera");

        // El producto sube de precio DESPUÉS de guardado.
        db.upsertProductos(List.of(
                producto("https://s.com/remera", "Remera", 22000),
                producto("https://s.com/short", "Short", 8000)));

        Map<String, Object> item = slotsDe("Mi outfit").get(0);
        assertThat(item.get("precio")).isEqualTo(15000.0);       // la foto
        assertThat(item.get("precioActual")).isEqualTo(22000.0); // hoy
    }

    @Test
    @DisplayName("Un producto que ya no existe no rompe el outfit: precioActual queda null")
    void productoBorradoNoRompeElOutfit() throws Exception {
        guardarOutfit("Mi outfit", "https://s.com/remera");

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM productos WHERE url=?")) {
            ps.setString(1, "https://s.com/remera");
            ps.executeUpdate();
        }

        Map<String, Object> item = slotsDe("Mi outfit").get(0);
        assertThat(item.get("nombre")).isEqualTo("Producto");
        assertThat(item.get("precio")).isEqualTo(15000.0);
        assertThat(item.get("precioActual")).isNull();
    }

    @Test
    @DisplayName("Borrar el outfit se lleva sus ítems por CASCADE")
    void borrarElOutfitCascadeaSusItems() throws Exception {
        int id = guardarOutfit("Mi outfit", "https://s.com/remera");
        assertThat(contarItems(id)).isEqualTo(1);

        db.eliminarOutfitGuardado(yo(), id);

        assertThat(contarItems(id)).isZero();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> slotsDe(String nombre) {
        return db.obtenerOutfitsGuardados(yo()).stream()
                .filter(o -> nombre.equals(o.get("nombre")))
                .map(o -> (List<Map<String, Object>>) o.get("slots"))
                .findFirst().orElseThrow();
    }

    private List<String> nombresDeOutfitsCon(String url) throws Exception {
        List<String> nombres = new java.util.ArrayList<>();
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT o.nombre FROM saved_outfits o "
                             + "JOIN saved_outfit_item i ON i.outfit_id = o.id WHERE i.url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) nombres.add(rs.getString(1));
            }
        }
        return nombres;
    }

    private int contarItems(int outfitId) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM saved_outfit_item WHERE outfit_id=?")) {
            ps.setInt(1, outfitId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private Product producto(String url, String categoria, double precio) {
        return new Product("Sitio", "Producto", precio, null, url, "http://img.example/x.jpg",
                categoria, "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    /**
     * The owner every personal read and write is scoped by since slice 8.
     *
     * <p>A method rather than a field: {@code PostgresTestBase} truncates between
     * tests, so a cached id would point at a row that no longer exists. Seeding is
     * idempotent, so calling it repeatedly costs three cheap queries and is always
     * correct.</p>
     */
    private UUID yo() {
        return UsuarioDePrueba.yo(dataSource());
    }
}
