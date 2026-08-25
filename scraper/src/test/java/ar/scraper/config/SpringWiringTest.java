package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the dependency-injection graph: every collaborator a Spring bean asks
 * for must be something Spring can actually hand it.
 *
 * <p><b>Why this exists.</b> The application once shipped with a stray
 * {@code @Autowired} left sitting above a field that builds its own value:</p>
 *
 * <pre>
 *   &#64;Autowired
 *   private final SupplementCombo supplementCombo = new SupplementCombo();
 * </pre>
 *
 * <p>{@code SupplementCombo} carries no stereotype, so it is not a bean.
 * Spring refused to start the context — {@code APPLICATION FAILED TO START} —
 * while the whole suite stayed green, because not one test loads the Spring
 * context: every service is unit tested by calling its constructor directly.
 * 974 passing tests on an application that could not boot.</p>
 *
 * <p><b>Why not {@code @SpringBootTest}.</b> A real context load would catch
 * strictly more, and the surefire config already sets the {@code test} profile
 * "for any future @SpringBootTest". But this application's startup opens a
 * browser, fetches INDEC data over the network and runs Flyway, so booting it
 * per test run buys flakiness alongside the coverage. This check needs no
 * database, no network and no container, so it can never be skipped for
 * missing infrastructure — which matters, because "the tests were green" is
 * exactly what failed us here.</p>
 *
 * <p>Framework types are deliberately out of scope: only collaborators living
 * in {@code ar.scraper} are asserted on. A {@code DataSource} or an
 * {@code ObjectMapper} comes from auto-configuration and proves nothing about
 * our own wiring.</p>
 */
@Epic("Configuration")
@Feature("Dependency injection")
@Story("El grafo de beans es satisfacible")
@DisplayName("Spring wiring — todo colaborador inyectado es un bean de verdad")
class SpringWiringTest {

    private static final String PAQUETE_RAIZ = "ar.scraper";

    /** Concrete classes under ar.scraper that Spring will manage. */
    private static List<Class<?>> beansDeLaAplicacion() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        List<Class<?>> beans = new ArrayList<>();
        for (BeanDefinition definicion : scanner.findCandidateComponents(PAQUETE_RAIZ)) {
            Class<?> clase = Class.forName(definicion.getBeanClassName());
            if (esBean(clase)) beans.add(clase);
        }
        return beans;
    }

    /** True when the class carries @Component or any stereotype meta-annotated with it. */
    private static boolean esBean(Class<?> clase) {
        return AnnotatedElementUtils.hasAnnotation(clase, Component.class);
    }

    /** Our own types are the ones we can make claims about; framework types are not. */
    private static boolean esTipoPropio(Class<?> tipo) {
        return tipo.getName().startsWith(PAQUETE_RAIZ + ".");
    }

    /**
     * Whether Spring could satisfy a dependency on {@code tipo}.
     *
     * <p>Checking the requested type for a stereotype is not enough: injecting
     * an interface is satisfied by an annotated implementation, which is how
     * {@code CatalogAgentService} gets its {@code ChatProvider} — the interface
     * carries nothing, {@code OpenAiCompatProvider} carries {@code @Component}.
     * Asserting on the requested type alone would flag that seam as broken
     * while the application starts perfectly well.</p>
     */
    private static boolean resoluble(Class<?> tipo, List<Class<?>> beans) {
        if (esBean(tipo)) return true;
        return beans.stream().anyMatch(tipo::isAssignableFrom);
    }

    @Test
    @DisplayName("el scanner encuentra beans — si esto falla, el resto no prueba nada")
    void elScannerEncuentraBeans() throws Exception {
        // Sanity check: a silently empty scan would make every assertion below
        // vacuously true, which is the classic way a guard like this rots.
        assertThat(beansDeLaAplicacion())
                .as("beans encontrados bajo %s", PAQUETE_RAIZ)
                .hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("un bean con varios constructores dice explícitamente cuál usar")
    void unBeanConVariosConstructoresMarcaCual() throws Exception {
        // Con un solo constructor Spring lo elige sin ayuda. Con dos o más y
        // ninguno anotado, busca el vacío, no lo encuentra, y el contexto muere
        // con "No default constructor found" — un arranque roto que ningún test
        // unitario ve, porque cada colaborador se construye a mano.
        //
        // Pasó de verdad: PasswordResetService sumó un segundo constructor como
        // seam de test y la aplicación dejó de arrancar con la suite en verde.
        List<String> ambiguos = new ArrayList<>();

        for (Class<?> bean : beansDeLaAplicacion()) {
            Constructor<?>[] constructores = bean.getDeclaredConstructors();
            if (constructores.length < 2) continue;

            long anotados = java.util.Arrays.stream(constructores)
                    .filter(c -> c.isAnnotationPresent(Autowired.class))
                    .count();
            boolean hayVacio = java.util.Arrays.stream(constructores)
                    .anyMatch(c -> c.getParameterCount() == 0);

            if (anotados == 0 && !hayVacio) {
                ambiguos.add(String.format(
                        "%s tiene %d constructores y ninguno marcado con @Autowired",
                        bean.getSimpleName(), constructores.length));
            }
            if (anotados > 1) {
                ambiguos.add(String.format(
                        "%s marca %d constructores con @Autowired; sólo puede haber uno",
                        bean.getSimpleName(), anotados));
            }
        }

        assertThat(ambiguos)
                .as("[constructores que Spring no podría elegir]")
                .isEmpty();
    }

    @Test
    @DisplayName("ningún campo @Autowired apunta a una clase nuestra que no sea bean")
    void ningunCampoAutowiredApuntaAUnNoBean() throws Exception {
        List<Class<?>> beans = beansDeLaAplicacion();
        List<String> rotos = new ArrayList<>();

        for (Class<?> bean : beans) {
            for (Field campo : bean.getDeclaredFields()) {
                if (!campo.isAnnotationPresent(Autowired.class)) continue;
                Class<?> tipo = campo.getType();
                if (!esTipoPropio(tipo) || resoluble(tipo, beans)) continue;
                rotos.add(String.format(
                        "%s.%s es @Autowired de tipo %s, que no tiene estereotipo Spring",
                        bean.getSimpleName(), campo.getName(), tipo.getSimpleName()));
            }
        }

        assertThat(rotos)
                .as("campos @Autowired que Spring no podría resolver")
                .isEmpty();
    }

    @Test
    @DisplayName("ningún constructor de un bean pide una clase nuestra que no sea bean")
    void ningunConstructorPideUnNoBean() throws Exception {
        List<Class<?>> beans = beansDeLaAplicacion();
        List<String> rotos = new ArrayList<>();

        for (Class<?> bean : beans) {
            Constructor<?>[] constructores = bean.getDeclaredConstructors();
            // Con varios constructores, Spring elige por @Autowired o por el
            // único disponible; acá solo se mira el caso inequívoco.
            if (constructores.length != 1) continue;

            for (Class<?> parametro : constructores[0].getParameterTypes()) {
                if (!esTipoPropio(parametro) || resoluble(parametro, beans)) continue;
                rotos.add(String.format(
                        "el constructor de %s pide %s, que no tiene estereotipo Spring",
                        bean.getSimpleName(), parametro.getSimpleName()));
            }
        }

        assertThat(rotos)
                .as("parámetros de constructor que Spring no podría resolver")
                .isEmpty();
    }

    @Test
    @DisplayName("un campo @Autowired que se construye solo es siempre un error")
    void ningunCampoAutowiredSeConstruyeSolo() throws Exception {
        // El caso exacto que rompió el arranque: @Autowired sobre un `final`
        // con inicializador. Aunque el tipo LLEGARA a ser un bean, la anotación
        // no tiene sentido — el valor ya está puesto — y delata que quedó
        // colgada de un campo anterior. Spring falla sobre `final`.
        List<String> sospechosos = new ArrayList<>();

        for (Class<?> bean : beansDeLaAplicacion()) {
            for (Field campo : bean.getDeclaredFields()) {
                if (!campo.isAnnotationPresent(Autowired.class)) continue;
                if (java.lang.reflect.Modifier.isFinal(campo.getModifiers())) {
                    sospechosos.add(String.format("%s.%s es @Autowired y final",
                            bean.getSimpleName(), campo.getName()));
                }
            }
        }

        assertThat(sospechosos)
                .as("campos @Autowired declarados final")
                .isEmpty();
    }
}
