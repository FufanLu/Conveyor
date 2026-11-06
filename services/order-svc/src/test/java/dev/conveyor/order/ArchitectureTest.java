package dev.conveyor.order;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.conveyor.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..order.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..", "..order.application..", "..order.infrastructure..", "..order.api..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses()
            .that()
            .resideInAPackage("..order.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..order.infrastructure..", "..order.api..", "dev.conveyor.proto..");

    @ArchTest
    static final ArchRule apiGoesThroughTheApplication = noClasses()
            .that()
            .resideInAPackage("..order.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..order.infrastructure..");
}
