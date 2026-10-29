package com.codereviewx.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.model.UNode;

/**
 * Builds the program that is sent to the judge.
 *
 * <p>Test mode: the user's code plus a generated driver that calls the user's function once per test case and
 * prints each result on its own marker line ({@code @@CRX:<case>:OK:<value>} or {@code ...:ERR:<message>}). The
 * function is found through the AST; typed languages get arguments built from the declared parameter types. All
 * cases run in one submission.
 *
 * <p>Stdin mode: the user's program as written, with only the adjustments the judge needs (e.g. Java's public
 * class must be called Main).
 */
public final class Harness {
    public static final String MARK = "@@CRX:";

    public record Program(String source, String stdin, String entry) {}

    /** A problem with the user's code that prevents building a runnable program (shown to the user as-is). */
    public static final class HarnessException extends RuntimeException {
        public HarnessException(String message) { super(message); }
    }

    private final LanguageRegistry languages;

    public Harness(LanguageRegistry languages) { this.languages = languages; }

    public Program forTests(String lang, String code, List<List<TestValue>> cases) {
        ParseResult pr = languages.get(lang).orElseThrow(() -> new HarnessException("Unsupported language.")).parse(code);
        int argc = cases.isEmpty() ? 0 : cases.get(0).size();
        UNode fn = entry(pr.root(), lang, argc);
        boolean needsList = cases.stream().flatMap(List::stream).anyMatch(v -> v instanceof TestValue.LinkedList);
        boolean hasListNode = pr.root().any(n -> n.is(Kind.CLASS) && "ListNode".equals(n.name()))
            || code.matches("(?s).*\\btype\\s+ListNode\\s+struct.*") || code.matches("(?s).*\\bstruct\\s+ListNode\\s*\\{.*");
        String src = switch (lang) {
            case "python" -> python(code, fn, cases, needsList && !hasListNode);
            case "javascript" -> javascript(code, fn, cases, needsList && !hasListNode);
            case "java" -> java(code, pr, fn, cases, needsList && !hasListNode);
            case "cpp" -> cpp(code, fn, cases, needsList && !hasListNode);
            case "c" -> c(code, fn, cases, needsList && !hasListNode);
            case "go" -> go(code, pr, fn, cases, needsList && !hasListNode);
            default -> throw new HarnessException("Unsupported language.");
        };
        return new Program(src, "", fn.name());
    }

    public Program forStdin(String lang, String code, String stdin) {
        ParseResult pr = languages.get(lang).orElseThrow(() -> new HarnessException("Unsupported language.")).parse(code);
        boolean hasMain = pr.root().find(Kind.FUNCTION).stream().anyMatch(f -> "main".equals(f.name()));
        String src = code;
        switch (lang) {
            case "java" -> {
                UNode main = pr.root().find(Kind.FUNCTION).stream().filter(f -> "main".equals(f.name()) && f.ancestor(Kind.CLASS) != null).findFirst()
                    .orElseThrow(() -> new HarnessException("To run with custom input, add a class with public static void main(String[] args)."));
                String cls = main.ancestor(Kind.CLASS).name();
                src = stripPublicTopLevel(stripPackage(code), pr);
                if (!"Main".equals(cls)) src = src.replaceAll("\\b" + Pattern.quote(cls) + "\\b", "Main");
            }
            case "c", "cpp" -> { if (!hasMain) throw new HarnessException("To run with custom input, add an int main() function that reads the input and prints the answer."); }
            case "go" -> {
                if (!hasMain) throw new HarnessException("To run with custom input, add func main() that reads the input and prints the answer.");
                if (!code.matches("(?s)\\s*(//[^\\n]*\\n\\s*)*package\\s+main\\b.*")) throw new HarnessException("Go programs must start with: package main");
            }
            default -> { }
        }
        return new Program(src, stdin == null ? "" : stdin, "main");
    }

    // ------------------------------------------------------------------ entry function

    /** The user's top-level function: not main/constructors, preferably not called by another function, with matching arity. */
    static UNode entry(UNode root, String lang, int argc) {
        List<UNode> fns = new ArrayList<>();
        for (UNode f : root.find(n -> n.is(Kind.FUNCTION) || n.is(Kind.LAMBDA) && n.name() != null)) {
            String n = f.name();
            if (n == null || n.equals("main") || n.startsWith("__") || n.equals("constructor") || f.type().equals("ConstructorDeclaration")
                || "true".equals(f.attr("method")) || f.ancestor(Kind.FUNCTION, Kind.LAMBDA) != null) continue;
            UNode owner = f.ancestor(Kind.CLASS);
            if (owner != null && (n.equals(owner.name()) || "ListNode".equals(owner.name()) || "TreeNode".equals(owner.name()))) continue; // constructors, node types
            fns.add(f);
        }
        if (fns.isEmpty()) throw new HarnessException("Couldn't find a function to test. Define a function that takes the test inputs and returns the answer.");
        Set<String> called = new HashSet<>();
        for (UNode f : fns) for (UNode c : f.find(Kind.CALL)) if (c.attr("method") != null) called.add(c.attr("method"));
        List<UNode> roots = fns.stream().filter(f -> !called.contains(f.name()) || selfOnly(f)).toList();
        List<UNode> pool = roots.isEmpty() ? fns : roots;
        for (UNode f : pool) if (arity(f, lang) == argc) return f;
        for (UNode f : pool) if (arity(f, lang) > argc && lang.matches("c|cpp")) return f; // arrays with an extra length parameter
        return pool.get(0);
    }

    private static boolean selfOnly(UNode f) {
        // A recursive function calls itself; that doesn't make it a helper.
        UNode root = f;
        while (root.parent() != null) root = root.parent();
        return root.find(Kind.CALL).stream().filter(c -> f.name().equals(c.attr("method"))).allMatch(c -> c.isWithin(f));
    }

    static int arity(UNode f, String lang) {
        int n = 0;
        for (UNode p : f.list("params")) n += p.attr("names") == null ? 1 : Integer.parseInt(p.attr("names"));
        if (isMethod(f, lang) && lang.equals("python") && n > 0) n--; // self
        return n;
    }

    private static boolean isMethod(UNode f, String lang) {
        UNode c = f.ancestor(Kind.CLASS);
        return c != null && c.name() != null;
    }

    // ------------------------------------------------------------------ Python

    private String python(String code, UNode fn, List<List<TestValue>> cases, boolean defineList) {
        StringBuilder b = new StringBuilder(code).append("\n\n# ---- CodeReviewX test driver ----\nimport sys as __crx_sys\n");
        if (defineList) b.append("class ListNode:\n    def __init__(self, val=0, next=None):\n        self.val = val\n        self.next = next\n");
        b.append("""
            def __crx_fmt(v):
                if v is None:
                    return "null"
                if isinstance(v, bool):
                    return "true" if v else "false"
                if hasattr(v, "val") and hasattr(v, "next"):
                    out, k = [], 0
                    while v is not None and k < 100000:
                        out.append(__crx_fmt(v.val)); v = v.next; k += 1
                    return "->".join(out) if out else "(empty)"
                if isinstance(v, (list, tuple)):
                    return "[" + ",".join(__crx_fmt(x) for x in v) + "]"
                return str(v)
            def __crx_list(vals):
                head = None
                for x in reversed(vals):
                    node = ListNode(x)
                    node.next = head
                    head = node
                return head
            """);
        UNode cls = fn.ancestor(Kind.CLASS);
        String call = cls != null && cls.name() != null ? cls.name() + "()." + fn.name() : fn.name();
        b.append("__crx_cases = [\n");
        for (List<TestValue> c : cases) {
            List<String> args = new ArrayList<>();
            for (TestValue v : c) args.add(py(v));
            b.append("    (").append(String.join(", ", args)).append(args.size() == 1 ? ",)" : ")").append(",\n");
        }
        b.append("]\nfor __crx_i, __crx_args in enumerate(__crx_cases):\n")
            .append("    try:\n        __crx_r = ").append(call).append("(*__crx_args)\n")
            .append("        print(\"\\n" + MARK + "%d:OK:%s\" % (__crx_i, __crx_fmt(__crx_r).replace(\"\\n\", \"\\\\n\")), flush=True)\n")
            .append("    except BaseException as __crx_e:\n")
            .append("        print(\"\\n" + MARK + "%d:ERR:%s: %s\" % (__crx_i, type(__crx_e).__name__, str(__crx_e).replace(\"\\n\", \" \")), flush=True)\n");
        return b.toString();
    }

    private static String py(TestValue v) {
        return switch (v) {
            case TestValue.Num n -> n.text();
            case TestValue.Bool x -> x.value() ? "True" : "False";
            case TestValue.Str s -> TestValue.quote(s.value());
            case TestValue.Arr a -> "[" + String.join(", ", a.items().stream().map(Harness::py).toList()) + "]";
            case TestValue.LinkedList l -> l.items().isEmpty() ? "None" : "__crx_list([" + String.join(", ", l.items().stream().map(Harness::py).toList()) + "])";
        };
    }

    // ------------------------------------------------------------------ JavaScript

    private String javascript(String code, UNode fn, List<List<TestValue>> cases, boolean defineList) {
        StringBuilder b = new StringBuilder(code).append("\n\n// ---- CodeReviewX test driver ----\n");
        if (defineList) b.append("class ListNode { constructor(val = 0, next = null) { this.val = val; this.next = next; } }\n");
        b.append("""
            function __crxFmt(v) {
              if (v === null || v === undefined) return "null";
              if (typeof v === "boolean") return v ? "true" : "false";
              if (Array.isArray(v)) return "[" + v.map(__crxFmt).join(",") + "]";
              if (typeof v === "object" && "val" in v && "next" in v) {
                const out = []; let k = 0;
                while (v && k++ < 100000) { out.push(__crxFmt(v.val)); v = v.next; }
                return out.length ? out.join("->") : "(empty)";
              }
              return String(v);
            }
            function __crxList(vals) { let head = null; for (let i = vals.length - 1; i >= 0; i--) { const n = new ListNode(vals[i]); n.next = head; head = n; } return head; }
            """);
        UNode cls = fn.ancestor(Kind.CLASS);
        String call = cls != null && cls.name() != null ? "new " + cls.name() + "()." + fn.name() : fn.name();
        b.append("const __crxCases = [\n");
        for (List<TestValue> c : cases) b.append("  () => [").append(String.join(", ", c.stream().map(Harness::js).toList())).append("],\n");
        b.append("];\n__crxCases.forEach((args, i) => {\n  try {\n    const r = ").append(call).append("(...args());\n")
            .append("    console.log(\"\\n" + MARK + "\" + i + \":OK:\" + __crxFmt(r).replace(/\\n/g, \"\\\\n\"));\n")
            .append("  } catch (e) {\n    console.log(\"\\n" + MARK + "\" + i + \":ERR:\" + String(e && e.message || e).replace(/\\n/g, \" \"));\n  }\n});\n");
        return b.toString();
    }

    private static String js(TestValue v) {
        return switch (v) {
            case TestValue.Num n -> n.text();
            case TestValue.Bool x -> String.valueOf(x.value());
            case TestValue.Str s -> TestValue.quote(s.value());
            case TestValue.Arr a -> "[" + String.join(", ", a.items().stream().map(Harness::js).toList()) + "]";
            case TestValue.LinkedList l -> l.items().isEmpty() ? "null" : "__crxList([" + String.join(", ", l.items().stream().map(Harness::js).toList()) + "])";
        };
    }

    // ------------------------------------------------------------------ Java

    private String java(String code, ParseResult pr, UNode fn, List<List<TestValue>> cases, boolean defineList) {
        boolean wrapped = fn.ancestor(Kind.CLASS) == null;
        String cls = wrapped ? "Solution" : fn.ancestor(Kind.CLASS).name();
        String user = stripPublicTopLevel(stripPackage(code), pr);
        if (user.matches("(?s).*\\bclass\\s+Main\\b.*")) {
            user = user.replaceAll("\\bMain\\b", "CrxUserMain");
            if ("Main".equals(cls)) cls = "CrxUserMain";
        }
        StringBuilder b = new StringBuilder("import java.util.*; ");
        b.append(wrapped ? "class Solution { " + user + "\n}\n" : user + "\n");
        b.append("\n// ---- CodeReviewX test driver ----\n");
        if (defineList) b.append("class ListNode { int val; ListNode next; ListNode() {} ListNode(int val) { this.val = val; } ListNode(int val, ListNode next) { this.val = val; this.next = next; } }\n");
        b.append("""
            public class Main {
                static String crxFmt(Object v) {
                    if (v == null) return "null";
                    if (v.getClass().isArray()) {
                        StringBuilder b = new StringBuilder("[");
                        for (int i = 0; i < java.lang.reflect.Array.getLength(v); i++) { if (i > 0) b.append(','); b.append(crxFmt(java.lang.reflect.Array.get(v, i))); }
                        return b.append(']').toString();
                    }
                    if (v instanceof Iterable<?> it) {
                        StringBuilder b = new StringBuilder("["); boolean first = true;
                        for (Object o : it) { if (!first) b.append(','); first = false; b.append(crxFmt(o)); }
                        return b.append(']').toString();
                    }
                    if (v.getClass().getSimpleName().equals("ListNode")) {
                        try {
                            java.lang.reflect.Field val = v.getClass().getDeclaredField("val"), next = v.getClass().getDeclaredField("next");
                            val.setAccessible(true); next.setAccessible(true);
                            StringBuilder b = new StringBuilder(); int k = 0;
                            for (Object p = v; p != null && k < 100000; p = next.get(p), k++) { if (k > 0) b.append("->"); b.append(crxFmt(val.get(p))); }
                            return b.toString();
                        } catch (ReflectiveOperationException e) { return String.valueOf(v); }
                    }
                    return String.valueOf(v);
                }
            """);
        if (defineList || code.contains("ListNode")) {
            b.append("    static ListNode crxList(int... v) { ListNode h = null; for (int i = v.length - 1; i >= 0; i--) { ListNode n = new ListNode(v[i]); n.next = h; h = n; } return h; }\n");
        }
        b.append("    public static void main(String[] crxArgs) {\n");
        boolean isStatic = String.valueOf(fn.attr("modifiers")).contains("static");
        String target = isStatic ? cls + "." + fn.name() : "new " + cls + "()." + fn.name();
        boolean isVoid = "void".equals(fn.attr("returnType"));
        List<UNode> params = fn.list("params");
        for (int i = 0; i < cases.size(); i++) {
            List<TestValue> c = cases.get(i);
            if (c.size() != params.size()) throw new HarnessException(fn.name() + "() takes " + params.size() + " parameter(s) but each test supplies " + c.size() + ".");
            List<String> args = new ArrayList<>();
            for (int k = 0; k < c.size(); k++) args.add(javaLit(params.get(k).attr("type"), c.get(k)));
            String callExpr = target + "(" + String.join(", ", args) + ")";
            b.append("        try { ").append(isVoid ? callExpr + "; Object r = null;" : "Object r = " + callExpr + ";")
                .append(" System.out.println(\"\\n" + MARK + i + ":OK:\" + crxFmt(r).replace(\"\\n\", \"\\\\n\")); }")
                .append(" catch (Throwable e) { System.out.println(\"\\n" + MARK + i + ":ERR:\" + String.valueOf(e).replace(\"\\n\", \" \")); }\n");
        }
        b.append("    }\n}\n");
        return b.toString();
    }

    static String javaLit(String rawType, TestValue v) {
        String t = rawType == null ? "" : rawType.replaceAll("\\bfinal\\s+", "").replaceAll("\\s+", "").replace("...", "[]");
        switch (t) {
            case "int", "short", "byte", "Integer", "Short", "Byte": return num(v, t, "");
            case "long", "Long": return num(v, t, "L");
            case "double", "Double": return num(v, t, "");
            case "float", "Float": return num(v, t, "f");
            case "boolean", "Boolean": if (v instanceof TestValue.Bool b) return String.valueOf(b.value()); break;
            case "String", "CharSequence": if (v instanceof TestValue.Str s) return TestValue.quote(s.value()); break;
            case "char", "Character": if (v instanceof TestValue.Str s && s.value().length() == 1) return "'" + s.value().replace("\\", "\\\\").replace("'", "\\'") + "'"; break;
            case "char[]": if (v instanceof TestValue.Str s) return TestValue.quote(s.value()) + ".toCharArray()"; break;
            case "ListNode":
                if (v instanceof TestValue.LinkedList l) return l.items().isEmpty() ? "null" : "crxList(" + String.join(", ", l.items().stream().map(x -> num(x, "int", "")).toList()) + ")";
                break;
            default: break;
        }
        if (t.endsWith("[]") && v instanceof TestValue.Arr a) {
            String elem = t.substring(0, t.length() - 2);
            return "new " + t + "{" + String.join(", ", a.items().stream().map(x -> javaArrayItem(elem, x)).toList()) + "}";
        }
        Matcher m = Pattern.compile("(?:java\\.util\\.)?(List|ArrayList|LinkedList|Collection)<(.+)>").matcher(t);
        if (m.matches() && v instanceof TestValue.Arr a) {
            String elem = m.group(2);
            if (a.items().isEmpty()) return "new ArrayList<" + elem + ">()";
            return "new ArrayList<" + elem + ">(Arrays.asList(" + String.join(", ", a.items().stream().map(x -> javaLit(elem, x)).toList()) + "))";
        }
        throw new HarnessException("Parameter type '" + rawType + "' isn't supported by the automatic tests. Use int, long, double, boolean, String, arrays, Lists or ListNode.");
    }

    /** Nested array initializers omit the "new T[]" for inner levels. */
    private static String javaArrayItem(String elem, TestValue v) {
        if (elem.endsWith("[]") && v instanceof TestValue.Arr a) {
            String inner = elem.substring(0, elem.length() - 2);
            return "{" + String.join(", ", a.items().stream().map(x -> javaArrayItem(inner, x)).toList()) + "}";
        }
        return javaLit(elem, v);
    }

    private static String num(TestValue v, String type, String suffix) {
        if (v instanceof TestValue.Num n) return n.text() + suffix;
        throw new HarnessException("A test value doesn't fit the parameter type '" + type + "'.");
    }

    static String stripPackage(String code) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;").matcher(code);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(" ".repeat(m.group().length())));
        m.appendTail(out);
        return out.toString();
    }

    /** Judge0 compiles Main.java, so no other top-level type may be public. Edits keep every line where it was. */
    static String stripPublicTopLevel(String code, ParseResult pr) {
        StringBuilder b = new StringBuilder(code);
        List<UNode> classes = pr.root().children().stream().filter(n -> n.is(Kind.CLASS)).toList();
        for (int i = classes.size() - 1; i >= 0; i--) {
            UNode c = classes.get(i);
            int s = c.span().start(), brace = code.indexOf('{', s);
            if (brace < 0) continue;
            Matcher m = Pattern.compile("\\bpublic\\s").matcher(code.substring(s, brace));
            if (m.find()) b.replace(s + m.start(), s + m.start() + 6, "      ");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ C++

    private String cpp(String code, UNode fn, List<List<TestValue>> cases, boolean defineList) {
        StringBuilder b = new StringBuilder("#include <bits/stdc++.h>\nusing namespace std;\n");
        if (defineList) b.append("struct ListNode { int val; ListNode *next; ListNode() : val(0), next(nullptr) {} ListNode(int x) : val(x), next(nullptr) {} ListNode(int x, ListNode *n) : val(x), next(n) {} };\n");
        b.append("#define main crx_user_main\n#line 1\n").append(code).append("\n#undef main\n// ---- CodeReviewX test driver ----\n");
        b.append("""
            static string crxFmt(bool v) { return v ? "true" : "false"; }
            static string crxFmt(char v) { return string(1, v); }
            static string crxFmt(const string& v) { return v; }
            static string crxFmt(const char* v) { return v ? string(v) : string("null"); }
            template <class T> static typename enable_if<is_arithmetic<T>::value, string>::type crxFmt(T v) { ostringstream o; o << v; return o.str(); }
            template <class T> static string crxFmt(const vector<T>& v) { string s = "["; for (size_t i = 0; i < v.size(); i++) { if (i) s += ","; s += crxFmt(v[i]); } return s + "]"; }
            """);
        boolean lists = defineList || code.contains("ListNode");
        if (lists) b.append("""
            static string crxFmt(ListNode* p) { if (!p) return "(empty)"; string s; int k = 0; while (p && k < 100000) { if (k++) s += "->"; s += to_string(p->val); p = p->next; } return s; }
            static ListNode* crxList(const vector<int>& v) { ListNode* h = nullptr; for (int i = (int) v.size() - 1; i >= 0; i--) { ListNode* n = new ListNode(v[i]); n->next = h; h = n; } return h; }
            """);
        b.append("int main() {\n");
        UNode cls = fn.ancestor(Kind.CLASS);
        String target = cls != null && cls.name() != null ? "crxObj." + fn.name() : fn.name();
        boolean isVoid = "void".equals(fn.attr("returnType"));
        for (int i = 0; i < cases.size(); i++) {
            List<String> decls = new ArrayList<>(), args = new ArrayList<>();
            cArgs(fn, cases.get(i), true, decls, args);
            b.append("    {\n");
            if (cls != null && cls.name() != null) b.append("        ").append(cls.name()).append(" crxObj;\n");
            for (String d : decls) b.append("        ").append(d).append('\n');
            String call = target + "(" + String.join(", ", args) + ")";
            b.append("        try { ").append(isVoid ? call + "; cout << \"\\n" + MARK + i + ":OK:null\" << endl;" : "auto r = " + call + "; cout << \"\\n" + MARK + i + ":OK:\" << crxFmt(r) << endl;")
                .append(" } catch (const exception& e) { cout << \"\\n" + MARK + i + ":ERR:\" << e.what() << endl; } catch (...) { cout << \"\\n" + MARK + i + ":ERR:exception\" << endl; }\n    }\n");
        }
        b.append("    return 0;\n}\n");
        return b.toString();
    }

    // ------------------------------------------------------------------ C

    private String c(String code, UNode fn, List<List<TestValue>> cases, boolean defineList) {
        StringBuilder b = new StringBuilder("#include <stdio.h>\n#include <stdlib.h>\n#include <string.h>\n#include <stdbool.h>\n#include <limits.h>\n");
        if (defineList) b.append("struct ListNode { int val; struct ListNode *next; };\ntypedef struct ListNode ListNode;\n");
        b.append("#define main crx_user_main\n#line 1\n").append(code).append("\n#undef main\n/* ---- CodeReviewX test driver ---- */\n");
        boolean lists = defineList || code.contains("ListNode");
        if (lists) b.append("static struct ListNode* crxList(const int* v, int n) { struct ListNode* h = NULL; for (int i = n - 1; i >= 0; i--) { struct ListNode* x = (struct ListNode*) malloc(sizeof *x); x->val = v[i]; x->next = h; h = x; } return h; }\n");
        String rt = String.valueOf(fn.attr("returnType")).replaceAll("\\b(const|static|inline|unsigned)\\b", "").trim();
        b.append("int main(void) {\n");
        for (int i = 0; i < cases.size(); i++) {
            List<String> decls = new ArrayList<>(), args = new ArrayList<>();
            cArgs(fn, cases.get(i), false, decls, args);
            b.append("    {\n");
            for (String d : decls) b.append("        ").append(d).append('\n');
            String call = fn.name() + "(" + String.join(", ", args) + ")";
            String prefix = "printf(\"\\n" + MARK + i + ":OK:";
            b.append("        ").append(switch (rt.replaceAll("\\s+", " ")) {
                case "void" -> call + "; " + prefix + "null\\n\");";
                case "bool", "_Bool" -> prefix + "%s\\n\", " + call + " ? \"true\" : \"false\");";
                case "char*", "char *" -> "{ const char* r = " + call + "; " + prefix + "%s\\n\", r ? r : \"null\"); }";
                case "char" -> prefix + "%c\\n\", " + call + ");";
                case "long long" -> prefix + "%lld\\n\", (long long) " + call + ");";
                case "long" -> prefix + "%ld\\n\", (long) " + call + ");";
                case "double", "float" -> prefix + "%g\\n\", (double) " + call + ");";
                case "struct ListNode*", "ListNode*", "struct ListNode *", "ListNode *" ->
                    "{ struct ListNode* p = " + call + "; printf(\"\\n" + MARK + i + ":OK:\"); if (!p) printf(\"(empty)\"); for (int k = 0; p && k < 100000; p = p->next, k++) printf(k ? \"->%d\" : \"%d\", p->val); printf(\"\\n\"); }";
                default -> prefix + "%d\\n\", (int) " + call + ");";
            }).append("\n        fflush(stdout);\n    }\n");
        }
        b.append("    return 0;\n}\n");
        return b.toString();
    }

    /** Declarations and arguments for C/C++. Arrays passed as pointer/array get their length added when the function expects it. */
    private static void cArgs(UNode fn, List<TestValue> values, boolean cpp, List<String> decls, List<String> args) {
        List<UNode> params = fn.list("params");
        int vi = 0;
        String lastArray = null;
        for (int pi = 0; pi < params.size(); pi++) {
            UNode p = params.get(pi);
            String type = String.valueOf(p.attr("type")).replaceAll("\\bconst\\b", "").replaceAll("\\s+", " ").trim();
            boolean ptr = p.flag("pointer") || p.flag("array");
            String var = "a" + pi;
            // An integer parameter right after an array is its length (int arr[], int n), unless the tests supply it.
            boolean integral = !ptr && type.matches("(unsigned )?(int|long|long long|size_t|short)");
            boolean lengthName = String.valueOf(p.name()).matches("(?i)n|len|length|size|sz|count|num|cnt|arr_?size|arr_?len");
            if (lastArray != null && integral && (params.size() - pi > values.size() - vi || lengthName && params.size() - pi >= values.size() - vi)) {
                args.add(lastArray + "_n");
                lastArray = null;
                continue;
            }
            lastArray = null;
            if (vi >= values.size()) throw new HarnessException(fn.name() + "() has more parameters than the test inputs provide.");
            TestValue v = values.get(vi++);
            if (type.contains("ListNode")) {
                if (!(v instanceof TestValue.LinkedList l)) throw new HarnessException("A test value doesn't fit the ListNode parameter.");
                String items = String.join(", ", l.items().stream().map(x -> num(x, "int", "")).toList());
                if (cpp) decls.add("ListNode* " + var + " = crxList({" + items + "});");
                else decls.add(l.items().isEmpty() ? "struct ListNode* " + var + " = NULL;" : "int " + var + "_v[] = {" + items + "}; struct ListNode* " + var + " = crxList(" + var + "_v, " + l.items().size() + ");");
                args.add(var);
                continue;
            }
            if (ptr && type.equals("char") && v instanceof TestValue.Str s) {
                decls.add("char " + var + "[] = " + TestValue.quote(s.value()) + ";");
                args.add(var);
                continue;
            }
            if (ptr && v instanceof TestValue.Arr a) {
                if (a.items().stream().anyMatch(x -> x instanceof TestValue.Arr)) throw new HarnessException("2-D array parameters aren't supported by the automatic tests in C/C++ pointer form; use vector<vector<int>> in C++.");
                String items = String.join(", ", a.items().stream().map(x -> cScalar(type, x)).toList());
                decls.add(type + " " + var + (a.items().isEmpty() ? "[1] = {0};" : "[] = {" + items + "};"));
                decls.add("int " + var + "_n = " + a.items().size() + ";");
                args.add(var);
                lastArray = var;
                continue;
            }
            if (cpp && type.replace("std::", "").matches("vector<.*>")) {
                if (!(v instanceof TestValue.Arr)) throw new HarnessException("A test value doesn't fit the parameter type '" + type + "'.");
                decls.add(type + " " + var + " = " + cppInit(type.replace("std::", ""), v) + ";");
                args.add(var);
                continue;
            }
            if (cpp && type.replace("std::", "").equals("string")) {
                if (!(v instanceof TestValue.Str s)) throw new HarnessException("A test value doesn't fit the string parameter.");
                decls.add("string " + var + " = " + TestValue.quote(s.value()) + ";");
                args.add(var);
                continue;
            }
            decls.add(type + " " + var + " = " + cScalar(type, v) + ";");
            args.add(var);
        }
        if (vi < values.size()) throw new HarnessException(fn.name() + "() takes fewer parameters than each test supplies.");
    }

    private static String cppInit(String type, TestValue v) {
        if (v instanceof TestValue.Arr a) {
            String inner = type.matches("vector<(.*)>") ? type.replaceAll("^vector<(.*)>$", "$1") : type;
            return "{" + String.join(", ", a.items().stream().map(x -> cppInit(inner, x)).toList()) + "}";
        }
        if (v instanceof TestValue.Str s) return type.contains("char") && s.value().length() == 1 ? "'" + s.value() + "'" : TestValue.quote(s.value());
        return cScalar(type, v);
    }

    private static String cScalar(String type, TestValue v) {
        return switch (v) {
            case TestValue.Num n -> n.text() + (type.contains("long long") && n.integral() ? "LL" : "");
            case TestValue.Bool b -> b.value() ? "true" : "false";
            case TestValue.Str s -> type.contains("char") && s.value().length() == 1 ? "'" + s.value() + "'" : TestValue.quote(s.value());
            default -> throw new HarnessException("A test value doesn't fit the parameter type '" + type + "'.");
        };
    }

    // ------------------------------------------------------------------ Go

    private String go(String code, ParseResult pr, UNode fn, List<List<TestValue>> cases, boolean defineList) {
        String user = code;
        UNode userMain = pr.root().find(Kind.FUNCTION).stream().filter(f -> "main".equals(f.name()) && !"true".equals(f.attr("method"))).findFirst().orElse(null);
        if (userMain != null && userMain.field("name") != null) {
            UNode n = userMain.field("name");
            user = user.substring(0, n.span().start()) + "crxUserMain" + user.substring(n.span().end());
        }
        String imports = "import (crxfmt \"fmt\"; crxstrings \"strings\"; crxreflect \"reflect\");";
        Matcher pkg = Pattern.compile("(?m)^\\s*package\\s+\\w+\\s*$").matcher(user);
        if (pkg.find()) user = user.substring(0, pkg.start()) + "package main; " + imports + user.substring(pkg.end());
        else user = "package main; " + imports + " " + user;
        StringBuilder b = new StringBuilder(user).append("\n\n// ---- CodeReviewX test driver ----\n");
        if (defineList) b.append("type ListNode struct { Val int; Next *ListNode }\n");
        b.append("""
            func crxFmt(v interface{}) string {
                if v == nil { return "null" }
                rv := crxreflect.ValueOf(v)
                switch rv.Kind() {
                case crxreflect.Bool:
                    if rv.Bool() { return "true" }
                    return "false"
                case crxreflect.String:
                    return rv.String()
                case crxreflect.Slice, crxreflect.Array:
                    parts := []string{}
                    for i := 0; i < rv.Len(); i++ { parts = append(parts, crxFmt(rv.Index(i).Interface())) }
                    return "[" + crxstrings.Join(parts, ",") + "]"
                case crxreflect.Ptr:
                    if rv.IsNil() { return "null" }
                    if e := rv.Elem(); e.Kind() == crxreflect.Struct && e.FieldByName("Val").IsValid() && e.FieldByName("Next").IsValid() {
                        parts := []string{}
                        for p, k := rv, 0; !p.IsNil() && k < 100000; p, k = p.Elem().FieldByName("Next"), k+1 { parts = append(parts, crxfmt.Sprint(p.Elem().FieldByName("Val"))) }
                        return crxstrings.Join(parts, "->")
                    }
                }
                return crxfmt.Sprint(v)
            }
            """);
        boolean lists = defineList || code.contains("ListNode");
        if (lists) b.append("func crxList(v []int) *ListNode { var h *ListNode; for i := len(v) - 1; i >= 0; i-- { h = &ListNode{Val: v[i], Next: h} }; return h }\n");
        List<String> types = new ArrayList<>();
        for (UNode p : fn.list("params")) {
            int n = p.attr("names") == null ? 1 : Integer.parseInt(p.attr("names"));
            for (int k = 0; k < n; k++) types.add(String.valueOf(p.attr("type")).replaceAll("\\s+", ""));
        }
        String ret = String.valueOf(fn.attr("returnType")).trim();
        int results = ret.isEmpty() ? 0 : ret.startsWith("(") ? ret.split(",").length : 1;
        b.append("func main() {\n");
        for (int i = 0; i < cases.size(); i++) {
            List<TestValue> c = cases.get(i);
            if (c.size() != types.size()) throw new HarnessException(fn.name() + "() takes " + types.size() + " parameter(s) but each test supplies " + c.size() + ".");
            List<String> args = new ArrayList<>();
            for (int k = 0; k < c.size(); k++) args.add(goLit(types.get(k), c.get(k)));
            String call = fn.name() + "(" + String.join(", ", args) + ")";
            String lhs = results == 0 ? "" : results == 1 ? "r := " : "r" + ", _".repeat(results - 1) + " := ";
            b.append("    func() {\n        defer func() { if e := recover(); e != nil { crxfmt.Println(\"\\n" + MARK + i + ":ERR:\" + crxfmt.Sprint(e)) } }()\n")
                .append("        ").append(lhs).append(call).append('\n')
                .append("        crxfmt.Println(\"\\n" + MARK + i + ":OK:\" + ").append(results == 0 ? "\"null\"" : "crxFmt(r)").append(")\n    }()\n");
        }
        b.append("}\n");
        return b.toString();
    }

    private static String goLit(String type, TestValue v) {
        if (type.equals("*ListNode")) {
            if (v instanceof TestValue.LinkedList l) return l.items().isEmpty() ? "nil" : "crxList([]int{" + String.join(", ", l.items().stream().map(x -> num(x, "int", "")).toList()) + "})";
            throw new HarnessException("A test value doesn't fit the *ListNode parameter.");
        }
        if (type.startsWith("[]") && v instanceof TestValue.Arr a) {
            String elem = type.substring(2);
            return type + "{" + String.join(", ", a.items().stream().map(x -> elem.startsWith("[]") && x instanceof TestValue.Arr ? goLit(elem, x).substring(elem.length()) : goLit(elem, x)).toList()) + "}";
        }
        return switch (v) {
            case TestValue.Num n -> n.text();
            case TestValue.Bool b -> String.valueOf(b.value());
            case TestValue.Str s -> TestValue.quote(s.value());
            default -> throw new HarnessException("Parameter type '" + type + "' isn't supported by the automatic tests.");
        };
    }
}
