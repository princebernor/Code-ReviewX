package com.codereviewx.ast.lang;

import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJavascript;

public final class JavaScriptModule extends TreeSitterModule {
    private static final Map<String, Kind> KINDS = Map.ofEntries(
        Map.entry("program", Kind.PROGRAM), Map.entry("function_declaration", Kind.FUNCTION),
        Map.entry("generator_function_declaration", Kind.FUNCTION), Map.entry("method_definition", Kind.FUNCTION),
        Map.entry("function_expression", Kind.LAMBDA), Map.entry("arrow_function", Kind.LAMBDA),
        Map.entry("class_declaration", Kind.CLASS), Map.entry("class", Kind.CLASS), Map.entry("statement_block", Kind.BLOCK),
        Map.entry("variable_declarator", Kind.DECL), Map.entry("for_statement", Kind.LOOP), Map.entry("for_in_statement", Kind.LOOP),
        Map.entry("while_statement", Kind.LOOP), Map.entry("do_statement", Kind.LOOP), Map.entry("if_statement", Kind.IF),
        Map.entry("switch_statement", Kind.SWITCH), Map.entry("switch_case", Kind.CASE), Map.entry("switch_default", Kind.CASE),
        Map.entry("try_statement", Kind.TRY), Map.entry("catch_clause", Kind.CATCH), Map.entry("finally_clause", Kind.FINALLY),
        Map.entry("call_expression", Kind.CALL), Map.entry("new_expression", Kind.NEW), Map.entry("member_expression", Kind.MEMBER),
        Map.entry("subscript_expression", Kind.INDEX), Map.entry("assignment_expression", Kind.ASSIGN),
        Map.entry("augmented_assignment_expression", Kind.ASSIGN), Map.entry("binary_expression", Kind.BINARY),
        Map.entry("unary_expression", Kind.UNARY), Map.entry("update_expression", Kind.UNARY),
        Map.entry("ternary_expression", Kind.TERNARY), Map.entry("parenthesized_expression", Kind.PAREN),
        Map.entry("identifier", Kind.IDENT), Map.entry("property_identifier", Kind.IDENT),
        Map.entry("shorthand_property_identifier", Kind.IDENT), Map.entry("number", Kind.NUMBER), Map.entry("string", Kind.STRING),
        Map.entry("template_string", Kind.STRING), Map.entry("null", Kind.NULL), Map.entry("undefined", Kind.NULL),
        Map.entry("true", Kind.BOOL), Map.entry("false", Kind.BOOL), Map.entry("array", Kind.LIST), Map.entry("object", Kind.MAP),
        Map.entry("return_statement", Kind.RETURN), Map.entry("throw_statement", Kind.THROW),
        Map.entry("break_statement", Kind.BREAK), Map.entry("continue_statement", Kind.CONTINUE),
        Map.entry("import_statement", Kind.IMPORT), Map.entry("comment", Kind.COMMENT),
        Map.entry("expression_statement", Kind.EXPR_STMT));
    private static final Set<String> PARAM_TYPES = Set.of("identifier", "assignment_pattern", "rest_pattern",
        "object_pattern", "array_pattern");

    @Override public String id() { return "javascript"; }
    @Override public String label() { return "JavaScript"; }
    @Override public Set<String> fileExtensions() { return Set.of(".js", ".mjs", ".cjs"); }
    @Override protected TSLanguage newLanguage() { return new TreeSitterJavascript(); }
    @Override protected boolean syntacticConditionParens() { return true; }

    @Override
    protected Kind kindOf(String type, TSNode n, String field) {
        if (PARAM_TYPES.contains(type)) {
            TSNode p = n.getParent();
            if (p != null && (p.getType().equals("formal_parameters") || p.getType().equals("arrow_function") && "parameter".equals(field))) {
                return Kind.PARAM;
            }
        }
        return KINDS.get(type);
    }

    @Override
    protected void decorate(UNode u, TSNode n) {
        switch (u.type()) {
            case "variable_declarator" -> {
                TSNode p = n.getParent();
                if (p != null && p.getChildCount() > 0) u.attr("declKind", p.getChild(0).getType());
                UNode v = u.field("value");
                if (v != null && v.is(Kind.LAMBDA) && v.name() == null) v.name(u.name());
            }
            case "for_statement" -> u.attr("loop", u.field("cond") == null || u.field("cond").type().equals("empty_statement") ? "infinite" : "for");
            case "for_in_statement" -> u.attr("loop", "foreach").field("var", u.field("left")).field("iter", u.field("right"));
            case "while_statement" -> {
                UNode c = u.field("cond");
                u.attr("loop", c != null && c.is(Kind.BOOL) && c.text().equals("true") ? "infinite" : "while");
            }
            case "do_statement" -> u.attr("loop", "do");
            case "switch_default" -> u.attr("default", "true");
            case "catch_clause" -> {
                UNode p = u.field("parameter");
                if (p != null) u.attr("param", p.text());
                u.attr("type", "*");
            }
            case "assignment_pattern" -> {
                UNode l = u.field("left");
                if (l != null) u.name(l.text());
                u.field("default", u.field("right"));
            }
            case "rest_pattern" -> u.name(u.text().replace("...", ""));
            default -> { }
        }
        if (u.is(Kind.PARAM) && u.name() == null) u.name(u.text());
    }
}
