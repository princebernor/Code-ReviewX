package com.codereviewx.ast.model;

/** A parser message. Locations are omitted (0) when the parser could not establish one. */
public record Diagnostic(String severity, String message, int line, int col, int endLine, int endCol) {
    public static Diagnostic error(String message, Span s) {
        return s == null ? new Diagnostic("error", message, 0, 0, 0, 0)
            : new Diagnostic("error", message, s.startLine(), s.startCol(), s.endLine(), s.endCol());
    }

    public static Diagnostic info(String message) { return new Diagnostic("info", message, 0, 0, 0, 0); }
}
