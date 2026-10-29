package com.codereviewx.review;

import java.util.regex.Pattern;

/**
 * Port of guessLang() from js/review-engine.js. A heuristic hint for the "this looks like X" warning only; it
 * never selects the parser.
 */
public final class LanguageHints {
    private static final Pattern GO_PKG = Pattern.compile("^\\s*package\\s+\\w+", Pattern.MULTILINE);
    private static final Pattern GO_FUNC = Pattern.compile("\\bfunc\\s+\\w*\\s*\\(");
    private static final Pattern JAVA = Pattern.compile("\\bpublic\\s+(static\\s+)?(class|void|int|boolean|String)\\b|System\\.out\\.");
    private static final Pattern INCLUDE = Pattern.compile("#include\\s*<");
    private static final Pattern CPP = Pattern.compile("std::|cout|vector<|using namespace|bits/stdc");
    private static final Pattern PY = Pattern.compile("^\\s*def\\s+\\w+\\s*\\(.*\\)\\s*:|^\\s*import\\s+\\w+\\s*$|^\\s*from\\s+\\w+\\s+import", Pattern.MULTILINE);
    private static final Pattern JS = Pattern.compile("\\bfunction\\b|=>|\\bconst\\s+\\w+\\s*=|\\blet\\s+\\w+\\s*=|console\\.log");

    private LanguageHints() {}

    public static String guess(String code) {
        if (GO_PKG.matcher(code).find() && GO_FUNC.matcher(code).find()) return "go";
        if (JAVA.matcher(code).find()) return "java";
        if (INCLUDE.matcher(code).find()) return CPP.matcher(code).find() ? "cpp" : "c";
        if (PY.matcher(code).find()) return "python";
        if (JS.matcher(code).find()) return "javascript";
        return null;
    }
}
