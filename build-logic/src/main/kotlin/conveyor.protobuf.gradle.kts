// Protobuf + gRPC code generation. protoc and protoc-gen-grpc-java are pinned in the version
// catalog to the versions the Spring Boot BOM manages for the runtime libraries.
plugins {
    id("conveyor.java-conventions")
    id("com.google.protobuf")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

dependencies {
    api("com.google.protobuf:protobuf-java")
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
}

protobuf {
    protoc {
        artifact = catalog.findLibrary("protoc").get().get().toString()
    }
    plugins {
        create("grpc") {
            artifact = catalog.findLibrary("protoc-gen-grpc-java").get().get().toString()
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc") {
                    option("@generated=omit")
                }
            }
        }
    }
}

// Generated code is not ours to keep warning-free.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.remove("-Werror")
}
