package ar.scraper.web.cache;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.security.ActorResolver;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "ar.scraper", importOptions = ImportOption.DoNotIncludeTests.class)
class CacheUsageArchTest {

    @ArchTest
    static final ArchRule soElCacheAgrupa = noClasses()
        .that().resideInAPackage("ar.scraper.web..")
        .and().doNotHaveFullyQualifiedName(CatalogoDerivadoCache.class.getName())
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>(
                "call GroupingService.agrupar") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(GroupingService.class)
                    && call.getTarget().getName().equals("agrupar");
            }
        })
        .because("the grouping is cached per snapshot; a direct call recomputes it on every request");

    @ArchTest
    static final ArchRule cacheableSoEnElBeanDerivado = methods()
        .that().areAnnotatedWith(Cacheable.class)
        .or().areAnnotatedWith(CachePut.class)
        .or().areAnnotatedWith(CacheEvict.class)
        .should().beDeclaredInClassesThat().haveFullyQualifiedName(CatalogoDerivadoCache.class.getName())
        .because("cache annotations are inert on objects built with new, so they live on one Spring bean");

    @ArchTest
    static final ArchRule elCacheNuncaLeeAlUsuario = noClasses()
        .that().haveFullyQualifiedName(CatalogoDerivadoCache.class.getName())
        .should().dependOnClassesThat().haveFullyQualifiedName(ActorResolver.class.getName())
        .because("a per-user answer cached for the catalog would be served to everyone");

    @ArchTest
    static final ArchRule elBeanDerivadoEsPublicoYNoFinal = classes()
        .that().haveFullyQualifiedName(CatalogoDerivadoCache.class.getName())
        .should().bePublic().andShould().notBeInterfaces()
        .andShould(new com.tngtech.archunit.lang.ArchCondition<JavaClass>("not be final") {
            @Override
            public void check(JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                if (item.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.FINAL)) {
                    events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item, item.getName() + " is final"));
                }
            }
        });
}
