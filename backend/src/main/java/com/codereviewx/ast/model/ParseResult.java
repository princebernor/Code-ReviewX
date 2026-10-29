package com.codereviewx.ast.model;

import java.util.List;

/**
 * Output of a language module. {@code root} is present whenever the parser produced any tree, including a
 * partial one after syntax errors ({@code partial = true}); it is null only when nothing could be recovered.
 */
public record ParseResult(String language, String parser, SourceText source, UNode root, List<Diagnostic> diagnostics,
                          boolean partial) {
    public boolean hasTree() { return root != null; }
}
