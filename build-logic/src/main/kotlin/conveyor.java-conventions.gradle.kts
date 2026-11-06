import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = catalog.findLibrary(alias).get()

fun ver(alias: String) = catalog.findVersion(alias).get().requiredVersion

group = "dev.conveyor"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(ver("java"))
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

val springBootBom = dependencies.platform(lib("spring-boot-dependencies"))

dependencies {
    implementation(springBootBom)
    annotationProcessor(springBootBom)
    errorprone(lib("errorprone-core"))

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

pluginManager.withPlugin("java-test-fixtures") {
    dependencies {
        "testFixturesImplementation"(springBootBom)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial,-this-escape", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
    }
}

jacoco {
    toolVersion = ver("jacoco")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The JaCoCo agent appends to the boot class path, which disables CDS and makes the JVM
    // print a warning for every test fork. Turn CDS off explicitly to keep the output clean.
    jvmArgs("-Xshare:off")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(ver("palantir-java-format"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
