package com.codereviewx.ast.lang;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCpp;

/** C and C++ share most of their tree-sitter node vocabulary. */
public abstract class CFamilyModule extends TreeSitterModule {
    static final Map<String, Kind> KINDS = new HashMap<>(Map.ofEntries(
        Map.entry("translation_unit", Kind.PROGRAM), Map.entry("function_definition", Kind.FUNCTION),
        Map.entry("parameter_declaration", Kind.PARAM), Map.entry("optional_parameter_declaration", Kind.PARAM),
        Map.entry("compound_statement", Kind.BLOCK), Map.entry("init_declarator", Kind.DECL),
        Map.entry("for_statement", Kind.LOOP), Map.entry("for_range_loop", Kind.LOOP), Map.entry("while_statement", Kind.LOOP),
        Map.entry("do_statement", Kind.LOOP), Map.entry("if_statement", Kind.IF), Map.entry("switch_statement", Kind.SWITCH),
        Map.entry("case_statement", Kind.CASE), Map.entry("break_statement", Kind.BREAK), Map.entry("continue_statement", Kind.CONTINUE),
        Map.entry("return_statement", Kind.RETURN), Map.entry("throw_statement", Kind.THROW), Map.entry("try_statement", Kind.TRY),
        Map.entry("catch_clause", Kind.CATCH), Map.entry("call_expression", Kind.CALL), Map.entry("new_expression", Kind.NEW),
        Map.entry("field_expression", Kind.MEMBER), Map.entry("subscript_expression", Kind.INDEX),
        Map.entry("assignment_expression", Kind.ASSIGN), Map.entry("binary_expression", Kind.BINARY),
        Map.entry("unary_expression", Kind.UNARY), Map.entry("pointer_expression", Kind.UNARY), Map.entry("update_expression", Kind.UNARY),
        Map.entry("conditional_expression", Kind.TERNARY), Map.entry("parenthesized_expression", Kind.PAREN),
        Map.entry("identifier", Kind.IDENT), Map.entry("field_identifier", Kind.IDENT),
        Map.entry("number_literal", Kind.NUMBER), Map.entry("string_literal", Kind.STRING), Map.entry("char_literal", Kind.STRING),
        Map.entry("raw_string_literal", Kind.STRING), Map.entry("concatenated_string", Kind.STRING),
        Map.entry("null", Kind.NULL), Map.entry("nullptr", Kind.NULL), Map.entry("true", Kind.BOOL), Map.entry("false", Kind.BOOL),
        Map.entry("initializer_list", Kind.LIST), Map.entry("lambda_expression", Kind.LAMBDA),
        Map.entry("preproc_include", Kind.IMPORT), Map.entry("using_declaration", Kind.IMPORT),
        Map.entry("comment", Kind.COMMENT), Map.entry("expression_statement", Kind.EXPR_STMT),
        Map.entry("struct_specifier", Kind.CLASS), Map.entry("class_specifier", Kind.CLASS)));
    private static final Set<String> DECLARATOR_PARENTS = Set.of("declaration", "field_declaration");

    @Override
    protected Kind kindOf(String type, TSNode n, String field) {
        if ("declarator".equals(field) && !type.equals("function_declarator")) {
            TSNode p = n.getParent();
            if (p != null && DECLARATOR_PARENTS.contains(p.getType())) return Kind.DECL;
        }
        if ((type.equals("struct_specifier") || type.equals("class_specifier")) && n.getChildByFieldName("body").isNull()) return null;
        return KINDS.get(type);
    }

    @Override
    protected Set<String> flattened() { return Set.of("condition_clause"); }

    @Override
    protected void decorate(UNode u, TSNode n) {
        switch (u.type()) {
            case "for_statement" -> u.attr("loop", u.field("cond") == null ? "infinite" : "for");
            case "for_range_loop" -> u.attr("loop", "foreach").field("var", u.field("declarator")).field("iter", u.field("right"));
            case "while_statement" -> u.attr("loop", isTrue(u.field("cond")) ? "infinite" : "while");
            case "do_statement" -> {
                u.attr("loop", "do");
                if (!syntacticConditionParens()) Decorations.stripParens(u, "cond"); // C++: do-while keeps its parens
            }
            case "case_statement" -> u.attr("default", String.valueOf("default".equals(u.attr("keyword"))));
            case "catch_clause" -> {
                UNode p = u.field("parameters");
                if (p != null) u.attr("type", p.text().replaceAll("^\\(|\\)$", "").trim());
            }
            case "char_literal" -> u.attr("char", "true");
            case "using_declaration" -> u.attr("usingNamespace", String.valueOf(u.text().matches("(?s)using\\s+namespace\\s+.*")));
            case "preproc_include" -> {
                UNode p = u.field("path");
                if (p != null) u.attr("path", p.text());
            }
            case "function_definition" -> {
                UNode t = u.field("type"), d = u.field("declarator");
                if (t != null) u.attr("returnType", t.text() + (d != null && d.type().equals("pointer_declarator") ? "*" : ""));
            }
            case "struct_specifier", "class_specifier" -> {
                UNode nm = u.field("name");
                if (nm != null) u.name(nm.text());
            }
            default -> { }
        }
        if (u.is(Kind.PARAM) || u.is(Kind.DECL)) declarator(u, n);
        if (u.is(Kind.UNARY) && u.op() == null && u.attr("keyword") != null) u.op(u.attr("keyword"));
    }

    private static boolean isTrue(UNode c) {
        return c != null && (c.is(Kind.BOOL) && c.text().equals("true") || c.is(Kind.NUMBER) && c.text().equals("1"));
    }

    /** Records pointer/reference/array/const facts from the declarator chain and the declared type. */
    private static void declarator(UNode u, TSNode n) {
        UNode d = u.is(Kind.DECL) && u.field("declarator") == null ? u : u.field("declarator");
        for (UNode x = d; x != null; x = x.field("declarator")) {
            switch (x.type()) {
                case "pointer_declarator", "abstract_pointer_declarator" -> u.attr("pointer", "true");
                case "reference_declarator", "abstract_reference_declarator" -> u.attr("reference", "true");
                case "array_declarator" -> {
                    u.attr("array", "true");
                    UNode size = x.field("size");
                    u.attr("arraySize", size == null ? "" : size.text());
                    if (size != null && !size.is(Kind.NUMBER)) u.attr("vla", "true");
                }
                default -> { }
            }
        }
        if (u.is(Kind.DECL) && u.name() == null) u.name(Decorations.nameOf(u));
        if (u.is(Kind.DECL) && u.attr("type") == null) {
            TSNode p = n.getParent();
            if (p != null && !p.getChildByFieldName("type").isNull()) {
                TSNode t = p.getChildByFieldName("type");
                u.attr("type", u.source().slice(u.source().charOffset(t.getStartByte()), u.source().charOffset(t.getEndByte())));
            }
        }
        if (u.is(Kind.PARAM)) {
            String text = u.text();
            u.attr("const", String.valueOf(text.matches("(?s)^\\s*const\\b.*") || text.matches("(?s).*\\bconst\\s*[&*].*")));
        }
    }

    public static final class C extends CFamilyModule {
        @Override public String id() { return "c"; }
        @Override public String label() { return "C"; }
        @Override public Set<String> fileExtensions() { return Set.of(".c", ".h"); }
        @Override protected TSLanguage newLanguage() { return new TreeSitterC(); }
        @Override protected boolean syntacticConditionParens() { return true; }
    }

    public static final class Cpp extends CFamilyModule {
        @Override public String id() { return "cpp"; }
        @Override public String label() { return "C++"; }
        @Override public Set<String> fileExtensions() { return Set.of(".cpp", ".cc", ".cxx", ".hpp"); }
        @Override protected TSLanguage newLanguage() { return new TreeSitterCpp(); }
    }
}
