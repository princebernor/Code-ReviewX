package com.codereviewx.ast.lang;

import java.util.List;
import java.util.Set;

import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.rules.Rule;
import com.codereviewx.ast.rules.RuleCatalog;

/** One supported language: its parser, its normalization into the unified AST, and its rules. */
public interface LanguageModule {
    /** Stable identifier, matching the frontend's {@code CRX.LANGS} ids. */
    String id();

    String label();

    Set<String> fileExtensions();

    /** Name of the parser behind this module, e.g. "javaparser" or "tree-sitter-python". */
    String parser();

    /** Parses LF-normalized source. Never executes it. Returns a partial tree plus diagnostics on syntax errors. */
    ParseResult parse(String source);

    /** Null when the grammar loaded; otherwise why it could not. */
    String unavailableReason();

    default List<Rule> rules() { return RuleCatalog.forLanguage(id()); }
}
