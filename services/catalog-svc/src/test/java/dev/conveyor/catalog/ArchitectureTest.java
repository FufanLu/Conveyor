package dev.conveyor.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Dependencies point inwards: api and infrastructure depend on application and domain, never
 * the other way round. The domain does not know Spring exists.
 */
@AnalyzeClasses(packages = "dev.conveyor.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..catalog.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "..catalog.application..",
                    "..catalog.infrastructure..",
                    "..catalog.api..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowAdapters = noClasses()
            .that()
            .resideInAPackage("..catalog.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..catalog.infrastructure..", "..catalog.api..");

    @ArchTest
    static final ArchRule apiGoesThroughTheApplication = noClasses()
            .that()
            .resideInAPackage("..catalog.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..catalog.infrastructure..");

    @ArchTest
    static final ArchRule nothingDependsOnTheApi = noClasses()
            .that()
            .resideOutsideOfPackage("..catalog.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..catalog.api..");
}
