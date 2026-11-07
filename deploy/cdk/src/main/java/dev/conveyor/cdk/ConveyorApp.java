package dev.conveyor.cdk;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/** Entry point: one stack per environment; account and region come from the CLI's profile. */
public final class ConveyorApp {

    private ConveyorApp() {}

    public static void main(String[] args) {
        App app = new App();
        String env = String.valueOf(
                app.getNode().tryGetContext("env") == null
                        ? "staging"
                        : app.getNode().tryGetContext("env"));
        new ConveyorStack(
                app,
                "Conveyor-" + env,
                StackProps.builder()
                        .description("Conveyor order-fulfillment platform (" + env + ")")
                        .env(Environment.builder()
                                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                                .region(System.getenv().getOrDefault("CDK_DEFAULT_REGION", "us-east-1"))
                                .build())
                        .build(),
                ConveyorStack.Sizing.forEnvironment(env));
        app.synth();
    }
}
