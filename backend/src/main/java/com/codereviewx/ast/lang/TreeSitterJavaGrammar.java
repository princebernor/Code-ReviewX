package com.codereviewx.ast.lang;

import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

/**
 * tree-sitter-java normalization. Not registered as a language of its own: {@link JavaModule} uses it to recover a
 * partial tree when JavaParser cannot produce one for malformed input.
 */
final class TreeSitterJavaGrammar extends TreeSitterModule {
    private static final Map<String, Kind> KINDS = Map.ofEntries(
        Map.entry("program", Kind.PROGRAM), Map.entry("class_declaration", Kind.CLASS),
        Map.entry("interface_declaration", Kind.CLASS), Map.entry("enum_declaration", Kind.CLASS),
        Map.entry("record_declaration", Kind.CLASS), Map.entry("method_declaration", Kind.FUNCTION),
        Map.entry("constructor_declaration", Kind.FUNCTION), Map.entry("lambda_expression", Kind.LAMBDA),
        Map.entry("formal_parameter", Kind.PARAM), Map.entry("spread_parameter", Kind.PARAM),
        Map.entry("block", Kind.BLOCK), Map.entry("constructor_body", Kind.BLOCK), Map.entry("variable_declarator", Kind.DECL),
        Map.entry("for_statement", Kind.LOOP), Map.entry("enhanced_for_statement", Kind.LOOP),
        Map.entry("while_statement", Kind.LOOP), Map.entry("do_statement", Kind.LOOP), Map.entry("if_statement", Kind.IF),
        Map.entry("switch_expression", Kind.SWITCH), Map.entry("switch_statement", Kind.SWITCH),
        Map.entry("switch_block_statement_group", Kind.CASE), Map.entry("switch_rule", Kind.CASE),
        Map.entry("break_statement", Kind.BREAK), Map.entry("continue_statement", Kind.CONTINUE),
        Map.entry("return_statement", Kind.RETURN), Map.entry("throw_statement", Kind.THROW),
        Map.entry("try_statement", Kind.TRY), Map.entry("try_with_resources_statement", Kind.TRY),
        Map.entry("catch_clause", Kind.CATCH), Map.entry("finally_clause", Kind.FINALLY),
        Map.entry("method_invocation", Kind.CALL), Map.entry("object_creation_expression", Kind.NEW),
        Map.entry("array_creation_expression", Kind.NEW), Map.entry("field_access", Kind.MEMBER),
        Map.entry("array_access", Kind.INDEX), Map.entry("assignment_expression", Kind.ASSIGN),
        Map.entry("binary_expression", Kind.BINARY), Map.entry("unary_expression", Kind.UNARY),
        Map.entry("update_expression", Kind.UNARY), Map.entry("ternary_expression", Kind.TERNARY),
        Map.entry("parenthesized_expression", Kind.PAREN), Map.entry("identifier", Kind.IDENT),
        Map.entry("decimal_integer_literal", Kind.NUMBER), Map.entry("hex_integer_literal", Kind.NUMBER),
        Map.entry("octal_integer_literal", Kind.NUMBER), Map.entry("binary_integer_literal", Kind.NUMBER),
        Map.entry("decimal_floating_point_literal", Kind.NUMBER), Map.entry("string_literal", Kind.STRING),
        Map.entry("character_literal", Kind.STRING), Map.entry("null_literal", Kind.NULL),
        Map.entry("true", Kind.BOOL), Map.entry("false", Kind.BOOL), Map.entry("array_initializer", Kind.LIST),
        Map.entry("line_comment", Kind.COMMENT), Map.entry("block_comment", Kind.COMMENT),
        Map.entry("expression_statement", Kind.EXPR_STMT), Map.entry("import_declaration", Kind.IMPORT));

    @Override public String id() { return "java"; }
    @Override public String label() { return "Java"; }
    @Override public String parser() { return "tree-sitter-java"; }
    @Override public Set<String> fileExtensions() { return Set.of(".java"); }
    @Override protected TSLanguage newLanguage() { return new TreeSitterJava(); }
    @Override protected boolean syntacticConditionParens() { return true; }

    @Override
    protected Kind kindOf(String type, TSNode n, String field) { return KINDS.get(type); }

    @Override
    protected void decorate(UNode u, TSNode n) {
        switch (u.type()) {
            case "method_invocation" -> {
                UNode obj = u.field("object"), nm = u.field("name");
                String method = nm == null ? null : nm.text();
                u.name(Decorations.compact((obj == null ? "" : obj.text() + ".") + (method == null ? "" : method)));
                u.attr("method", method).field("receiver", obj);
            }
            case "object_creation_expression", "array_creation_expression" -> {
                UNode t = u.field("type");
                if (t != null) u.name(Decorations.compact(t.text())).attr("method", Decorations.compact(t.text()));
                if (u.type().startsWith("array")) u.attr("array", "true").attr("dims", String.valueOf(u.text().chars().filter(c -> c == '[').count()));
            }
            case "variable_declarator" -> {
                TSNode p = n.getParent();
                if (p != null && !p.getChildByFieldName("type").isNull()) {
                    TSNode t = p.getChildByFieldName("type");
                    u.attr("type", u.source().slice(u.source().charOffset(t.getStartByte()), u.source().charOffset(t.getEndByte())));
                }
                if (p != null && p.getType().equals("field_declaration")) u.attr("field", "true");
            }
            case "for_statement" -> u.attr("loop", u.field("cond") == null ? "infinite" : "for");
            case "enhanced_for_statement" -> u.attr("loop", "foreach").field("var", u.field("name")).field("iter", u.field("value"));
            case "while_statement" -> u.attr("loop", u.field("cond") != null && u.field("cond").text().equals("true") ? "infinite" : "while");
            case "do_statement" -> u.attr("loop", "do");
            case "try_with_resources_statement" -> u.attr("resources", "true");
            case "catch_clause" -> {
                UNode p = u.children().stream().filter(c -> c.type().equals("catch_formal_parameter")).findFirst().orElse(null);
                if (p != null) {
                    UNode t = p.children().stream().filter(c -> c.type().equals("catch_type")).findFirst().orElse(null);
                    u.attr("type", t == null ? p.text() : t.text());
                }
            }
            case "switch_block_statement_group" -> u.attr("default", String.valueOf(u.text().trim().startsWith("default")));
            case "switch_rule" -> u.attr("arrow", "true");
            case "method_declaration", "constructor_declaration" -> {
                u.attr("modifiers", u.children().stream().filter(c -> c.type().equals("modifiers")).map(UNode::text).findFirst().orElse(""));
                TSNode prev = n.getPrevNamedSibling();
                if (prev != null && !prev.isNull() && prev.getType().equals("block_comment")
                    && u.source().slice(u.source().charOffset(prev.getStartByte()), u.source().charOffset(prev.getStartByte()) + 3).equals("/**")) {
                    u.attr("doc", "true");
                }
            }
            case "class_declaration", "interface_declaration", "enum_declaration", "record_declaration" ->
                u.attr("modifiers", u.children().stream().filter(c -> c.type().equals("modifiers")).map(UNode::text).findFirst().orElse(""));
            default -> { }
        }
    }
}
