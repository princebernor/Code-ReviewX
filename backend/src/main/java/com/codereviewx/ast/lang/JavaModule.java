package com.codereviewx.ast.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.codereviewx.ast.model.AstPaths;
import com.codereviewx.ast.model.Diagnostic;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.model.SourceText;
import com.codereviewx.ast.model.Span;
import com.codereviewx.ast.model.UNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.UnparsableStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.type.Type;

/**
 * Java via JavaParser. Snippets that are not a full compilation unit (a lone method, or bare statements, which is
 * how most practice code is pasted) are parsed inside a synthetic wrapper that is removed again during
 * normalization. When JavaParser cannot produce a tree at all, tree-sitter-java supplies a partial one.
 */
public final class JavaModule implements LanguageModule {
    private static final String WRAP_CLASS = "__CrxSnippet__";
    private static final String WRAP_METHOD = "__crxSnippet__";
    private final TreeSitterJavaGrammar fallback = new TreeSitterJavaGrammar();

    @Override public String id() { return "java"; }
    @Override public String label() { return "Java"; }
    @Override public Set<String> fileExtensions() { return Set.of(".java"); }
    @Override public String parser() { return "javaparser"; }
    @Override public String unavailableReason() { return null; }

    private static JavaParser newParser() {
        return new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setTabSize(1)); // token storage must stay on: node ranges are derived from tokens
    }

    @Override
    public ParseResult parse(String code) {
        SourceText src = new SourceText(code);
        JavaParser jp = newParser();
        com.github.javaparser.ParseResult<CompilationUnit> first = jp.parse(code);
        if (first.isSuccessful() && first.getResult().isPresent()) return build(src, first.getResult().get(), 0, List.of());
        for (String header : new String[] {"class " + WRAP_CLASS + " {", "class " + WRAP_CLASS + " { void " + WRAP_METHOD + "() {"}) {
            String footer = header.endsWith("() {") ? "\n}}" : "\n}";
            var r = newParser().parse(header + "\n" + code + footer);
            if (r.isSuccessful() && r.getResult().isPresent()) {
                return build(src, r.getResult().get(), 1, List.of(Diagnostic.info("Parsed as a snippet (no enclosing class).")));
            }
        }
        // No complete JavaParser tree: recover a partial one with tree-sitter, keeping JavaParser's first message.
        ParseResult ts = fallback.parse(code);
        List<Diagnostic> diags = new ArrayList<>();
        if (!first.getProblems().isEmpty()) diags.add(problem(first.getProblems().get(0), src, 0));
        diags.addAll(ts.diagnostics());
        diags.add(Diagnostic.info("JavaParser could not build a complete tree; tree-sitter-java recovered a partial tree."));
        return new ParseResult(id(), "javaparser+tree-sitter-java", src, ts.root(), diags, true);
    }

    private ParseResult build(SourceText src, CompilationUnit cu, int shift, List<Diagnostic> notes) {
        Conv c = new Conv(src, shift);
        UNode root = new UNode(Kind.PROGRAM, "CompilationUnit", Span.of(src, 0, src.text().length()), src);
        for (Node n : cu.getChildNodes()) c.into(root, n);
        for (Comment cm : cu.getAllComments()) {
            UNode u = c.node(Kind.COMMENT, cm);
            if (u != null) root.add(u.attr("text", cm.getContent()));
        }
        AstPaths.assign(root);
        List<Diagnostic> diags = new ArrayList<>(notes);
        diags.addAll(c.diagnostics);
        return new ParseResult(id(), parser(), src, root, diags, !c.diagnostics.isEmpty());
    }

    private static Diagnostic problem(Problem p, SourceText src, int shift) {
        String first = p.getMessage().split("\n")[0];
        String msg = first.length() > 200 ? first.substring(0, 200) + "…" : first;
        return p.getLocation().flatMap(l -> l.toRange()).map(r -> {
            int line = r.begin.line - shift;
            return new Diagnostic("error", msg, Math.max(1, line), r.begin.column, Math.max(1, r.end.line - shift), r.end.column + 1);
        }).orElse(Diagnostic.error(msg, null));
    }

    /** JavaParser → UNode conversion. */
    private static final class Conv {
        final SourceText src;
        final int shift;
        final List<Diagnostic> diagnostics = new ArrayList<>();

        Conv(SourceText src, int shift) {
            this.src = src;
            this.shift = shift;
        }

        Span span(Node n) {
            Range r = n.getRange().orElse(null);
            if (r == null) return null;
            int s = src.offset(r.begin.line - shift, r.begin.column);
            int e = src.offset(r.end.line - shift, r.end.column) + 1;
            if (r.begin.line - shift < 1) s = 0;
            if (r.end.line - shift > src.lineCount()) e = src.text().length();
            return Span.of(src, s, Math.max(s, Math.min(e, src.text().length())));
        }

        UNode node(Kind k, Node n) {
            Span s = span(n);
            return s == null ? null : new UNode(k, n.getClass().getSimpleName(), s, src);
        }

        /** Appends the conversion of {@code n} to {@code parent}, dissolving the synthetic snippet wrapper. */
        void into(UNode parent, Node n) {
            if (n instanceof ClassOrInterfaceDeclaration c && c.getNameAsString().equals(WRAP_CLASS)) {
                for (Node m : c.getMembers()) into(parent, m);
                return;
            }
            if (n instanceof MethodDeclaration m && m.getNameAsString().equals(WRAP_METHOD)) {
                m.getBody().ifPresent(b -> b.getStatements().forEach(s -> into(parent, s)));
                return;
            }
            UNode u = conv(n);
            if (u != null) parent.add(u);
        }

        void children(UNode u, Node n) {
            for (Node c : n.getChildNodes()) into(u, c);
        }

        UNode child(UNode u, Node n) {
            if (n == null) return null;
            for (UNode c : u.children()) if (c.span().equals(span(n)) && c.type().equals(n.getClass().getSimpleName())) return c;
            return null;
        }

        UNode conv(Node n) {
            if (n instanceof SimpleName || n instanceof Name || n instanceof Modifier || n instanceof Type
                || n instanceof Comment || n instanceof PackageDeclaration || n instanceof AnnotationExpr) return null;
            Kind k = kind(n);
            UNode u = node(k, n);
            if (u == null) return null;
            if (n instanceof UnparsableStmt) {
                diagnostics.add(Diagnostic.error("Unparsable statement", u.span()));
                return u;
            }
            if (n instanceof ImportDeclaration) return u.attr("text", n.toString().trim());
            children(u, n);
            decorate(u, n);
            return u;
        }

        static Kind kind(Node n) {
            if (n instanceof TypeDeclaration<?>) return Kind.CLASS;
            if (n instanceof CallableDeclaration<?>) return Kind.FUNCTION;
            if (n instanceof Parameter) return Kind.PARAM;
            if (n instanceof BlockStmt) return Kind.BLOCK;
            if (n instanceof VariableDeclarator) return Kind.DECL;
            if (n instanceof ForStmt || n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof DoStmt) return Kind.LOOP;
            if (n instanceof IfStmt) return Kind.IF;
            if (n instanceof SwitchStmt || n instanceof SwitchExpr) return Kind.SWITCH;
            if (n instanceof SwitchEntry) return Kind.CASE;
            if (n instanceof BreakStmt) return Kind.BREAK;
            if (n instanceof ContinueStmt) return Kind.CONTINUE;
            if (n instanceof ReturnStmt) return Kind.RETURN;
            if (n instanceof ThrowStmt) return Kind.THROW;
            if (n instanceof TryStmt) return Kind.TRY;
            if (n instanceof CatchClause) return Kind.CATCH;
            if (n instanceof MethodCallExpr || n instanceof ExplicitConstructorInvocationStmt) return Kind.CALL;
            if (n instanceof ObjectCreationExpr || n instanceof ArrayCreationExpr) return Kind.NEW;
            if (n instanceof FieldAccessExpr) return Kind.MEMBER;
            if (n instanceof ArrayAccessExpr) return Kind.INDEX;
            if (n instanceof AssignExpr) return Kind.ASSIGN;
            if (n instanceof BinaryExpr) return Kind.BINARY;
            if (n instanceof UnaryExpr) return Kind.UNARY;
            if (n instanceof ConditionalExpr) return Kind.TERNARY;
            if (n instanceof EnclosedExpr) return Kind.PAREN;
            if (n instanceof NameExpr) return Kind.IDENT;
            if (n instanceof StringLiteralExpr || n instanceof TextBlockLiteralExpr || n instanceof CharLiteralExpr) return Kind.STRING;
            if (n instanceof IntegerLiteralExpr || n instanceof LongLiteralExpr || n instanceof DoubleLiteralExpr) return Kind.NUMBER;
            if (n instanceof NullLiteralExpr) return Kind.NULL;
            if (n instanceof BooleanLiteralExpr) return Kind.BOOL;
            if (n instanceof LambdaExpr) return Kind.LAMBDA;
            if (n instanceof ExpressionStmt) return Kind.EXPR_STMT;
            if (n instanceof ArrayInitializerExpr) return Kind.LIST;
            if (n instanceof ImportDeclaration) return Kind.IMPORT;
            return Kind.OTHER;
        }

        void decorate(UNode u, Node n) {
            switch (n) {
                case TypeDeclaration<?> t -> u.name(t.getNameAsString()).attr("modifiers", mods(t.getModifiers()));
                case CallableDeclaration<?> m -> {
                    u.name(m.getNameAsString()).attr("modifiers", mods(m.getModifiers()));
                    List<UNode> ps = new ArrayList<>();
                    for (Parameter p : m.getParameters()) { UNode pu = child(u, p); if (pu != null) ps.add(pu); }
                    u.list("params", ps);
                    if (m instanceof MethodDeclaration md) {
                        md.getBody().ifPresent(b -> u.field("body", child(u, b)));
                        u.attr("returnType", md.getTypeAsString());
                    } else if (m instanceof com.github.javaparser.ast.body.ConstructorDeclaration cd) {
                        u.field("body", child(u, cd.getBody()));
                    }
                    if (m.getJavadocComment().isPresent()) u.attr("doc", "true");
                }
                case Parameter p -> u.name(p.getNameAsString()).attr("type", p.getTypeAsString());
                case VariableDeclarator v -> {
                    u.name(v.getNameAsString()).attr("type", v.getTypeAsString());
                    v.getInitializer().ifPresent(i -> u.field("value", child(u, i)));
                    if (v.getParentNode().orElse(null) instanceof FieldDeclaration f) {
                        u.attr("field", "true");
                        if (f.isFinal()) u.attr("final", "true");
                    } else if (v.getParentNode().orElse(null) instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr ve && ve.isFinal()) {
                        u.attr("final", "true");
                    }
                }
                case ForStmt f -> {
                    u.attr("loop", f.getCompare().isEmpty() ? "infinite" : "for");
                    if (!f.getInitialization().isEmpty()) u.field("init", child(u, f.getInitialization().get(0)));
                    f.getCompare().ifPresent(c -> u.field("cond", child(u, c)));
                    if (!f.getUpdate().isEmpty()) u.field("update", child(u, f.getUpdate().get(0)));
                    u.field("body", child(u, f.getBody()));
                }
                case ForEachStmt f -> u.attr("loop", "foreach").field("var", child(u, f.getVariable()))
                    .field("iter", child(u, f.getIterable())).field("body", child(u, f.getBody()));
                case WhileStmt w -> u.attr("loop", w.getCondition() instanceof BooleanLiteralExpr b && b.getValue() ? "infinite" : "while")
                    .field("cond", child(u, w.getCondition())).field("body", child(u, w.getBody()));
                case DoStmt d -> u.attr("loop", "do").field("cond", child(u, d.getCondition())).field("body", child(u, d.getBody()));
                case IfStmt i -> {
                    u.field("cond", child(u, i.getCondition())).field("then", child(u, i.getThenStmt()));
                    i.getElseStmt().ifPresent(e -> u.field("else", child(u, e)));
                }
                case SwitchEntry e -> u.attr("default", String.valueOf(e.isDefault() || e.getLabels().isEmpty()))
                    .attr("arrow", String.valueOf(e.getType() != SwitchEntry.Type.STATEMENT_GROUP));
                case TryStmt t -> {
                    if (!t.getResources().isEmpty()) u.attr("resources", "true");
                    u.field("body", child(u, t.getTryBlock()));
                    t.getFinallyBlock().ifPresent(f -> u.field("finally", child(u, f)));
                }
                case CatchClause c -> u.name(c.getParameter().getNameAsString())
                    .attr("type", c.getParameter().getTypeAsString()).field("body", child(u, c.getBody()));
                case MethodCallExpr m -> {
                    String scope = m.getScope().map(s -> s.toString().replaceAll("\\s+", "") + ".").orElse("");
                    u.name(scope + m.getNameAsString()).attr("method", m.getNameAsString());
                    m.getScope().ifPresent(s -> u.field("receiver", child(u, s)));
                    args(u, m.getArguments());
                }
                case ExplicitConstructorInvocationStmt e -> {
                    u.name(e.isThis() ? "this" : "super").attr("method", e.isThis() ? "this" : "super");
                    args(u, e.getArguments());
                }
                case ObjectCreationExpr o -> {
                    u.name(o.getType().getNameAsString()).attr("method", o.getType().getNameAsString())
                        .attr("typeArgs", o.getType().getTypeArguments().map(Object::toString).orElse(""));
                    args(u, o.getArguments());
                }
                case ArrayCreationExpr a -> {
                    u.name(a.getElementType().asString()).attr("method", a.getElementType().asString()).attr("array", "true")
                        .attr("dims", String.valueOf(a.getLevels().size()));
                    List<UNode> dims = new ArrayList<>();
                    a.getLevels().forEach(l -> l.getDimension().ifPresent(d -> {
                        UNode lv = child(u, l);
                        UNode du = lv == null ? null : child(lv, d);
                        if (du != null) dims.add(du);
                    }));
                    u.list("args", dims);
                }
                case FieldAccessExpr f -> u.name(f.getNameAsString()).field("object", child(u, f.getScope()));
                case ArrayAccessExpr a -> u.field("object", child(u, a.getName())).field("index", child(u, a.getIndex()));
                case AssignExpr a -> u.op(a.getOperator().asString()).field("target", child(u, a.getTarget())).field("value", child(u, a.getValue()));
                case BinaryExpr b -> u.op(b.getOperator().asString()).field("left", child(u, b.getLeft())).field("right", child(u, b.getRight()));
                case UnaryExpr x -> u.op(x.getOperator().asString()).field("operand", child(u, x.getExpression()))
                    .attr("postfix", String.valueOf(x.isPostfix()));
                case NameExpr x -> u.name(x.getNameAsString());
                case StringLiteralExpr s -> u.attr("value", s.getValue());
                case TextBlockLiteralExpr s -> u.attr("value", s.getValue());
                case CharLiteralExpr s -> u.attr("value", s.getValue()).attr("char", "true");
                case IntegerLiteralExpr x -> u.attr("value", x.getValue());
                case LongLiteralExpr x -> u.attr("value", x.getValue());
                case DoubleLiteralExpr x -> u.attr("value", x.getValue());
                case LambdaExpr l -> {
                    List<UNode> ps = new ArrayList<>();
                    for (Parameter p : l.getParameters()) { UNode pu = child(u, p); if (pu != null) ps.add(pu); }
                    u.list("params", ps).field("body", child(u, l.getBody()));
                }
                case ThisExpr t -> u.name("this");
                default -> { }
            }
        }

        private void args(UNode u, List<? extends Node> args) {
            List<UNode> list = new ArrayList<>();
            for (Node a : args) { UNode au = child(u, a); if (au != null) list.add(au); }
            u.list("args", list);
        }

        private static String mods(List<Modifier> ms) {
            StringBuilder sb = new StringBuilder();
            for (Modifier m : ms) sb.append(m.getKeyword().asString()).append(' ');
            return sb.toString().trim();
        }
    }
}
