package dev.conveyor.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.conveyor.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..inventory.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "..inventory.application..", "..inventory.infrastructure..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses()
            .that()
            .resideInAPackage("..inventory.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..inventory.infrastructure..", "..inventory.grpc..", "..inventory.api..", "dev.conveyor.proto..");
}
