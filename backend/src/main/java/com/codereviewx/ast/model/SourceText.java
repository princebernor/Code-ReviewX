package com.codereviewx.ast.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Normalized source (LF line endings) with line/offset lookups. Lines and columns are 1-based. */
public final class SourceText {
    private final String text;
    private final int[] lineStarts;
    private final List<String> lines;
    private int[] byteToChar; // lazily built; null while the text is pure ASCII

    public SourceText(String text) {
        this.text = text;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        this.lines = List.of(text.split("\n", -1));
    }

    public String text() { return text; }

    public int lineCount() { return lines.size(); }

    public List<String> lines() { return lines; }

    /** Line {@code n} (1-based) without its newline, or "" when out of range. */
    public String line(int n) { return n >= 1 && n <= lines.size() ? lines.get(n - 1) : ""; }

    /** Lines {@code from..to} inclusive, joined with LF. */
    public String lines(int from, int to) {
        int a = Math.max(1, from), b = Math.min(to, lines.size());
        return a > b ? "" : String.join("\n", lines.subList(a - 1, b));
    }

    public String slice(int start, int end) {
        int s = Math.max(0, Math.min(start, text.length())), e = Math.max(s, Math.min(end, text.length()));
        return text.substring(s, e);
    }

    /** 1-based line containing char offset. */
    public int lineOf(int offset) {
        int i = Arrays.binarySearch(lineStarts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }

    /** 1-based column of char offset. */
    public int colOf(int offset) { return offset - lineStarts[lineOf(offset) - 1] + 1; }

    public int lineStart(int line) { return lineStarts[Math.max(0, Math.min(line, lineStarts.length) - 1)]; }

    /** Char offset for a 1-based line and column. */
    public int offset(int line, int col) {
        if (line < 1) return 0;
        if (line > lineStarts.length) return text.length();
        return Math.min(text.length(), lineStarts[line - 1] + Math.max(0, col - 1));
    }

    /** Converts a UTF-8 byte offset (tree-sitter) to a char offset. */
    public int charOffset(int byteOffset) {
        if (byteToChar == null) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == text.length()) {
                byteToChar = new int[0]; // ASCII: identity
            } else {
                byteToChar = new int[bytes.length + 1];
                int b = 0;
                for (int c = 0; c < text.length(); c++) {
                    int cp = text.codePointAt(c);
                    int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                    for (int k = 0; k < len && b + k < byteToChar.length; k++) byteToChar[b + k] = c;
                    b += len;
                    if (Character.isSupplementaryCodePoint(cp)) c++;
                }
                byteToChar[bytes.length] = text.length();
            }
        }
        if (byteToChar.length == 0) return Math.min(byteOffset, text.length());
        return byteToChar[Math.max(0, Math.min(byteOffset, byteToChar.length - 1))];
    }
}
