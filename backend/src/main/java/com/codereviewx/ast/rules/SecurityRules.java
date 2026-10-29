package com.codereviewx.ast.rules;

import static com.codereviewx.ast.rules.BaseRule.rule;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

final class SecurityRules {
    private SecurityRules() {}

    static final Pattern CREDENTIAL_NAME = Pattern.compile("(?i).*(password|passwd|pwd|secret|api_?key|token|access_?key|private_?key|secret_?key|client_?secret)$");
    private static final Pattern SQL_START = Pattern.compile("(?is)^\\s*(SELECT|INSERT|UPDATE|DELETE|REPLACE|MERGE)\\b.*");
    private static final Set<String> PLACEHOLDERS = Set.of("", "changeme", "xxx", "todo", "your_api_key", "<secret>", "none", "null");

    static List<Rule> all() {
        return List.of(GETS, UNBOUNDED_COPY, SCANF_STRING, DYNAMIC_EXEC, HARDCODED_CREDENTIAL, SQL_CONCAT, COMMAND_INJECTION,
            INSECURE_RANDOM, WEAK_HASH, INNER_HTML);
    }

    static final Rule GETS = rule("security.gets", "security", "critical").langs("c", "cpp")
        .title("Unbounded read with gets()")
        .explain("gets() copies input into the buffer until a newline, with no idea how large the buffer is.")
        .why("Any input longer than the buffer overwrites adjacent memory. This is a classic buffer overflow, and gets() was removed from the C11 standard for this reason.")
        .changes("Replaced gets() with fgets(), passing the buffer size so the read stops before the end of the buffer.")
        .benefits("Input can no longer write past the buffer.")
        .tradeoffs("fgets() keeps the trailing newline. Strip it with buf[strcspn(buf, \"\\n\")] = '\\0' if needed. Over-long input is truncated, not rejected.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CALL)) {
                if (!"gets".equals(c.name()) || c.list("args").size() != 1) continue;
                String buf = c.list("args").get(0).text();
                ctx.report(self, c, f -> ctx.applyEdits(f, List.of(Edit.replace(c, "fgets(" + buf + ", sizeof " + buf + ", stdin)"))));
            }
        });

    static final Rule UNBOUNDED_COPY = rule("security.unbounded-copy", "security", "high").langs("c", "cpp").max(3)
        .title("Unchecked string copy")
        .explain("strcpy, strcat and sprintf write into the destination without checking its size.")
        .why("If the source is longer than the space left in the destination, the write overflows the buffer.")
        .changes("Switched to the size-bounded variant and passed the destination's capacity.")
        .benefits("Writes stop at the end of the destination buffer.")
        .tradeoffs("Output may be silently truncated. Check snprintf's return value if truncation matters. sizeof only works on real arrays, not pointers.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CALL)) {
                String n = c.name();
                List<UNode> a = c.list("args");
                if (n == null || a.size() < 2 || !Set.of("strcpy", "strcat", "sprintf").contains(n)) continue;
                String dst = a.get(0).text();
                boolean isArray = a.get(0).is(Kind.IDENT) && "true".equals(declOf(ctx, a.get(0).name(), c, "array"));
                String rest = ctx.src().slice(a.get(1).span().start(), a.get(a.size() - 1).span().end());
                String fix = switch (n) {
                    case "strcpy" -> "snprintf(" + dst + ", sizeof " + dst + ", \"%s\", " + rest + ")";
                    case "strcat" -> "strncat(" + dst + ", " + rest + ", sizeof " + dst + " - strlen(" + dst + ") - 1)";
                    default -> "snprintf(" + dst + ", sizeof " + dst + ", " + rest + ")";
                };
                ctx.report(self, c, f -> {
                    ctx.applyEdits(f, List.of(Edit.replace(c, fix)));
                    if (!isArray) {
                        f.confidence("medium");
                        f.assumptions = "The destination is not a known fixed-size array here, so replace sizeof with the real capacity.";
                    }
                });
            }
        });

    static final Rule SCANF_STRING = rule("security.scanf-unbounded", "security", "high").langs("c", "cpp")
        .title("scanf reads a string without a width")
        .explain("A %s conversion with no field width reads an arbitrarily long word into the buffer.")
        .why("Input longer than the buffer overflows it, exactly like gets().")
        .changes("Give every %s a maximum width one less than the buffer size, e.g. %15s for char buf[16].")
        .benefits("The read can no longer run past the buffer.")
        .tradeoffs("Longer words are split across reads; check the return value of scanf.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CALL)) {
                if (!Set.of("scanf", "fscanf", "sscanf").contains(String.valueOf(c.name()))) continue;
                UNode fmt = c.list("args").stream().filter(a -> a.is(Kind.STRING)).findFirst().orElse(null);
                if (fmt == null || !fmt.attr("value").matches("(?s).*%s.*")) continue;
                ctx.report(self, c, f -> ctx.applyEdits(f, List.of(Edit.replace(fmt, fmt.text().replace("%s", "%15s")))));
            }
        });

    static final Rule DYNAMIC_EXEC = rule("security.dynamic-exec", "security", "high").langs("javascript", "python")
        .title("Dynamic code execution")
        .explain("eval/exec runs a string as code.")
        .why("If any part of that string comes from input, an attacker can run arbitrary code. It also defeats static analysis and optimisation.")
        .changes("Parse the data with a parser that only accepts literals, instead of executing it.")
        .benefits("Input can't execute code.")
        .tradeoffs("Only works when the string is meant to be data. Real expression evaluation needs a purpose-built parser.")
        .check((ctx, self) -> {
            for (UNode n : ctx.all(Kind.CALL)) check(ctx, self, n);
            for (UNode n : ctx.all(Kind.NEW)) if ("Function".equals(n.name())) check(ctx, self, n);
        });

    private static void check(RuleContext ctx, Rule self, UNode c) {
        String name = c.name();
        boolean exec = ctx.lang("python") ? Set.of("eval", "exec").contains(name)
            : Set.of("eval", "Function", "setTimeout", "setInterval").contains(name);
        if (!exec || c.list("args").isEmpty()) return;
        UNode arg = c.list("args").get(0);
        if ((name.startsWith("set")) && !arg.is(Kind.STRING) && !arg.is(Kind.BINARY)) return; // callbacks are fine
        boolean literal = arg.is(Kind.STRING) && !arg.flag("interpolated");
        boolean tainted = ctx.dataFlow().isTainted(arg, ctx.scopeOf(c));
        ctx.report(self, c, f -> {
            f.template(ctx.lang("python") ? "import ast\nvalue = ast.literal_eval(text)  # parses literals only"
                : "const value = JSON.parse(text); // data only, never code");
            if (literal) {
                f.sev = "medium";
                f.confidence("low");
                f.assumptions = "The argument is a constant string, so this is not injectable as written; it is still hard to analyse.";
            } else if (tainted) {
                f.source = "dataflow";
                f.explain = "eval/exec runs a string as code, and that string is built from program input.";
            } else {
                f.confidence("medium");
            }
        });
    }

    static final Rule HARDCODED_CREDENTIAL = rule("security.hardcoded-credential", "security", "high")
        .title("Hard-coded credential")
        .explain("A credential-like value is written directly into the source.")
        .why("Anything in source control is visible to everyone with repository access and is very hard to fully remove from history.")
        .changes("Read the value from the environment, or a secrets manager, at runtime.")
        .benefits("The secret stays out of the codebase and can be rotated without a code change.")
        .tradeoffs("Deployments must now provide the variable. Add a clear error when it's missing.")
        .check((ctx, self) -> {
            Map<String, String> tpl = Map.of("python", "import os\nAPI_KEY = os.environ[\"API_KEY\"]",
                "javascript", "const apiKey = process.env.API_KEY;", "java", "String apiKey = System.getenv(\"API_KEY\");",
                "go", "apiKey := os.Getenv(\"API_KEY\")", "c", "const char *api_key = getenv(\"API_KEY\");",
                "cpp", "const char* apiKey = std::getenv(\"API_KEY\");");
            ctx.root().walk(n -> {
                String name = null;
                UNode value = null;
                if (n.is(Kind.DECL)) { name = n.name(); value = n.field("value"); }
                else if (n.is(Kind.ASSIGN) && n.field("target") != null) { name = lastName(n.field("target")); value = n.field("value"); }
                else if (n.type().equals("keyword_argument") || n.type().equals("pair")) {
                    UNode k = n.field("name") != null ? n.field("name") : n.field("key");
                    name = k == null ? null : k.text().replaceAll("[\"']", "");
                    value = n.field("value");
                }
                if (name == null || value == null || !value.is(Kind.STRING) || value.flag("interpolated")) return;
                String v = value.attr("value");
                if (!CREDENTIAL_NAME.matcher(name).matches() || v == null || v.length() < 3 || PLACEHOLDERS.contains(v.toLowerCase())) return;
                ctx.report(self, n, f -> f.template(tpl.get(ctx.lang())));
            });
        });

    static final Rule SQL_CONCAT = rule("security.sql-concatenation", "security", "critical")
        .title("SQL built by string concatenation")
        .explain("A SQL statement is assembled by joining strings with variables.")
        .why("If a variable contains quotes or SQL, it changes the query. This is SQL injection, one of the most exploited vulnerability classes.")
        .changes("Use a parameterised query so values are sent separately from the SQL text.")
        .benefits("Input is always treated as data.")
        .tradeoffs("Identifiers (table or column names) can't be parameterised. Allow-list them instead.")
        .check((ctx, self) -> {
            Map<String, String> tpl = Map.of("python", "cur.execute(\"SELECT * FROM users WHERE id = %s\", (user_id,))",
                "java", "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\");\nps.setInt(1, userId);",
                "javascript", "await db.query(\"SELECT * FROM users WHERE id = $1\", [userId]);",
                "go", "row := db.QueryRow(\"SELECT * FROM users WHERE id = ?\", userID)",
                "c", "/* use sqlite3_prepare_v2 + sqlite3_bind_int */", "cpp", "/* use a prepared statement with bound parameters */");
            ctx.root().walk(n -> {
                UNode built = sqlBuilt(n);
                if (built == null) return;
                UNode scope = ctx.scopeOf(n);
                boolean tainted = ctx.dataFlow().isTainted(n, scope);
                boolean param = ctx.dataFlow().usesParameter(n, scope);
                ctx.report(self, n, f -> {
                    f.template(tpl.get(ctx.lang()));
                    if (tainted) {
                        f.source = "dataflow";
                        f.explain = "A SQL statement is assembled from strings and a value that comes from program input.";
                    } else if (param) {
                        f.confidence("medium");
                        f.assumptions = "The inserted value is a function parameter; whether callers pass untrusted data isn't visible here.";
                    } else {
                        f.confidence("medium");
                        f.assumptions = "No input source was traced to this value; it is still unsafe if the value can ever contain user data.";
                    }
                });
            });
        });

    /** The expression if {@code n} builds SQL from a literal SQL prefix plus a non-literal part. */
    private static UNode sqlBuilt(UNode n) {
        if (n.is(Kind.BINARY) && "+".equals(n.op()) && !(n.parent() != null && n.parent().is(Kind.BINARY) && "+".equals(n.parent().op()))) {
            UNode leftmost = n;
            while (leftmost.is(Kind.BINARY) && leftmost.field("left") != null) leftmost = leftmost.field("left");
            boolean sql = leftmost.is(Kind.STRING) && SQL_START.matcher(leftmost.attr("value")).matches();
            boolean dynamic = n.any(x -> x.is(Kind.IDENT) || x.is(Kind.CALL) || x.is(Kind.MEMBER) || x.is(Kind.INDEX));
            return sql && dynamic ? n : null;
        }
        if (n.is(Kind.STRING) && n.flag("interpolated") && SQL_START.matcher(n.attr("value")).matches()) return n;
        if (n.is(Kind.CALL) && Set.of("String.format", "fmt.Sprintf", "sprintf", "snprintf").contains(String.valueOf(n.name()))) {
            UNode fmt = n.list("args").stream().filter(a -> a.is(Kind.STRING)).findFirst().orElse(null);
            if (fmt != null && SQL_START.matcher(fmt.attr("value")).matches() && n.list("args").size() > 1) return n;
        }
        if (n.is(Kind.CALL) && "format".equals(n.attr("method")) && n.field("receiver") != null && n.field("receiver").is(Kind.STRING)
            && SQL_START.matcher(n.field("receiver").attr("value")).matches()) return n;
        return null;
    }

    static final Rule COMMAND_INJECTION = rule("security.command-injection", "security", "high")
        .title("Shell command built from a variable")
        .explain("A command string that includes a variable is handed to a shell.")
        .why("Shell metacharacters in the variable (; | && $()) run additional commands with the program's privileges.")
        .changes("Pass the program and its arguments as a list, without a shell, and validate the arguments.")
        .benefits("Arguments are never interpreted by a shell.")
        .tradeoffs("Shell features such as pipes and globbing must be implemented explicitly.")
        .check((ctx, self) -> {
            Map<String, String> tpl = Map.of("python", "subprocess.run([\"ls\", path], check=True)  # no shell",
                "javascript", "execFile(\"ls\", [path], callback); // no shell", "java", "new ProcessBuilder(\"ls\", path).start();",
                "go", "exec.Command(\"ls\", path) // arguments are not parsed by a shell",
                "c", "/* use execv() with an argument array instead of system() */", "cpp", "/* use posix_spawn/execv with an argument vector */");
            for (UNode c : ctx.all(Kind.CALL)) {
                String n = String.valueOf(c.name());
                List<UNode> a = c.list("args");
                if (a.isEmpty()) continue;
                UNode cmd = null;
                if (Set.of("os.system", "os.popen", "system", "popen", "child_process.exec", "exec", "execSync", "Runtime.getRuntime().exec").contains(n)
                    || n.endsWith(".exec") && ctx.lang("java", "javascript")) cmd = a.get(0);
                else if (n.startsWith("subprocess.") && a.stream().anyMatch(x -> x.text().replaceAll("\\s", "").equals("shell=True"))) cmd = a.get(0);
                else if (n.equals("exec.Command") && a.size() >= 3 && a.get(0).text().matches("\"(ba)?sh\"") && a.get(1).text().equals("\"-c\"")) cmd = a.get(2);
                if (cmd == null || cmd.is(Kind.STRING) && !cmd.flag("interpolated") || cmd.is(Kind.LIST)) continue;
                if (ctx.lang("python") && n.equals("exec")) continue; // handled by dynamic-exec
                UNode command = cmd;
                boolean tainted = ctx.dataFlow().isTainted(command, ctx.scopeOf(c));
                ctx.report(self, c, f -> {
                    f.template(tpl.get(ctx.lang()));
                    if (tainted) { f.source = "dataflow"; f.sev = "critical"; }
                    else f.confidence("medium");
                });
            }
        });

    static final Rule INSECURE_RANDOM = rule("security.insecure-random", "security", "medium")
        .title("Predictable random number used for a secret")
        .explain("A general-purpose random generator produces a value that is stored in a token, password, key or similar.")
        .why("These generators are predictable from a few outputs, so an attacker can guess the secret.")
        .changes("Use the platform's cryptographically secure generator.")
        .benefits("Values cannot be predicted from earlier outputs.")
        .tradeoffs("Slightly slower; irrelevant for generating secrets.")
        .check((ctx, self) -> {
            Map<String, String> tpl = Map.of("python", "import secrets\ntoken = secrets.token_urlsafe(32)",
                "javascript", "const token = crypto.randomUUID(); // or crypto.getRandomValues", "java", "SecureRandom rng = new SecureRandom();",
                "go", "import \"crypto/rand\"\n_, err := rand.Read(buf)", "c", "/* read from getrandom() or /dev/urandom */",
                "cpp", "/* use std::random_device only for seeding; prefer an OS CSPRNG for secrets */");
            Pattern secretName = Pattern.compile("(?i).*(token|password|passwd|secret|key|salt|nonce|otp|session).*");
            for (UNode c : ctx.all(Kind.CALL)) {
                String n = String.valueOf(c.name());
                boolean weak = Set.of("Math.random", "random.random", "random.randint", "random.choice", "rand", "random", "rand.Intn", "rand.Int")
                    .contains(n) || ctx.lang("java") && c.field("receiver") != null && "Random".equals(ctx.typeOf(c.field("receiver").name(), c));
                if (!weak) continue;
                UNode owner = c.ancestor(Kind.DECL, Kind.ASSIGN, Kind.FUNCTION);
                String target = owner == null ? null : owner.is(Kind.DECL) ? owner.name()
                    : owner.is(Kind.ASSIGN) && owner.field("target") != null ? lastName(owner.field("target")) : owner.name();
                if (target == null || !secretName.matcher(target).matches()) continue;
                ctx.report(self, c, f -> f.template(tpl.get(ctx.lang())));
            }
            for (UNode nw : ctx.all(Kind.NEW)) {
                if (!"Random".equals(nw.name())) continue;
                UNode d = nw.ancestor(Kind.DECL);
                if (d != null && d.name() != null && secretName.matcher(d.name()).matches()) ctx.report(self, nw, f -> f.template(tpl.get("java")));
            }
        });

    static final Rule WEAK_HASH = rule("security.weak-hash", "security", "medium")
        .title("Weak cryptographic hash")
        .explain("MD5 or SHA-1 is used.")
        .why("Both have practical collision attacks, and they are far too fast for storing passwords.")
        .changes("Use SHA-256 or better for integrity, and a password hash (bcrypt, scrypt, Argon2) for passwords.")
        .benefits("Collisions and brute force become impractical.")
        .tradeoffs("Interoperating with systems that require MD5 (some checksums) may need it; never use it for security.")
        .check((ctx, self) -> {
            Pattern weak = Pattern.compile("(?i)\"?(md5|sha-?1|md4)\"?");
            for (UNode c : ctx.all(Kind.CALL)) {
                String n = String.valueOf(c.name());
                boolean hit = Set.of("hashlib.md5", "hashlib.sha1", "md5.New", "sha1.New", "md5.Sum", "sha1.Sum", "MD5", "SHA1", "MD5_Init", "SHA1_Init").contains(n);
                if (!hit && (n.endsWith("getInstance") || n.endsWith("createHash") || n.equals("hashlib.new")) && !c.list("args").isEmpty()) {
                    UNode a = c.list("args").get(0);
                    hit = a.is(Kind.STRING) && weak.matcher(a.attr("value")).matches();
                }
                if (hit) ctx.report(self, c, f -> f.note(ctx.commentPrefix() + "Use SHA-256 (or a password hash such as bcrypt/Argon2 for passwords)."));
            }
        });

    static final Rule INNER_HTML = rule("security.inner-html", "security", "medium").langs("javascript")
        .title("Dynamic value written to innerHTML")
        .explain("A value that includes a variable is inserted as HTML.")
        .why("If that value ever contains user input, any markup in it, including scripts and event handlers, becomes live (cross-site scripting).")
        .changes("Use textContent, which inserts text and never parses HTML.")
        .benefits("Removes the XSS vector for this assignment.")
        .tradeoffs("If you need formatting, build elements with createElement or sanitise with a vetted library.")
        .check((ctx, self) -> {
            for (UNode a : ctx.all(Kind.ASSIGN)) {
                UNode t = a.field("target"), v = a.field("value");
                if (t == null || v == null || !t.is(Kind.MEMBER) || !Set.of("innerHTML", "outerHTML").contains(t.name())) continue;
                if (v.is(Kind.STRING) && !v.flag("interpolated")) continue;
                UNode prop = t.children().get(t.children().size() - 1);
                ctx.report(self, a, f -> {
                    ctx.applyEdits(f, List.of(Edit.replace(prop, "textContent")));
                    if (ctx.dataFlow().isTainted(v, ctx.scopeOf(a))) f.source = "dataflow";
                });
            }
        });

    static String lastName(UNode target) {
        if (target.is(Kind.IDENT)) return target.name();
        if (target.is(Kind.MEMBER)) return target.name();
        return null;
    }

    static String declOf(RuleContext ctx, String name, UNode at, String attr) {
        for (UNode d : com.codereviewx.ast.dataflow.DataFlow.defs(name, ctx.scopeOf(at))) if (d.attr(attr) != null) return d.attr(attr);
        for (UNode d : ctx.all(Kind.DECL)) if (name.equals(d.name()) && d.attr(attr) != null) return d.attr(attr);
        return null;
    }
}
