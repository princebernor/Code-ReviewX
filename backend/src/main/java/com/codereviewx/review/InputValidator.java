package com.codereviewx.review;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import com.codereviewx.config.ApiException;
import com.codereviewx.config.CrxProperties;
import org.springframework.http.HttpStatus;

/**
 * The browser engine's input checks (same codes and messages: empty, short, long, binary) plus server-side
 * resource limits (too_large, too_deep).
 */
public final class InputValidator {
    private static final Pattern BINARY = Pattern.compile("[\\u0000-\\u0008\\u000E-\\u001F]");

    private final CrxProperties.Review cfg;

    public InputValidator(CrxProperties.Review cfg) { this.cfg = cfg; }

    /** Returns LF-normalized source or throws an ApiException with a frontend-compatible code. */
    public String validate(String code) {
        if (code == null) code = "";
        if (code.getBytes(StandardCharsets.UTF_8).length > cfg.maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "The server reviews up to 512 KB of code at a time.");
        }
        String src = code.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = src.split("\n", -1);
        long nonblank = java.util.Arrays.stream(lines).filter(l -> !l.strip().isEmpty()).count();
        if (src.strip().isEmpty()) throw ApiException.badRequest("empty", "Paste or upload some code first.");
        if (nonblank < 3) throw ApiException.badRequest("short", "Add at least 3 lines of code so there's something to review.");
        if (lines.length > cfg.maxLines()) {
            throw ApiException.badRequest("long", "This demo reviews up to 2,000 lines at a time. Split the file and review it in parts.");
        }
        if (BINARY.matcher(src).find()) throw ApiException.badRequest("binary", "This looks like a binary file, not source code.");
        int depth = 0, max = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(' || c == '[' || c == '{') max = Math.max(max, ++depth);
            else if ((c == ')' || c == ']' || c == '}') && depth > 0) depth--;
        }
        if (max > cfg.maxNesting()) {
            throw ApiException.badRequest("too_deep", "The code is nested more than " + cfg.maxNesting() + " brackets deep, which the server won't analyse.");
        }
        return src;
    }
}
