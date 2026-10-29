package com.codereviewx.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * A test-case argument as written in data/problem-details.js: numbers, booleans, "strings", [arrays] (nested), and
 * linked lists written as 1->2->3 or (empty).
 */
public sealed interface TestValue {
    record Num(String text) implements TestValue {
        public boolean integral() { return !text.contains(".") && !text.contains("e") && !text.contains("E"); }
    }
    record Str(String value) implements TestValue {}
    record Bool(boolean value) implements TestValue {}
    record Arr(List<TestValue> items) implements TestValue {}
    record LinkedList(List<TestValue> items) implements TestValue {}

    /** Splits "[1,2], 16, \"a,b\"" into top-level arguments and parses each. */
    static List<TestValue> parseArgs(String input) {
        List<TestValue> out = new ArrayList<>();
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return out;
        int depth = 0, start = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[' || c == '(' || c == '{') depth++;
            else if (c == ']' || c == ')' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(parse(s.substring(start, i)));
                start = i + 1;
            }
        }
        out.add(parse(s.substring(start)));
        return out;
    }

    static TestValue parse(String raw) {
        String s = raw.trim();
        if (s.equals("(empty)")) return new LinkedList(List.of());
        if (s.startsWith("\"")) return new Str(unescape(s.substring(1, Math.max(1, s.length() - 1))));
        if (s.startsWith("[")) {
            String inner = s.substring(1, s.length() - 1).trim();
            return new Arr(inner.isEmpty() ? List.of() : parseArgs(inner));
        }
        if (s.contains("->")) {
            List<TestValue> items = new ArrayList<>();
            for (String part : s.split("->")) items.add(parse(part));
            return new LinkedList(items);
        }
        if (s.equals("true") || s.equals("false")) return new Bool(Boolean.parseBoolean(s));
        if (s.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) return new Num(s.startsWith("+") ? s.substring(1) : s);
        throw new IllegalArgumentException("Unsupported test value: " + s);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(switch (n) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; default -> n; });
            } else b.append(c);
        }
        return b.toString();
    }

    /** Double-quoted literal with C/Java/JS/Go/Python-compatible escapes. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                default -> {
                    if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
