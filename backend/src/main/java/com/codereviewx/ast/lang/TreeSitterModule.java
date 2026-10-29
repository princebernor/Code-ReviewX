package com.codereviewx.ast.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.AstPaths;
import com.codereviewx.ast.model.Diagnostic;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.model.SourceText;
import com.codereviewx.ast.model.Span;
import com.codereviewx.ast.model.UNode;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

/**
 * Parses with a tree-sitter grammar (via the tree-sitter-ng JNI bindings) and normalizes the concrete syntax tree
 * into {@link UNode}s. Subclasses supply the node-type → {@link Kind} table and language-specific decoration.
 * Tree-sitter recovers from syntax errors, so a partial tree is returned together with ERROR/MISSING diagnostics.
 */
public abstract class TreeSitterModule implements LanguageModule {
    /** Converting deeper than this is refused; the subtree is reported as truncated. */
    static final int MAX_DEPTH = 1500;
    static final int MAX_DIAGNOSTICS = 25;

    private final TSLanguage language;
    private final String unavailable;
    private final ThreadLocal<TSParser> parsers;

    protected TreeSitterModule() {
        TSLanguage lang = null;
        String err = null;
        try {
            lang = newLanguage();
            TSParser probe = new TSParser();
            if (!probe.setLanguage(lang)) err = "grammar ABI is incompatible with the tree-sitter runtime";
            probe.close();
        } catch (Throwable t) { // UnsatisfiedLinkError on an unsupported platform
            err = "could not load the native grammar: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        this.language = err == null ? lang : null;
        this.unavailable = err;
        this.parsers = ThreadLocal.withInitial(() -> {
            TSParser p = new TSParser();
            p.setLanguage(language);
            return p;
        });
    }

    protected abstract TSLanguage newLanguage();

    /** Maps a native node type to a kind; return null for {@link Kind#OTHER}. */
    protected abstract Kind kindOf(String type, TSNode n, String fieldInParent);

    /** Wrapper node types whose children are lifted into the parent (e.g. Go's statement_list). */
    protected Set<String> flattened() { return Set.of(); }

    /** Language-specific roles and attributes, run after generic decoration. */
    protected void decorate(UNode u, TSNode n) {}

    /** True when if/while conditions are written inside mandatory parentheses that the grammar keeps as a node. */
    protected boolean syntacticConditionParens() { return false; }

    @Override
    public String parser() { return "tree-sitter-" + id(); }

    @Override
    public String unavailableReason() { return unavailable; }

    @Override
    public ParseResult parse(String code) {
        if (language == null) throw new IllegalStateException(id() + " parser unavailable: " + unavailable);
        SourceText src = new SourceText(code);
        TSTree tree = parsers.get().parseString(null, code);
        try {
            TSNode rootNode = tree.getRootNode();
            Builder b = new Builder(src);
            UNode root = b.convert(rootNode, null, 0, false);
            AstPaths.assign(root);
            return new ParseResult(id(), parser(), src, root, b.diagnostics, rootNode.hasError());
        } finally {
            tree.close();
        }
    }

    private final class Builder {
        final SourceText src;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        boolean truncated;

        Builder(SourceText src) { this.src = src; }

        Span span(TSNode n) {
            return Span.of(src, src.charOffset(n.getStartByte()), src.charOffset(n.getEndByte()));
        }

        void diag(String msg, Span s) {
            if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(Diagnostic.error(msg, s));
        }

        UNode convert(TSNode n, String fieldInParent, int depth, boolean insideError) {
            String type = n.getType();
            boolean error = n.isError();
            if (error && !insideError) diag("Syntax error", span(n));
            Kind k = error ? Kind.ERROR : kindOf(type, n, fieldInParent);
            UNode u = new UNode(k == null ? Kind.OTHER : k, type, span(n), src);
            if (depth > MAX_DEPTH) {
                if (!truncated) diagnostics.add(Diagnostic.error("Nesting is too deep; the rest of this subtree was not analysed", u.span()));
                truncated = true;
                return u;
            }
            String firstToken = null;
            int count = n.getChildCount();
            for (int i = 0; i < count; i++) {
                TSNode c = n.getChild(i);
                String f = n.getFieldNameForChild(i);
                if (c.isMissing()) {
                    diag("Missing '" + c.getType() + "'", span(c));
                    continue;
                }
                if (!c.isNamed()) {
                    if (firstToken == null) firstToken = c.getType();
                    if (("operator".equals(f) || "operators".equals(f)) && u.op() == null) u.op(c.getType());
                    continue;
                }
                UNode cu = convert(c, f, depth + 1, insideError || error);
                if (flattened().contains(c.getType())) {
                    List<UNode> lifted = new ArrayList<>(cu.children());
                    for (UNode l : lifted) u.add(l);
                    if (f != null && !lifted.isEmpty()) u.field(f, lifted.get(0));
                    continue;
                }
                u.add(cu);
                if (f != null) {
                    if (u.field(f) == null) u.field(f, cu);
                    u.addTo("field:" + f, cu);
                }
            }
            u.attr("keyword", firstToken);
            Decorations.generic(u, syntacticConditionParens());
            decorate(u, n);
            return u;
        }
    }

    /** Convenience for subclasses' type tables. */
    protected static Kind lookup(Map<String, Kind> table, String type) { return table.get(type); }
}
