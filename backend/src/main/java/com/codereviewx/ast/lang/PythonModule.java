package com.codereviewx.ast.lang;

import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

public final class PythonModule extends TreeSitterModule {
    private static final Map<String, Kind> KINDS = Map.ofEntries(
        Map.entry("module", Kind.PROGRAM), Map.entry("function_definition", Kind.FUNCTION),
        Map.entry("class_definition", Kind.CLASS), Map.entry("lambda", Kind.LAMBDA), Map.entry("block", Kind.BLOCK),
        Map.entry("for_statement", Kind.LOOP), Map.entry("while_statement", Kind.LOOP),
        Map.entry("if_statement", Kind.IF), Map.entry("elif_clause", Kind.IF),
        Map.entry("try_statement", Kind.TRY), Map.entry("except_clause", Kind.CATCH), Map.entry("finally_clause", Kind.FINALLY),
        Map.entry("with_statement", Kind.WITH), Map.entry("call", Kind.CALL), Map.entry("attribute", Kind.MEMBER),
        Map.entry("subscript", Kind.INDEX), Map.entry("assignment", Kind.ASSIGN), Map.entry("augmented_assignment", Kind.ASSIGN),
        Map.entry("binary_operator", Kind.BINARY), Map.entry("comparison_operator", Kind.BINARY),
        Map.entry("boolean_operator", Kind.BINARY), Map.entry("not_operator", Kind.UNARY), Map.entry("unary_operator", Kind.UNARY),
        Map.entry("conditional_expression", Kind.TERNARY), Map.entry("parenthesized_expression", Kind.PAREN),
        Map.entry("identifier", Kind.IDENT), Map.entry("string", Kind.STRING), Map.entry("concatenated_string", Kind.STRING),
        Map.entry("integer", Kind.NUMBER), Map.entry("float", Kind.NUMBER), Map.entry("none", Kind.NULL),
        Map.entry("true", Kind.BOOL), Map.entry("false", Kind.BOOL),
        Map.entry("list", Kind.LIST), Map.entry("list_comprehension", Kind.LIST), Map.entry("dictionary", Kind.MAP),
        Map.entry("dictionary_comprehension", Kind.MAP), Map.entry("set", Kind.SET), Map.entry("set_comprehension", Kind.SET),
        Map.entry("return_statement", Kind.RETURN), Map.entry("raise_statement", Kind.THROW),
        Map.entry("break_statement", Kind.BREAK), Map.entry("continue_statement", Kind.CONTINUE),
        Map.entry("import_statement", Kind.IMPORT), Map.entry("import_from_statement", Kind.IMPORT),
        Map.entry("comment", Kind.COMMENT), Map.entry("expression_statement", Kind.EXPR_STMT));
    private static final Set<String> PARAM_TYPES = Set.of("identifier", "default_parameter", "typed_parameter",
        "typed_default_parameter", "list_splat_pattern", "dictionary_splat_pattern");

    @Override public String id() { return "python"; }
    @Override public String label() { return "Python"; }
    @Override public Set<String> fileExtensions() { return Set.of(".py"); }
    @Override protected TSLanguage newLanguage() { return new TreeSitterPython(); }

    @Override
    protected Kind kindOf(String type, TSNode n, String field) {
        if (PARAM_TYPES.contains(type)) {
            String p = n.getParent().getType();
            if (p.equals("parameters") || p.equals("lambda_parameters")) return Kind.PARAM;
        }
        return KINDS.get(type);
    }

    @Override
    protected void decorate(UNode u, TSNode n) {
        switch (u.type()) {
            case "for_statement" -> u.attr("loop", "foreach").field("var", u.field("left")).field("iter", u.field("right"));
            case "while_statement" -> {
                UNode c = u.field("cond");
                u.attr("loop", c != null && c.is(Kind.BOOL) && c.text().equals("True") ? "infinite" : "while");
            }
            case "except_clause" -> {
                UNode type = u.children().stream().filter(c -> !c.is(Kind.BLOCK) && !c.is(Kind.COMMENT)).findFirst().orElse(null);
                if (type != null) u.attr("type", type.text().replaceAll("\\s+as\\s+\\w+$", ""));
                u.field("body", u.children().stream().filter(c -> c.is(Kind.BLOCK)).findFirst().orElse(null));
            }
            case "function_definition" -> {
                TSNode p = n.getParent();
                if (p != null && p.getType().equals("decorated_definition")) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < p.getNamedChildCount(); i++) {
                        TSNode c = p.getNamedChild(i);
                        if (c.getType().equals("decorator")) sb.append(u.source().slice(
                            u.source().charOffset(c.getStartByte()), u.source().charOffset(c.getEndByte()))).append('\n');
                    }
                    u.attr("decorators", sb.toString());
                }
            }
            case "typed_parameter" -> {
                UNode id = u.children().stream().filter(c -> c.is(Kind.IDENT)).findFirst().orElse(null);
                if (id != null) u.name(id.text());
            }
            case "list_splat_pattern", "dictionary_splat_pattern" -> u.name(u.text().replace("*", ""));
            case "list_comprehension", "dictionary_comprehension", "set_comprehension" -> u.attr("comprehension", "true");
            case "comparison_operator" -> {
                // Operator tokens of the first comparison, e.g. "is not" may arrive as one token or as two.
                StringBuilder ops = new StringBuilder();
                for (int i = 0; i < n.getChildCount(); i++) {
                    TSNode c = n.getChild(i);
                    if (c.isNamed()) { if (!ops.isEmpty()) break; continue; }
                    if (!ops.isEmpty()) ops.append(' ');
                    ops.append(c.getType());
                }
                if (!ops.isEmpty()) u.op(ops.toString());
            }
            default -> { }
        }
    }
}
