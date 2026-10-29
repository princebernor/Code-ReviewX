package com.codereviewx.ast.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.codereviewx.ast.model.SourceText;
import com.codereviewx.ast.model.UNode;

/** A replacement of the char range {@code [start, end)}. Suggestions are built from these, never from line guesses. */
public record Edit(int start, int end, String text) {
    public static Edit replace(UNode n, String text) { return new Edit(n.span().start(), n.span().end(), text); }

    public static Edit insertBefore(UNode n, String text) { return new Edit(n.span().start(), n.span().start(), text); }

    /** Applies the edits to the full lines they touch and returns those edited lines. */
    public static String applyToLines(SourceText src, List<Edit> edits) {
        int first = edits.stream().mapToInt(Edit::start).min().orElse(0);
        int last = edits.stream().mapToInt(e -> Math.max(e.start, e.end - 1)).max().orElse(0);
        int base = src.lineStart(src.lineOf(first));
        int lastLine = src.lineOf(Math.max(first, last));
        int stop = lastLine < src.lineCount() ? src.lineStart(lastLine + 1) - 1 : src.text().length();
        StringBuilder sb = new StringBuilder(src.slice(base, stop));
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit e : sorted) sb.replace(e.start - base, e.end - base, e.text);
        return sb.toString();
    }

    /** Lines covered by the edits, as they currently read. */
    public static String originalLines(SourceText src, List<Edit> edits) {
        int first = edits.stream().mapToInt(Edit::start).min().orElse(0);
        int last = edits.stream().mapToInt(e -> Math.max(e.start, e.end - 1)).max().orElse(0);
        return src.lines(src.lineOf(first), src.lineOf(Math.max(first, last)));
    }
}
