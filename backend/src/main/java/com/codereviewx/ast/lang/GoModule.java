package com.codereviewx.ast.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;

public final class GoModule extends TreeSitterModule {
    private static final Map<String, Kind> KINDS = Map.ofEntries(
        Map.entry("source_file", Kind.PROGRAM), Map.entry("function_declaration", Kind.FUNCTION),
        Map.entry("method_declaration", Kind.FUNCTION), Map.entry("func_literal", Kind.LAMBDA), Map.entry("block", Kind.BLOCK),
        Map.entry("for_statement", Kind.LOOP), Map.entry("if_statement", Kind.IF),
        Map.entry("expression_switch_statement", Kind.SWITCH), Map.entry("type_switch_statement", Kind.SWITCH),
        Map.entry("expression_case", Kind.CASE), Map.entry("default_case", Kind.CASE), Map.entry("type_case", Kind.CASE),
        Map.entry("short_var_declaration", Kind.ASSIGN), Map.entry("assignment_statement", Kind.ASSIGN),
        Map.entry("var_spec", Kind.DECL), Map.entry("const_spec", Kind.DECL),
        Map.entry("inc_statement", Kind.UNARY), Map.entry("dec_statement", Kind.UNARY),
        Map.entry("call_expression", Kind.CALL), Map.entry("selector_expression", Kind.MEMBER),
        Map.entry("index_expression", Kind.INDEX), Map.entry("binary_expression", Kind.BINARY),
        Map.entry("unary_expression", Kind.UNARY), Map.entry("parenthesized_expression", Kind.PAREN),
        Map.entry("identifier", Kind.IDENT), Map.entry("field_identifier", Kind.IDENT),
        Map.entry("int_literal", Kind.NUMBER), Map.entry("float_literal", Kind.NUMBER),
        Map.entry("interpreted_string_literal", Kind.STRING), Map.entry("raw_string_literal", Kind.STRING),
        Map.entry("nil", Kind.NULL), Map.entry("true", Kind.BOOL), Map.entry("false", Kind.BOOL),
        Map.entry("composite_literal", Kind.LIST), Map.entry("return_statement", Kind.RETURN),
        Map.entry("break_statement", Kind.BREAK), Map.entry("continue_statement", Kind.CONTINUE),
        Map.entry("import_declaration", Kind.IMPORT), Map.entry("comment", Kind.COMMENT),
        Map.entry("expression_statement", Kind.EXPR_STMT));

    @Override public String id() { return "go"; }
    @Override public String label() { return "Go"; }
    @Override public Set<String> fileExtensions() { return Set.of(".go"); }
    @Override protected TSLanguage newLanguage() { return new TreeSitterGo(); }
    @Override protected Set<String> flattened() { return Set.of("statement_list"); }

    @Override
    protected Kind kindOf(String type, TSNode n, String field) {
        if (type.equals("parameter_declaration") || type.equals("variadic_parameter_declaration")) {
            TSNode list = n.getParent(), owner = list == null ? null : list.getParent();
            if (owner != null && !owner.getChildByFieldName("parameters").isNull()
                && TSNode.eq(owner.getChildByFieldName("parameters"), list)) return Kind.PARAM;
            return null;
        }
        if (type.equals("composite_literal") && !n.getChildByFieldName("type").isNull()
            && n.getChildByFieldName("type").getType().equals("map_type")) return Kind.MAP;
        return KINDS.get(type);
    }

    @Override
    protected void decorate(UNode u, TSNode n) {
        switch (u.type()) {
            case "for_statement" -> {
                UNode clause = u.children().stream().filter(c -> c.type().equals("for_clause") || c.type().equals("range_clause")).findFirst().orElse(null);
                if (clause == null) {
                    UNode cond = u.children().stream().filter(c -> !c.is(Kind.BLOCK) && !c.is(Kind.COMMENT)).findFirst().orElse(null);
                    u.attr("loop", cond == null ? "infinite" : "while").field("cond", cond);
                } else if (clause.type().equals("for_clause")) {
                    u.attr("loop", clause.field("condition") == null ? "infinite" : "for")
                        .field("init", clause.field("initializer")).field("cond", clause.field("condition"))
                        .field("update", clause.field("update"));
                } else {
                    u.attr("loop", "foreach").field("var", clause.field("left")).field("iter", clause.field("right"));
                }
            }
            case "short_var_declaration", "assignment_statement" -> {
                if (u.type().startsWith("short")) u.op(":=").attr("declares", "true");
                List<UNode> ts = exprs(u.field("left")), vs = exprs(u.field("right"));
                u.list("targets", ts).list("values", vs);
                u.field("target", ts.isEmpty() ? null : ts.get(0)).field("value", vs.isEmpty() ? null : vs.get(0));
            }
            case "parameter_declaration", "variadic_parameter_declaration" -> {
                List<UNode> names = u.children().stream().filter(c -> c.is(Kind.IDENT)).toList();
                if (!names.isEmpty()) u.name(names.get(0).text());
                u.attr("names", String.valueOf(Math.max(1, names.size())));
            }
            case "var_spec", "const_spec" -> {
                if (u.type().equals("const_spec")) u.attr("const", "true");
                UNode v = u.field("value");
                if (v != null && v.type().equals("expression_list") && !v.children().isEmpty()) u.field("value", v.children().get(0));
            }
            case "inc_statement" -> u.op("++").field("operand", u.children().isEmpty() ? null : u.children().get(0));
            case "dec_statement" -> u.op("--").field("operand", u.children().isEmpty() ? null : u.children().get(0));
            case "default_case" -> u.attr("default", "true");
            case "function_declaration", "method_declaration" -> {
                UNode r = u.field("result");
                u.attr("returnType", r == null ? "" : r.text());
                if (u.type().equals("method_declaration")) u.attr("method", "true");
            }
            case "return_statement" -> {
                UNode list = u.children().stream().filter(c -> c.type().equals("expression_list")).findFirst().orElse(null);
                if (list != null) u.list("values", list.children());
            }
            default -> { }
        }
    }

    private static List<UNode> exprs(UNode list) {
        if (list == null) return List.of();
        if (!list.type().equals("expression_list")) return List.of(list);
        return new ArrayList<>(list.children().stream().filter(c -> !c.is(Kind.COMMENT)).toList());
    }
}
