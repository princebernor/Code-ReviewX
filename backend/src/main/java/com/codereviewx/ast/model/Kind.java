package com.codereviewx.ast.model;

/** Language-neutral node kinds. Native node types are kept on {@link UNode#type()} for diagnostics. */
public enum Kind {
    PROGRAM, CLASS, FUNCTION, LAMBDA, PARAM, BLOCK,
    DECL, ASSIGN,
    LOOP, IF, SWITCH, CASE, BREAK, CONTINUE, RETURN, THROW,
    TRY, CATCH, FINALLY, WITH,
    CALL, NEW, MEMBER, INDEX,
    BINARY, UNARY, TERNARY, PAREN,
    IDENT, STRING, NUMBER, NULL, BOOL,
    LIST, MAP, SET,
    IMPORT, COMMENT, EXPR_STMT,
    ERROR, OTHER;

    /** Kinds that appear in AST paths (e.g. {@code Function[twoSum] > For[0] > If[0]}). */
    public boolean structural() {
        return switch (this) {
            case CLASS, FUNCTION, LAMBDA, LOOP, IF, SWITCH, CASE, TRY, CATCH, WITH -> true;
            default -> false;
        };
    }
}
