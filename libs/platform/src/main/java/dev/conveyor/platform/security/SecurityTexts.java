package dev.conveyor.platform.security;

/** Client-facing details for authentication and authorization failures, identical on both stacks. */
final class SecurityTexts {

    static final String UNAUTHENTICATED = "A valid bearer token is required for this operation.";
    static final String FORBIDDEN = "The token does not grant the role this operation requires.";

    private SecurityTexts() {}
}
