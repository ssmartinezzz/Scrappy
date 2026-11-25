package ar.scraper.pcs;

import ar.scraper.model.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a build memoizes per catalog list (categories, pools, parsed specs) must never change a build: a warm memo, a
 * cold one, another list, a concurrent request and a dropped memo all give the same {@link PcBuild}.
 */
class PcBuilderMemoTest {

    private static final String[][] PLANTILLAS = {
        {"Motherboard", "Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", "Motherboard Gigabyte B550M AM4 DDR4",
            "Motherboard MSI PRO Z790-P WiFi LGA1700 DDR5", "Motherboard ASRock A620M AM5 DDR5 Combo + Procesador"},
        {"CPU", "Procesador Amd Ryzen 9 7900 Am5", "Procesador Intel Core i5 12400F LGA1700",
            "Procesador AMD Ryzen 5 5600 AM4", "Procesador Intel Core i7 14700K LGA1700 outlet"},
        {"RAM", "Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", "Memoria RAM Kingston DDR4 16GB 3200MHz",
            "Memoria RAM Kingston Fury DDR5 32GB (2x16GB) 5600MHz"},
        {"Fuente", "Fuente Antec 750W 80 Plus Bronze ATX 3.1", "Fuente Corsair 550W 80 Plus", "Fuente Gigabyte 1000W 80 Plus Gold"},
        {"Gabinete", "Gabinete Corsair 4000D ATX", "Gabinete Cooler Master MasterBox Q300L MATX",
            "Gabinete NZXT H1 Mini ITX"},
        {"Almacenamiento", "SSD Kingston NV2 1TB", "Disco Rigido Seagate 2TB HDD", "SSD WD Black SN850X 2TB NVMe"},
        {"GPU", "Placa de Video Nvidia GeForce RTX 4070 12GB", "Placa de Video Nvidia GeForce RTX 5060 8GB",
            "Placa de Video AMD Radeon RX 7800 XT 16GB"},
        {"Cooler", "Cooler CPU Be Quiet! SILENT LOOP 3 420mm Water Cooler", "Cooler CPU Cooler Master Hyper 212 aire"},
        {"Mini PC", "Mini PC Intel N100 16GB 512GB", "Mini PC AMD Ryzen 7 8845HS 32GB"},
    };

    private final PcBuilder builder = new PcBuilder();

    @AfterEach
    void soltar() {
        PcBuilder.soltarMemo();
    }

    private static Product producto(String nombre, double precio, String categoria, String url) {
        return Product.builder().sitio("TestSitio").nombre(nombre).precio(precio).precioOriginal(null).url(url)
                .imagenUrl("https://img/test.jpg").categoria(categoria).genero("").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("").rubro("tecnologia").gymrat(false).build();
    }

    private static List<Product> catalogo(long semilla, int cuantos) {
        Random r = new Random(semilla);
        List<Product> lista = new ArrayList<>();
        for (int i = 0; i < cuantos; i++) {
            String[] fila = PLANTILLAS[r.nextInt(PLANTILLAS.length)];
            String nombre = fila[1 + r.nextInt(fila.length - 1)] + (r.nextInt(4) == 0 ? " v" + r.nextInt(5) : "");
            String url = r.nextInt(20) == 0 ? "https://t/repetida" : "https://t/" + semilla + "/" + i;
            lista.add(producto(nombre, 20_000 + r.nextInt(900) * 1_000, fila[0], url));
            if (r.nextInt(25) == 0) lista.add(producto("Producto de otra cosa " + i, 1_000, "Mouse", "https://t/m" + i));
        }
        return lista;
    }

    private record Pedido(double presupuesto, boolean conGpu, Set<String> excluir, Gama gama,
            PreferenciasDeArmado prefs, Uso uso, ModoPresupuesto modo) {}

    private static List<Pedido> pedidos(List<Product> lista, long semilla) {
        Random r = new Random(semilla * 31);
        List<String> urls = lista.stream().map(Product::url).distinct().collect(Collectors.toList());
        Set<String> todosLosCpu = lista.stream().filter(p -> "CPU".equals(p.categoria())).map(Product::url)
                .collect(Collectors.toSet());
        List<Pedido> out = new ArrayList<>();
        out.add(new Pedido(0, false, Set.of(), null, PreferenciasDeArmado.NINGUNA, Uso.GAMING, ModoPresupuesto.AHORRAR));
        out.add(new Pedido(0, true, todosLosCpu, Gama.ALTA, PreferenciasDeArmado.NINGUNA, Uso.GAMING,
                ModoPresupuesto.AHORRAR));
        for (int i = 0; i < 10; i++) {
            Set<String> excluir = new HashSet<>();
            for (int j = r.nextInt(8); j > 0; j--) excluir.add(urls.get(r.nextInt(urls.size())));
            if (r.nextInt(3) == 0) excluir.add("https://t/no-existe");
            Gama gama = new Gama[]{null, Gama.BAJA, Gama.MEDIA, Gama.ALTA}[r.nextInt(4)];
            PreferenciasDeArmado prefs = r.nextInt(3) == 0
                    ? new PreferenciasDeArmado("DDR5", null, null, null, null, null, null, null, null, null, null)
                    : PreferenciasDeArmado.NINGUNA;
            Uso uso = r.nextInt(4) == 0 ? Uso.HOMELAB : Uso.GAMING;
            if (uso == Uso.HOMELAB && r.nextBoolean()) {
                prefs = new PreferenciasDeArmado(null, null, null, null, null, null, null, TamanioGabinete.MINI, null,
                        null, null);
            }
            ModoPresupuesto modo = r.nextInt(3) == 0 ? ModoPresupuesto.GASTAR_TODO : ModoPresupuesto.AHORRAR;
            out.add(new Pedido(new double[]{0, 600_000, 1_500_000, 4_000_000}[r.nextInt(4)], r.nextBoolean(),
                    excluir, gama, prefs, uso, modo));
        }
        return out;
    }

    private PcBuild armar(List<Product> lista, Pedido p) {
        return builder.armar(lista, p.presupuesto(), p.conGpu(), p.excluir(), p.gama(), p.prefs(), p.uso(), p.modo());
    }

    @Test
    void aWarmMemoAColdOneAndACopyOfTheListBuildTheSame() {
        int comparados = 0;
        int conPicks = 0;
        for (long semilla = 1; semilla <= 12; semilla++) {
            List<Product> lista = List.copyOf(catalogo(semilla, 150 + (int) semilla * 10));
            for (Pedido pedido : pedidos(lista, semilla)) {
                PcBuilder.soltarMemo();
                PcBuild frio = armar(lista, pedido);
                PcBuild tibio = armar(lista, pedido);
                PcBuild otraLista = armar(new ArrayList<>(lista), pedido);
                PcBuild devuelta = armar(lista, pedido);

                assertThat(tibio).as("semilla %d %s", semilla, pedido).isEqualTo(frio);
                assertThat(otraLista).as("semilla %d %s", semilla, pedido).isEqualTo(frio);
                assertThat(devuelta).as("semilla %d %s", semilla, pedido).isEqualTo(frio);
                comparados++;
                if (frio.picks().size() >= 5) conPicks++;
            }
        }
        assertThat(comparados).isEqualTo(12 * 12);
        assertThat(conPicks).isGreaterThan(comparados / 2);
    }

    @Test
    void alternatingBetweenListsNeverServesTheOtherListsMemo() {
        List<Product> a = List.copyOf(catalogo(100, 220));
        List<Product> b = List.copyOf(catalogo(200, 260));
        List<Pedido> pa = pedidos(a, 100);
        List<Pedido> pb = pedidos(b, 200);
        List<PcBuild> esperadoA = new ArrayList<>();
        List<PcBuild> esperadoB = new ArrayList<>();
        for (Pedido p : pa) esperadoA.add(armar(new ArrayList<>(a), p));
        for (Pedido p : pb) esperadoB.add(armar(new ArrayList<>(b), p));

        for (int vuelta = 0; vuelta < 3; vuelta++) {
            for (int i = 0; i < pa.size(); i++) {
                assertThat(armar(a, pa.get(i))).isEqualTo(esperadoA.get(i));
                assertThat(armar(b, pb.get(i))).isEqualTo(esperadoB.get(i));
            }
        }
    }

    @Test
    void concurrentBuildsOverTwoListsAndDroppedMemosBuildTheSame() throws Exception {
        List<Product> a = List.copyOf(catalogo(300, 240));
        List<Product> b = List.copyOf(catalogo(400, 240));
        List<Pedido> pa = pedidos(a, 300);
        List<Pedido> pb = pedidos(b, 400);
        List<PcBuild> esperadoA = new ArrayList<>();
        List<PcBuild> esperadoB = new ArrayList<>();
        for (Pedido p : pa) esperadoA.add(armar(new ArrayList<>(a), p));
        for (Pedido p : pb) esperadoB.add(armar(new ArrayList<>(b), p));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futuros = new ArrayList<>();
            for (int hilo = 0; hilo < 8; hilo++) {
                final int semilla = hilo;
                Callable<Boolean> tarea = () -> {
                    Random r = new Random(semilla);
                    for (int i = 0; i < 40; i++) {
                        boolean primera = r.nextBoolean();
                        int k = r.nextInt(pa.size());
                        if (r.nextInt(10) == 0) PcBuilder.soltarMemo();
                        PcBuild obtenido = primera ? armar(a, pa.get(k)) : armar(b, pb.get(k));
                        if (!obtenido.equals(primera ? esperadoA.get(k) : esperadoB.get(k))) return false;
                    }
                    return true;
                };
                futuros.add(pool.submit(tarea));
            }
            for (Future<Boolean> f : futuros) assertThat(f.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aListThatGrewIsNotServedFromTheMemoOfItsOldSize() {
        List<Product> lista = new ArrayList<>(List.of(
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu-1"),
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb")));
        builder.armar(lista, 0, false, Set.of());
        lista.add(producto("Procesador Amd Ryzen 5 5600 AM4", 100_000, "CPU", "https://t/cpu-2"));

        PcBuild despues = builder.armar(lista, 0, false, Set.of());

        assertThat(despues).isEqualTo(new PcBuilder().armar(new ArrayList<>(lista), 0, false, Set.of()));
        assertThat(despues.picks()).extracting(PcPick::url).contains("https://t/cpu-1");
    }

    @Test
    void theMemoDoesNotKeepTheCatalogListAlive() throws Exception {
        WeakReference<List<Product>> debil = construirYArmar();

        for (int i = 0; i < 20 && debil.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }

        assertThat(debil.get()).isNull();
    }

    private WeakReference<List<Product>> construirYArmar() {
        List<Product> lista = new ArrayList<>(catalogo(500, 200));
        builder.armar(lista, 0, true, Set.of());
        return new WeakReference<>(lista);
    }
}
