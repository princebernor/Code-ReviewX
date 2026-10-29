package com.codereviewx.ast.model;

/** Source range: 1-based lines/columns (end column exclusive) plus char offsets {@code [start, end)}. */
public record Span(int startLine, int startCol, int endLine, int endCol, int start, int end) {
    public static Span of(SourceText src, int start, int end) {
        int e = Math.max(start, end);
        return new Span(src.lineOf(start), src.colOf(start), src.lineOf(Math.max(start, e)), src.colOf(e), start, e);
    }

    public boolean contains(Span o) { return start <= o.start && o.end <= end; }
}
