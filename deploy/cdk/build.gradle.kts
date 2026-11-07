// deploy/cdk: Conveyor's AWS infrastructure as a CDK app in Java. `./gradlew :deploy:cdk:synth`
// writes the CloudFormation templates to build/cdk.out; the tests assert the synthesized
// template (no AWS account needed). `cdk deploy --app "./gradlew -q :deploy:cdk:run"` deploys.
plugins {
    id("conveyor.java-conventions")
    application
}

dependencies {
    implementation(libs.aws.cdk.lib)
    implementation(libs.constructs)
    // CDK's generated Java classes carry JetBrains nullability annotations.
    compileOnly("org.jetbrains:annotations:26.0.2")
    testCompileOnly("org.jetbrains:annotations:26.0.2")
}

application {
    mainClass = "dev.conveyor.cdk.ConveyorApp"
}

tasks.register<JavaExec>("synth") {
    group = "deploy"
    description = "Synthesizes the CloudFormation templates into build/cdk.out."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.conveyor.cdk.ConveyorApp"
    environment("CDK_OUTDIR", layout.buildDirectory.dir("cdk.out").get().asFile.absolutePath)
}
