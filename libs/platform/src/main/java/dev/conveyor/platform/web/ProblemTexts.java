package dev.conveyor.platform.web;

/**
 * Client-facing {@code detail} texts shared by the servlet and reactive error handlers, so a
 * client sees the same words whichever stack answered. They never contain hosts, URLs,
 * exception messages or class names.
 */
final class ProblemTexts {

    static final String NO_RESOURCE = "No resource at this path.";
    static final String UNEXPECTED = "Unexpected server error.";
    static final String DEPENDENCY_UNAVAILABLE = "A dependency of this resource is unavailable. Retry shortly.";
    static final String DEPENDENCY_TIMEOUT = "A dependency of this resource did not answer in time.";

    private ProblemTexts() {}
}
