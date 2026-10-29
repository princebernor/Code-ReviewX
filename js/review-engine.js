/* Code ReveiwX — demonstration review engine.
 *
 * IMPORTANT: this is NOT an AI model. It is a small set of rule-based checks that run
 * entirely in the browser:
 *   1. pattern rules (regular expressions) for known risky or unidiomatic constructs, and
 *   2. a loop-nesting analysis (by indentation) that produces a rough Big-O estimate.
 * Every number it returns is derived from those findings so the UI can show its working.
 * It cannot prove code correct, and a clean result does not mean the code is bug-free.
 */
(function () {
  "use strict";

  const CATEGORIES = [
    { id: "correctness", label: "Logic & correctness", w: 0.22 },
    { id: "security", label: "Security", w: 0.15 },
    { id: "time", label: "Time complexity", w: 0.15 },
    { id: "quality", label: "Code quality", w: 0.14 },
    { id: "readability", label: "Readability & maintainability", w: 0.14 },
    { id: "practices", label: "Best-practice compliance", w: 0.12 },
    { id: "space", label: "Space complexity", w: 0.08 }
  ];
  const SEVERITIES = ["critical", "high", "medium", "low", "info"];
  const PENALTY = { critical: 32, high: 20, medium: 11, low: 5, info: 2 };
  const BRACE = ["c", "cpp", "java", "javascript", "go"];

  /* ---------------- Pattern rules ---------------- */
  // fix(line, match) returns a rewritten line, or null when only a template applies.
  const RULES = [
    // --- Security
    { id: "gets", langs: ["c", "cpp"], re: /\bgets\s*\(\s*(\w+)\s*\)/, cat: "security", sev: "critical",
      title: "Unbounded read with gets()",
      explain: "gets() copies input into the buffer until a newline, with no idea how large the buffer is.",
      why: "Any input longer than the buffer overwrites adjacent memory. This is a classic buffer overflow, and gets() was removed from the C11 standard for this reason.",
      fix: (l, m) => l.replace(m[0], "fgets(" + m[1] + ", sizeof " + m[1] + ", stdin)"),
      changes: "Replaced gets() with fgets(), passing the buffer size so the read stops before the end of the buffer.",
      benefits: "Input can no longer write past the buffer.",
      tradeoffs: "fgets() keeps the trailing newline. Strip it with buf[strcspn(buf, \"\\n\")] = '\\0' if needed. Over-long input is truncated, not rejected." },
    { id: "strcpy", langs: ["c", "cpp"], re: /\b(strcpy|strcat|sprintf)\s*\(\s*(\w+)\s*,/, cat: "security", sev: "high", max: 3,
      title: "Unchecked string copy",
      explain: "strcpy, strcat and sprintf write into the destination without checking its size.",
      why: "If the source is longer than the space left in the destination, the write overflows the buffer.",
      fix: (l, m) => {
        if (m[1] === "strcpy") return l.replace(/strcpy\s*\(\s*(\w+)\s*,\s*(.+)\)\s*;/, "snprintf($1, sizeof $1, \"%s\", $2);");
        if (m[1] === "strcat") return l.replace(/strcat\s*\(\s*(\w+)\s*,\s*(.+)\)\s*;/, "strncat($1, $2, sizeof $1 - strlen($1) - 1);");
        return l.replace(/sprintf\s*\(\s*(\w+)\s*,/, "snprintf($1, sizeof $1,");
      },
      changes: "Switched to the size-bounded variant and passed the destination's capacity.",
      benefits: "Writes stop at the end of the destination buffer.",
      tradeoffs: "Output may be silently truncated. Check snprintf's return value if truncation matters. sizeof only works on real arrays, not pointers." },
    { id: "eval", langs: ["javascript", "python"], re: /(^|[^\w.])(eval|exec)\s*\(/, cat: "security", sev: "high",
      title: "Dynamic code execution",
      explain: "eval/exec runs a string as code.",
      why: "If any part of that string comes from input, an attacker can run arbitrary code. It also defeats static analysis and optimisation.",
      fix: null, template: { python: "import ast\nvalue = ast.literal_eval(text)  # parses literals only", javascript: "const value = JSON.parse(text); // data only, never code" },
      changes: "Parse the data with a parser that only accepts literals, instead of executing it.",
      benefits: "Input can't execute code.", tradeoffs: "Only works when the string is meant to be data. Real expression evaluation needs a purpose-built parser." },
    { id: "secret", langs: "*", re: /\b(password|passwd|pwd|secret|api_?key|apikey|token|access_?key)\b\s*[:=]\s*["'][^"']{3,}["']/i, cat: "security", sev: "high",
      title: "Hard-coded credential",
      explain: "A credential-like value is written directly into the source.",
      why: "Anything in source control is visible to everyone with repository access and is very hard to fully remove from history.",
      fix: null, template: { python: "import os\nAPI_KEY = os.environ[\"API_KEY\"]", javascript: "const apiKey = process.env.API_KEY;", java: "String apiKey = System.getenv(\"API_KEY\");", go: "apiKey := os.Getenv(\"API_KEY\")", c: "const char *api_key = getenv(\"API_KEY\");", cpp: "const char* apiKey = std::getenv(\"API_KEY\");" },
      changes: "Read the value from the environment, or a secrets manager, at runtime.",
      benefits: "The secret stays out of the codebase and can be rotated without a code change.", tradeoffs: "Deployments must now provide the variable. Add a clear error when it's missing." },
    { id: "sqlconcat", langs: "*", re: /["'`]\s*(SELECT|INSERT|UPDATE|DELETE)\b[^"'`]*["'`]\s*\+|f["'](SELECT|INSERT|UPDATE|DELETE)\b.*\{/i, cat: "security", sev: "critical",
      title: "SQL built by string concatenation",
      explain: "A SQL statement is assembled by joining strings with variables.",
      why: "If a variable contains quotes or SQL, it changes the query. This is SQL injection, one of the most exploited vulnerability classes.",
      fix: null, template: { python: "cur.execute(\"SELECT * FROM users WHERE id = %s\", (user_id,))", java: "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\");\nps.setInt(1, userId);", javascript: "await db.query(\"SELECT * FROM users WHERE id = $1\", [userId]);", go: "row := db.QueryRow(\"SELECT * FROM users WHERE id = ?\", userID)", c: "/* use sqlite3_prepare_v2 + sqlite3_bind_int */", cpp: "/* use a prepared statement with bound parameters */" },
      changes: "Use a parameterised query so values are sent separately from the SQL text.",
      benefits: "Input is always treated as data.", tradeoffs: "Identifiers (table or column names) can't be parameterised. Allow-list them instead." },
    { id: "innerhtml", langs: ["javascript"], re: /\.innerHTML\s*\+?=\s*(?!["'`][^"'`+]*["'`]\s*;?\s*$)/, cat: "security", sev: "medium",
      title: "Dynamic value written to innerHTML",
      explain: "A value that includes a variable is inserted as HTML.",
      why: "If that value ever contains user input, any markup in it, including scripts and event handlers, becomes live (cross-site scripting).",
      fix: (l) => l.replace(".innerHTML", ".textContent"),
      changes: "Use textContent, which inserts text and never parses HTML.",
      benefits: "Removes the XSS vector for this assignment.", tradeoffs: "If you need formatting, build elements with createElement or sanitise with a vetted library." },

    // --- Correctness
    { id: "mutable-default", langs: ["python"], re: /def\s+\w+\s*\([^)]*=\s*(\[\]|\{\}|set\(\))/, cat: "correctness", sev: "high",
      title: "Mutable default argument",
      explain: "The default list/dict is created once, when the function is defined, not on every call.",
      why: "Values appended in one call are still there in the next call, so results leak between calls. This bug is easy to miss in tests that call the function once.",
      fix: (l, m) => l.replace(m[1], "None"),
      extra: "Then, as the first line of the body: if result is None: result = []",
      changes: "Default to None and create a fresh object inside the function.",
      benefits: "Every call starts with its own empty container.", tradeoffs: "One extra line. Callers who relied on the shared state (rare and usually accidental) will see different behaviour." },
    { id: "bare-except", langs: ["python"], re: /^\s*except\s*:/, cat: "correctness", sev: "medium",
      title: "Bare except clause",
      explain: "except: with no exception type catches everything, including KeyboardInterrupt and SystemExit.",
      why: "Real bugs (typos, wrong types) get swallowed, so the program carries on with bad state and the root cause is hidden.",
      fix: (l) => l.replace(/except\s*:/, "except ValueError:  # catch only what you expect"),
      changes: "Catch the specific exception the block is meant to handle.",
      benefits: "Unexpected errors surface instead of being hidden.", tradeoffs: "You must know which exception to expect. Check the docs of the call inside try." },
    { id: "eq-none", langs: ["python"], re: /([!=])=\s*None\b/, cat: "practices", sev: "low",
      title: "Comparison to None with ==",
      explain: "None is a singleton and should be tested by identity.",
      why: "== can be overridden by a class's __eq__, so it may return surprising results. PEP 8 recommends is / is not.",
      fix: (l, m) => l.replace(/([!=])=\s*None\b/, m[1] === "!" ? "is not None" : "is None"),
      changes: "Use is None / is not None.", benefits: "Always correct, slightly faster, idiomatic.", tradeoffs: "None." },
    { id: "loose-eq", langs: ["javascript"], re: /[^=!<>]([!=])=(?!=)\s*[^=]/, cat: "correctness", sev: "medium", max: 2,
      title: "Loose equality (== / !=)",
      explain: "== converts types before comparing, so '1' == 1 and 0 == '' are both true.",
      why: "Implicit coercion causes comparisons that pass for the wrong reasons, especially with user input, which is always a string.",
      fix: (l) => l.replace(/([^=!<>])==(?!=)/g, "$1===").replace(/!=(?!=)/g, "!=="),
      changes: "Use strict equality, which compares type and value.", benefits: "Comparisons mean exactly what they say.", tradeoffs: "If you relied on coercion, convert explicitly (Number(x) === 1)." },
    { id: "java-str-eq", langs: ["java"], re: /(\w+)\s*==\s*"([^"]*)"|"([^"]*)"\s*==\s*(\w+)/, cat: "correctness", sev: "high",
      title: "String compared with ==",
      explain: "In Java, == on objects compares references, not contents.",
      why: "It may pass in a quick test (string literals are interned) and then fail for strings built at runtime, such as user input.",
      fix: (l, m) => m[1] ? l.replace(m[0], "\"" + m[2] + "\".equals(" + m[1] + ")") : l.replace(m[0], "\"" + m[3] + "\".equals(" + m[4] + ")"),
      changes: "Use \"literal\".equals(var), which also avoids a NullPointerException when var is null.",
      benefits: "Compares contents, which is what you meant.", tradeoffs: "For case-insensitive checks use equalsIgnoreCase." },
    { id: "assign-in-if", langs: ["c", "cpp", "java", "javascript"], re: /\bif\s*\(\s*[\w.\[\]]+\s*=\s*(?!=)[^=]/, cat: "correctness", sev: "high",
      title: "Assignment inside an if condition",
      explain: "A single = assigns. The condition is then the assigned value, not a comparison.",
      why: "This is almost always a typo for ==, and the branch runs whenever the assigned value is non-zero.",
      fix: (l) => l.replace(/(\bif\s*\(\s*[\w.\[\]]+\s*)=(?!=)/, "$1=="), changes: "Compare with == (or === in JavaScript).", benefits: "The branch tests what it reads as.", tradeoffs: "If the assignment was intentional, move it to its own line for clarity." },
    { id: "mid-overflow", langs: ["c", "cpp", "java", "go"], re: /\(\s*(\w+)\s*\+\s*(\w+)\s*\)\s*\/\s*2/, cat: "correctness", sev: "medium",
      title: "Midpoint can overflow",
      explain: "(low + high) / 2 adds two indices before dividing.",
      why: "For large arrays the sum can exceed the integer range and become negative, giving an invalid index. This bug sat in the JDK's binary search for nine years.",
      fix: (l, m) => l.replace(m[0], m[1] + " + (" + m[2] + " - " + m[1] + ") / 2"),
      changes: "Compute low + (high - low) / 2, which never exceeds high.", benefits: "Safe for any valid index range.", tradeoffs: "None. It's the same number of operations." },
    { id: "upper-bound", langs: ["c", "cpp", "java", "javascript", "go"], re: /\b(high|hi|right|end)\s*=\s*([\w.]+?)(\.length|\.size\(\)|\.length\(\))\s*[;,]/, cat: "correctness", sev: "high", needs: /(<=)/,
      title: "Inclusive search bound starts at length",
      explain: "The upper bound is set to the array length, but the loop uses <=, which treats it as inclusive.",
      why: "When the target is larger than every element, the search reads index length, which is past the end (ArrayIndexOutOfBounds, or undefined behaviour in C/C++).",
      fix: (l, m) => l.replace(m[0], m[1] + " = " + m[2] + m[3] + " - 1" + m[0].slice(-1)),
      changes: "Start the inclusive bound at length - 1.", benefits: "Every probed index is valid.", tradeoffs: "Alternatively keep length and switch to a half-open loop (low < high). Pick one convention and keep it." },
    { id: "le-length", langs: ["c", "cpp", "java", "javascript"], re: /for\s*\([^;]*;\s*\w+\s*<=\s*[\w.]+(\.length|\.size\(\)|\.length\(\))\s*;/, cat: "correctness", sev: "high",
      title: "Loop runs one index past the end",
      explain: "i <= length visits index length, which doesn't exist.",
      why: "Out-of-bounds read: an exception in Java/JS, undefined behaviour in C/C++.",
      fix: (l) => l.replace(/<=\s*([\w.]+(\.length|\.size\(\)|\.length\(\)))/, "< $1"), changes: "Use < instead of <=.", benefits: "Stays within bounds.", tradeoffs: "None." },
    { id: "go-ignored-err", langs: ["go"], re: /,\s*_\s*:?=\s*[\w.]+\(/, cat: "correctness", sev: "medium",
      title: "Error value discarded",
      explain: "The function's error return is assigned to _ and ignored.",
      why: "On failure (for example strconv.Atoi(\"x\")) the other result is a zero value, and the program continues as if it succeeded.",
      fix: (l) => l.replace(/,\s*_\s*(:?=)/, ", err $1") + "\n        if err != nil {\n            return 0, fmt.Errorf(\"parse %q: %w\", v, err)\n        }",
      changes: "Capture err and handle it, by returning it, logging it, or skipping the bad value deliberately.", benefits: "Failures become visible and testable.", tradeoffs: "The function signature may need an extra error return value." },
    { id: "signed-size", langs: ["cpp"], re: /for\s*\(\s*int\s+\w+\s*=\s*\w+\s*;\s*\w+\s*<\s*\w+\.size\(\)/, cat: "correctness", sev: "low", max: 1,
      title: "Signed/unsigned comparison",
      explain: "int is compared with size(), which returns an unsigned size_t.",
      why: "Mixed comparisons convert the int to unsigned. A negative value becomes huge, and compilers warn (-Wsign-compare).",
      fix: (l) => l.replace(/for\s*\(\s*int\s+/, "for (size_t "), changes: "Use size_t (or a range-based for loop).", benefits: "No implicit conversion and no warning.", tradeoffs: "size_t can't go negative. Count-down loops need care." },

    // --- Time (hidden linear work inside a loop)
    { id: "strlen-loop", langs: ["c", "cpp"], re: /for\s*\([^;]*;[^;]*\bstrlen\s*\(\s*(\w+)\s*\)/, cat: "time", sev: "medium",
      title: "strlen() in the loop condition",
      explain: "The condition is re-evaluated on every iteration, and strlen() walks the whole string each time.",
      why: "A loop that looks linear becomes O(n²) for long strings.",
      fix: (l, m) => "size_t len = strlen(" + m[1] + ");\n" + l.replace(/strlen\s*\(\s*\w+\s*\)/, "len"),
      changes: "Compute the length once before the loop.", benefits: "Restores O(n).", tradeoffs: "If the loop changes the string's length, recompute deliberately.", bump: 1 },
    { id: "linear-in-loop", langs: ["javascript", "java", "python"], re: /\.(indexOf|includes|lastIndexOf)\s*\(|\.pop\(\s*0\s*\)|\.insert\(\s*0\s*,|\.shift\(\)|\.unshift\(/, cat: "time", sev: "medium", inLoop: true, max: 2,
      title: "Linear operation inside a loop",
      explain: "This call scans or shifts the whole collection, and it runs once per loop iteration.",
      why: "It multiplies the loop's cost by n, so a single loop costs O(n²) in total.",
      fix: null, template: { javascript: "const seen = new Set();\n// ...\nif (!seen.has(x)) { seen.add(x); }", python: "from collections import deque\nq = deque(items)\nq.popleft()   # O(1) instead of list.pop(0)", java: "Set<Integer> seen = new HashSet<>();\n// seen.contains(x) is O(1) on average" },
      changes: "Use a structure with O(1) lookup or removal, such as a hash set or a deque.", benefits: "Removes the hidden factor of n.", tradeoffs: "Uses O(n) extra memory, and hash sets don't preserve order (use LinkedHashSet / insertion-ordered Set if needed).", bump: 1 },
    { id: "string-concat-loop", langs: ["java"], re: /\b\w+\s*\+=\s*("|\w+\s*\+\s*")/, cat: "time", sev: "low", inLoop: true, max: 1,
      title: "String concatenation in a loop",
      explain: "Java strings are immutable, so each += copies the whole string built so far.",
      why: "Building a string of n pieces this way costs O(n²) character copies.",
      fix: null, template: { java: "StringBuilder sb = new StringBuilder();\nfor (String part : parts) sb.append(part);\nString result = sb.toString();" },
      changes: "Append to a StringBuilder and convert once at the end.", benefits: "Linear time.", tradeoffs: "Slightly more verbose. The compiler already optimises single-expression concatenations." },

    // --- Space
    { id: "vector-by-value", langs: ["cpp"], re: /\(\s*(?!const)(vector|string|map|unordered_map|set)<?[^&()]*?>?\s+(\w+)\s*[,)]/, cat: "space", sev: "medium",
      title: "Container passed by value",
      explain: "The parameter is a full container, so every call copies all of its elements.",
      why: "That's O(n) extra time and memory per call, which adds up quickly inside recursion or loops.",
      fix: (l, m) => l.replace(new RegExp("\\(\\s*" + m[1]), "(const " + m[1]).replace(new RegExp("\\s+" + m[2] + "\\s*([,)])"), "& " + m[2] + "$1"),
      changes: "Take the container by const reference.", benefits: "No copy. The function reads the caller's data directly.", tradeoffs: "If the function needs a private copy to modify, keep pass-by-value. That's a deliberate choice." },

    // --- Readability
    { id: "long-line", langs: "*", re: /^.{121,}$/, cat: "readability", sev: "low", max: 2,
      title: "Very long line", explain: "This line is over 120 characters.",
      why: "Long lines force horizontal scrolling in reviews and side-by-side diffs and usually hide several steps in one expression.",
      fix: null, generic: "Split the expression into named intermediate variables.", changes: "Break the line at logical boundaries.", benefits: "Easier to read and to diff.", tradeoffs: "None." },

    // --- Best practices
    { id: "js-var", langs: ["javascript"], re: /^\s*(for\s*\(\s*)?var\s+/, cat: "practices", sev: "low", max: 2,
      title: "var declaration", explain: "var is function-scoped and hoisted.",
      why: "Variables leak out of blocks, and closures in loops capture the same variable, a common source of subtle bugs.",
      fix: (l) => l.replace(/\bvar\s+/, "let "), changes: "Use let (or const when the value never changes).", benefits: "Block scoping matches how the code reads.", tradeoffs: "None in modern runtimes." },
    { id: "bits", langs: ["cpp"], re: /#include\s*<bits\/stdc\+\+\.h>/, cat: "practices", sev: "low",
      title: "Non-standard umbrella header", explain: "<bits/stdc++.h> is a GCC internal header that includes the entire standard library.",
      why: "It's fine for timed contests, but it doesn't compile on MSVC or Clang/libc++ and slows builds noticeably.",
      fix: () => "#include <vector>\n#include <iostream>", changes: "Include only the headers you use.", benefits: "Portable and faster to compile.", tradeoffs: "You need to know which header declares what." },
    { id: "using-ns", langs: ["cpp"], re: /^\s*using\s+namespace\s+std\s*;/, cat: "practices", sev: "low",
      title: "using namespace std at file scope", explain: "Every std name becomes visible unqualified.",
      why: "Names like count, distance or data can collide with your own identifiers and cause confusing overload errors.",
      fix: () => "using std::vector;  // import only what you need", changes: "Qualify names (std::vector) or import specific ones.", benefits: "No accidental name collisions.", tradeoffs: "Slightly more typing. Many teams accept it in .cpp files but never in headers." },
    { id: "catch-generic", langs: ["java"], re: /catch\s*\(\s*(Exception|Throwable)\s+\w+\s*\)/, cat: "practices", sev: "low",
      title: "Catching a generic exception", explain: "Catching Exception or Throwable handles every failure the same way.",
      why: "Programming errors (NullPointerException) get handled as if they were expected conditions.",
      fix: (l) => l.replace(/\(\s*(Exception|Throwable)/, "(NumberFormatException"), changes: "Catch the specific checked or expected exception.", benefits: "Unexpected failures propagate and get noticed.", tradeoffs: "You may need several catch blocks or a multi-catch." },

    // --- Code quality
    { id: "debug-print", langs: "*", re: /\bconsole\.log\s*\(|System\.out\.println\s*\(|^\s*print\s*\(|\bfmt\.Println\s*\(|\bcout\s*<</, cat: "quality", sev: "info", max: 1, skipMain: true,
      title: "Console output in logic code", explain: "This function writes directly to the console.",
      why: "Output mixed into logic makes the function harder to test and to reuse. Return values and let the caller decide what to print.",
      fix: null, generic: "Return the value and print it from the caller (or use a logger with levels).", changes: "Move output to the caller or a logger.", benefits: "Testable, reusable function.", tradeoffs: "For quick contest or DSA practice this is often fine." },
    { id: "todo", langs: "*", re: /\b(TODO|FIXME|HACK|XXX)\b/, cat: "quality", sev: "info", comments: true, max: 2,
      title: "Unresolved TODO", explain: "A TODO/FIXME marker is left in the code.",
      why: "TODOs signal known gaps. Here, an edge case is acknowledged but not handled.",
      fix: null, generic: "Resolve it now, or track it in an issue and reference the ID.", changes: "Handle the case or create a tracked issue.", benefits: "Known gaps don't get forgotten.", tradeoffs: "None." },
    { id: "empty-catch", langs: ["java", "javascript", "cpp"], re: /catch\s*\([^)]*\)\s*\{\s*\}/, cat: "quality", sev: "medium",
      title: "Empty catch block", explain: "The error is caught and discarded.", why: "Failures disappear without a trace, which makes bugs extremely hard to diagnose.",
      fix: null, generic: "At minimum log the error, or rethrow it wrapped with context.", changes: "Handle, log or rethrow.", benefits: "Failures become observable.", tradeoffs: "None." },
    { id: "py-pass-except", langs: ["python"], re: /^\s*except[^:]*:\s*pass\s*$/, cat: "quality", sev: "medium",
      title: "Exception silently ignored", explain: "except ...: pass discards the error.", why: "Failures disappear without a trace.",
      fix: null, generic: "Log the error or handle the specific case explicitly.", changes: "Handle or log.", benefits: "Failures become observable.", tradeoffs: "None." }
  ];

  /* ---------------- Language guess (for a mismatch warning only) ---------------- */
  function guessLang(code) {
    if (/^\s*package\s+\w+/m.test(code) && /\bfunc\s+\w*\s*\(/.test(code)) return "go";
    if (/\bpublic\s+(static\s+)?(class|void|int|boolean|String)\b|System\.out\./.test(code)) return "java";
    if (/#include\s*</.test(code)) return /std::|cout|vector<|using namespace|bits\/stdc/.test(code) ? "cpp" : "c";
    if (/^\s*def\s+\w+\s*\(.*\)\s*:|^\s*import\s+\w+\s*$|^\s*from\s+\w+\s+import/m.test(code)) return "python";
    if (/\bfunction\b|=>|\bconst\s+\w+\s*=|\blet\s+\w+\s*=|console\.log/.test(code)) return "javascript";
    return null;
  }

  /* ---------------- Structural analysis ---------------- */
  function indentOf(line) { const m = line.match(/^[\t ]*/)[0]; return m.replace(/\t/g, "    ").length; }
  function isComment(t) { return /^(\/\/|#(?!include)|\/\*|\*)/.test(t); }
  const LOOP_RE = {
    python: /^\s*(for|while)\b/, go: /^\s*for\b/,
    c: /^\s*(for|while|do)\b/, cpp: /^\s*(for|while|do)\b/, java: /^\s*(for|while|do)\b/, javascript: /^\s*(for|while|do)\b|\.(forEach|map|filter|reduce)\s*\(/
  };
  const ALLOC_RE = {
    python: /(=\s*(\[|\{|set\(|dict\(|list\(|deque\())|\[[^\]]+\bfor\b/,
    javascript: /=\s*(\[|\{|new\s+(Map|Set|Array)\b)|\.(map|filter|slice|concat)\(/,
    java: /new\s+(\w+(<[^>]*>)?\s*\[|ArrayList|HashMap|HashSet|LinkedList|ArrayDeque|StringBuilder|TreeMap)/,
    cpp: /\b(vector|unordered_map|unordered_set|map|set|deque|string|queue|stack)\s*<[^;]*>\s+\w+\s*(\(|;|=|\{)|\bnew\s+\w+\[/,
    c: /\b(malloc|calloc|realloc)\s*\(|\w+\s+\w+\s*\[\s*[a-zA-Z_]\w*\s*\]/,
    go: /\bmake\s*\(|\[\]\w+\{|map\[\w+\]\w+\{/
  };
  const ALLOC2D = /\[\s*\[|vector\s*<\s*vector|new\s+\w+\[[^\]]+\]\[|make\(\s*\[\]\[\]|\[\[0\]|\[\s*\[\s*0/;
  const SORT_RE = /\b(sorted|qsort)\s*\(|\.sort\s*\(|\bsort\s*\(|Arrays\.sort|Collections\.sort|sort\.(Ints|Slice|Strings)/;

  function structure(lines, lang) {
    const loopRe = LOOP_RE[lang] || LOOP_RE.c;
    const stack = []; let maxDepth = 0, deepest = null; const loopLines = [];
    let maxLin = 0, maxLog = 0; const inLoop = new Set();
    lines.forEach((raw, i) => {
      const t = raw.trim(); if (!t || isComment(t)) return;
      const ind = indentOf(raw);
      while (stack.length && ind <= stack[stack.length - 1].indent && !(t === "{" )) stack.pop();
      if (stack.length) inLoop.add(i);
      if (loopRe.test(raw)) {
        // logarithmic loop: halving / binary-search body within the next lines
        const body = lines.slice(i, i + 8).join("\n");
        const isLog = /^\s*(while|for)\b/.test(raw) && /\bmid\b|\/\s*2\b|>>=\s*1|\*=\s*2|\/\/=\s*2|\/=\s*2/.test(body);
        stack.push({ indent: ind, line: i, log: isLog });
        loopLines.push(i);
        const lin = stack.filter((s) => !s.log).length, lg = stack.length - lin;
        if (stack.length > maxDepth || (stack.length === maxDepth && lin > maxLin)) { maxDepth = stack.length; deepest = stack.map((s) => s.line); maxLin = lin; maxLog = lg; }
      }
    });
    return { maxDepth, deepest, maxLin, maxLog, inLoop, loopLines };
  }

  function recursionInfo(code, lang) {
    const defRe = { python: /def\s+(\w+)\s*\(/g, go: /func\s+(?:\([^)]*\)\s*)?(\w+)\s*\(/g, javascript: /function\s+(\w+)\s*\(|(?:const|let)\s+(\w+)\s*=\s*\([^)]*\)\s*=>/g }[lang] || /\b[\w<>\[\]]+\s+(\w+)\s*\([^;{)]*\)\s*\{/g;
    const names = []; let m;
    while ((m = defRe.exec(code))) { const n = m[1] || m[2]; if (n && !/^(if|for|while|switch|catch|main|return)$/.test(n)) names.push({ n, at: m.index }); }
    let branching = null, linear = null;
    names.forEach((d, k) => {
      const end = k + 1 < names.length ? names[k + 1].at : code.length;
      const body = code.slice(d.at, end).replace(/^[^\n]*\n/, "");
      const calls = (body.match(new RegExp("\\b" + d.n + "\\s*\\(", "g")) || []).length;
      if (calls >= 2 && !/memo|cache|lru_cache|dp\[|dp =|dp:=|seen/.test(body)) branching = d.n;
      else if (calls >= 1) linear = linear || d.n;
    });
    return { branching, linear };
  }

  function bigO(lin, lg) {
    const parts = [];
    if (lin === 1) parts.push("n"); else if (lin > 1) parts.push("n" + ["", "", "²", "³", "⁴"][Math.min(lin, 4)]);
    if (lg === 1) parts.push("log n"); else if (lg > 1) parts.push("log" + ["", "", "²", "³"][Math.min(lg, 3)] + " n");
    return "O(" + (parts.join(" ") || "1") + ")";
  }
  const TIME_RANK = (lin, lg, exp) => (exp ? 6 : lin >= 3 ? 5 : lin === 2 ? 4 : lin === 1 && lg ? 3 : lin === 1 ? 2 : lg ? 1 : 0);
  const TIME_BASE = [100, 99, 95, 90, 68, 46, 32];   // category score by time class
  const EFF_BASE = [97, 95, 88, 82, 58, 38, 20];    // efficiency estimate by time class

  /* ---------------- Main entry ---------------- */
  function analyze(code, lang) {
    const src = code.replace(/\r\n?/g, "\n");
    const lines = src.split("\n");
    const nonblank = lines.filter((l) => l.trim()).length;
    if (!src.trim()) return { error: "empty", message: "Paste or upload some code first." };
    if (nonblank < 3) return { error: "short", message: "Add at least 3 lines of code so there's something to review." };
    if (lines.length > 2000) return { error: "long", message: "This demo reviews up to 2,000 lines at a time. Split the file and review it in parts." };
    if (/[\u0000-\u0008\u000E-\u001F]/.test(src)) return { error: "binary", message: "This looks like a binary file, not source code." };

    const st = structure(lines, lang);
    const rec = recursionInfo(src, lang);
    const sample = SAMPLES.find((s) => s.lang === lang && s.code.trim() === src.trim()) || null;
    const findings = []; const counts = {};

    // 1) pattern rules
    RULES.forEach((r) => {
      if (r.langs !== "*" && r.langs.indexOf(lang) === -1) return;
      if (r.needs && !r.needs.test(src)) return;
      let hits = 0;
      lines.forEach((raw, i) => {
        if ((r.max || 99) <= hits) return;
        const t = raw.trim(); if (!t) return;
        if (!r.comments && isComment(t)) return;
        if (r.inLoop && !st.inLoop.has(i)) return;
        if (r.skipMain && /\bmain\b/.test(enclosingFn(lines, i))) return;
        const m = raw.match(r.re); if (!m) return;
        hits++;
        let suggested = null, kind = "line";
        if (r.fix) { suggested = r.fix(raw, m); if (r.extra) suggested += "\n" + " ".repeat(indentOf(raw) + 4) + "# " + r.extra; }
        else if (r.template && r.template[lang]) { suggested = r.template[lang]; kind = "template"; }
        else { suggested = (lang === "python" ? "# " : "// ") + (r.generic || r.changes); kind = "note"; }
        findings.push(mk(r, i + 1, i + 1, raw, suggested, kind));
      });
    });

    // 2) complexity
    let lin = st.maxLin, lg = st.maxLog;
    const bumps = findings.filter((f) => f.bump);
    bumps.forEach((f) => { const d = depthAt(st, f.line - 1, lines); lin = Math.max(lin, d + 1); });
    if (rec.linear && lin === 0) lin = 1;
    const hasSort = SORT_RE.test(src);
    if (hasSort && lin <= 1 && lg === 0) { lin = Math.max(lin, 1); lg = 1; }
    const exp = !!rec.branching;
    const timeRank = TIME_RANK(lin, lg, exp);
    const timeO = exp ? "O(2ⁿ)" : bigO(lin, lg);

    if (st.maxLin >= 2) {
      const from = st.deepest[0], innerLoop = st.deepest[st.deepest.length - 1];
      let to = innerLoop; const outerInd = indentOf(lines[from]);
      for (let j = innerLoop + 1; j < lines.length && j < from + 14; j++) { if (lines[j].trim() && indentOf(lines[j]) <= outerInd) { to = BRACE.indexOf(lang) > -1 && lines[j].trim() === "}" ? j : j - 1; break; } to = j; }
      const fx = sample && sample.fix;
      findings.push({
        id: "nested-loop", cat: "time", sev: st.maxLin >= 3 ? "critical" : "high", noPenalty: true,
        title: "Nested loops: estimated " + bigO(st.maxLin, 0) + " time",
        line: from + 1, end: to + 1, original: lines.slice(from, to + 1).join("\n"),
        explain: "The loop on line " + (innerLoop + 1) + " runs fully for every iteration of the loop on line " + (from + 1) + ".",
        why: "Doubling the input roughly " + (st.maxLin >= 3 ? "multiplies the work by eight" : "quadruples the work") + ". At n = 100,000, that's about " + (st.maxLin >= 3 ? "10¹⁵" : "10¹⁰") + " steps, well past typical time limits of about 10⁸ simple operations per second.",
        suggested: fx ? fx.code : GENERIC_FIX[lang], kind: fx ? "rewrite" : "template",
        changes: fx ? fx.changes : "If the inner loop is searching for a matching value, remember what you've seen in a hash set or map and look it up in O(1) instead.",
        benefits: fx ? fx.benefits : "One pass over the data instead of n passes.",
        before: { time: bigO(st.maxLin, 0), space: "O(1)" }, after: fx ? fx.after : { time: "O(n)", space: "O(n)" },
        tradeoffs: fx ? fx.tradeoffs : "This template only applies when the inner loop is a lookup. Sorting + two pointers (O(n log n), O(1) extra space) is the alternative when memory matters. Some problems are genuinely O(n²).",
        assumptions: fx ? fx.assumptions : "Assumes the inner loop only looks for a value related to the outer element."
      });
    }
    if (exp) {
      findings.push({ id: "branching-rec", cat: "time", sev: "high", noPenalty: true, title: "Branching recursion without memoisation", line: 1, end: 1,
        original: "// " + rec.branching + "(...) calls itself more than once per call",
        explain: rec.branching + "() makes two or more recursive calls, and nothing caches results.",
        why: "The same sub-problems are recomputed many times, which can grow exponentially. Naive Fibonacci makes about 1.6ⁿ calls.",
        suggested: MEMO[lang], kind: "template", changes: "Cache each sub-result the first time it's computed (memoisation), or build bottom-up (tabulation).",
        benefits: "Each distinct sub-problem is solved once.", before: { time: "O(2ⁿ) (upper bound)", space: "O(n) stack" }, after: { time: "O(n · states)", space: "O(states)" },
        tradeoffs: "Uses memory for the cache. Only helps when sub-problems actually repeat.", assumptions: "Assumes the function is pure, so the same arguments give the same result." });
    }

    // 3) space
    const allocRe = ALLOC_RE[lang] || ALLOC_RE.c;
    let spaceRank = 0;
    if (lines.some((l) => !isComment(l.trim()) && allocRe.test(l))) spaceRank = 1;
    if (ALLOC2D.test(src)) spaceRank = 2;
    if (rec.linear || rec.branching) spaceRank = Math.max(spaceRank, 1);
    const spaceO = ["O(1)", "O(n)", "O(n²)"][spaceRank];

    // 4) scores — every number below comes from the findings above
    const cats = {};
    CATEGORIES.forEach((c) => { cats[c.id] = { score: 100, n: 0 }; });
    cats.time.score = TIME_BASE[timeRank];
    cats.space.score = [100, 92, 76][spaceRank];
    findings.forEach((f) => { cats[f.cat].n++; if (!f.noPenalty) cats[f.cat].score -= PENALTY[f.sev]; });
    CATEGORIES.forEach((c) => { cats[c.id].score = Math.max(10, Math.min(100, Math.round(cats[c.id].score))); });
    SEVERITIES.forEach((s) => (counts[s] = 0));
    findings.forEach((f) => counts[f.sev]++);
    // Weighted category average, minus a severity surcharge so a single critical
    // issue can't hide behind six clean categories.
    const weighted = CATEGORIES.reduce((s, c) => s + cats[c.id].score * c.w, 0);
    const overall = Math.max(10, Math.round(weighted - 8 * counts.critical - 3 * counts.high));
    const effPenalty = findings.filter((f) => f.cat === "time" && !f.noPenalty).length * 3 + (spaceRank === 2 ? 4 : 0);
    const efficiency = Math.max(5, Math.min(99, EFF_BASE[timeRank] - effPenalty));

    findings.sort((a, b) => SEVERITIES.indexOf(a.sev) - SEVERITIES.indexOf(b.sev) || a.line - b.line);
    findings.forEach((f, i) => (f.uid = "f" + i));

    return {
      engine: "demo-rules-v1", lang, lines: lines.length, nonblank, overall, weighted: Math.round(weighted), efficiency, time: timeO, space: spaceO,
      categories: cats, findings, counts, sampleId: sample ? sample.id : null, guessed: guessLang(src),
      notes: { sort: hasSort, recursion: rec.linear || rec.branching || null, loopDepth: st.maxDepth }
    };
  }

  function depthAt(st, idx, lines) {
    // number of loops enclosing line idx (including a loop header on that line)
    let d = 0; const ind = indentOf(lines[idx]);
    st.loopLines.forEach((l) => { if (l <= idx && indentOf(lines[l]) < ind) { // still open?
      let open = true; for (let j = l + 1; j < idx; j++) { if (lines[j].trim() && indentOf(lines[j]) <= indentOf(lines[l]) && lines[j].trim() !== "}") { open = false; break; } }
      if (open) d++; } else if (l === idx) d++; });
    return d;
  }
  function enclosingFn(lines, i) {
    for (let j = i; j >= 0; j--) if (/\b(def|func|function|void|int|static|public)\b.*\(/.test(lines[j]) && indentOf(lines[j]) < indentOf(lines[i]) + 1) return lines[j];
    return "";
  }
  function mk(r, line, end, original, suggested, kind) {
    return { id: r.id, cat: r.cat, sev: r.sev, title: r.title, line, end, original, suggested, kind, explain: r.explain, why: r.why,
      changes: r.changes, benefits: r.benefits, tradeoffs: r.tradeoffs, bump: r.bump || 0,
      before: r.cat === "time" ? { time: "O(n²) in this loop", space: "O(1)" } : null,
      after: r.cat === "time" ? { time: "O(n)", space: r.id === "linear-in-loop" ? "O(n)" : "O(1)" } : null };
  }

  const GENERIC_FIX = {
    python: "# Template: replace the inner scan with a hash lookup\nseen = {}\nfor i, x in enumerate(items):\n    match = target - x          # what the inner loop was searching for\n    if match in seen:\n        handle(seen[match], i)\n    seen[x] = i",
    javascript: "// Template: replace the inner scan with a hash lookup\nconst seen = new Map();\nitems.forEach((x, i) => {\n  const match = target - x;       // what the inner loop was searching for\n  if (seen.has(match)) handle(seen.get(match), i);\n  seen.set(x, i);\n});",
    java: "// Template: replace the inner scan with a hash lookup\nMap<Integer, Integer> seen = new HashMap<>();\nfor (int i = 0; i < items.length; i++) {\n    int match = target - items[i];   // what the inner loop was searching for\n    if (seen.containsKey(match)) handle(seen.get(match), i);\n    seen.put(items[i], i);\n}",
    cpp: "// Template: replace the inner scan with a hash lookup\nstd::unordered_map<int, int> seen;\nfor (int i = 0; i < (int)items.size(); ++i) {\n    int match = target - items[i];   // what the inner loop was searching for\n    if (auto it = seen.find(match); it != seen.end()) handle(it->second, i);\n    seen[items[i]] = i;\n}",
    c: "/* Template: sort a copy, then walk two pointers inward (O(n log n), O(1) extra) */\nqsort(a, n, sizeof a[0], cmp_int);\nint lo = 0, hi = n - 1;\nwhile (lo < hi) {\n    int s = a[lo] + a[hi];\n    if (s == target) { handle(lo, hi); lo++; hi--; }\n    else if (s < target) lo++;\n    else hi--;\n}",
    go: "// Template: replace the inner scan with a hash lookup\nseen := make(map[int]int)\nfor i, x := range items {\n    match := target - x // what the inner loop was searching for\n    if j, ok := seen[match]; ok {\n        handle(j, i)\n    }\n    seen[x] = i\n}"
  };
  const MEMO = {
    python: "from functools import lru_cache\n\n@lru_cache(maxsize=None)\ndef solve(n):\n    if n < 2:\n        return n\n    return solve(n - 1) + solve(n - 2)",
    javascript: "const memo = new Map();\nfunction solve(n) {\n  if (n < 2) return n;\n  if (memo.has(n)) return memo.get(n);\n  const v = solve(n - 1) + solve(n - 2);\n  memo.set(n, v);\n  return v;\n}",
    java: "long[] memo = new long[n + 1];\nArrays.fill(memo, -1);\nlong solve(int n) {\n    if (n < 2) return n;\n    if (memo[n] != -1) return memo[n];\n    return memo[n] = solve(n - 1) + solve(n - 2);\n}",
    cpp: "std::vector<long long> memo(n + 1, -1);\nstd::function<long long(int)> solve = [&](int k) -> long long {\n    if (k < 2) return k;\n    if (memo[k] != -1) return memo[k];\n    return memo[k] = solve(k - 1) + solve(k - 2);\n};",
    c: "long long memo[MAXN];  /* fill with -1 first */\nlong long solve(int n) {\n    if (n < 2) return n;\n    if (memo[n] != -1) return memo[n];\n    return memo[n] = solve(n - 1) + solve(n - 2);\n}",
    go: "memo := map[int]int{}\nvar solve func(int) int\nsolve = func(n int) int {\n    if n < 2 { return n }\n    if v, ok := memo[n]; ok { return v }\n    memo[n] = solve(n-1) + solve(n-2)\n    return memo[n]\n}"
  };

  /* ---------------- Samples (clearly labelled in the UI) ---------------- */
  const SAMPLES = [
    { id: "py-pairs", lang: "python", name: "Pair finder (Python)",
      code: "def find_pairs(nums, target, result=[]):\n    # TODO: handle empty input\n    for i in range(len(nums)):\n        for j in range(i + 1, len(nums)):\n            if nums[i] + nums[j] == target:\n                result.append((nums[i], nums[j]))\n    try:\n        best = max(result, key=lambda p: p[0])\n    except:\n        best = None\n    if best == None:\n        print(\"no pairs found\")\n    return result\n",
      fix: { code: "def find_pairs(nums, target):\n    \"\"\"Return value pairs (a, b) from nums with a + b == target.\"\"\"\n    seen = set()\n    result = []\n    for x in nums:\n        if target - x in seen:\n            result.append((target - x, x))\n        seen.add(x)\n    return result",
        changes: "One pass with a set of values seen so far. For each x, its partner target − x has either already appeared or it hasn't.", benefits: "Linear time. It also removes the shared default list, which fixes a separate bug.",
        after: { time: "O(n)", space: "O(n)" }, tradeoffs: "Pairs come out in the order the second element appears, and duplicates are reported as they're encountered. Sort the input first if you need sorted, de-duplicated pairs.", assumptions: "Assumes the elements are hashable numbers." } },
    { id: "js-dupes", lang: "javascript", name: "Duplicate finder (JavaScript)",
      code: "function findDuplicates(items) {\n  var dupes = [];\n  for (var i = 0; i < items.length; i++) {\n    for (var j = i + 1; j < items.length; j++) {\n      if (items[i] == items[j] && dupes.indexOf(items[i]) == -1) {\n        dupes.push(items[i]);\n      }\n    }\n  }\n  document.getElementById(\"out\").innerHTML = \"Duplicates: \" + dupes.join(\", \");\n  console.log(dupes);\n  return dupes;\n}\n",
      fix: { code: "function findDuplicates(items) {\n  const seen = new Set();\n  const dupes = new Set();\n  for (const item of items) {\n    if (seen.has(item)) dupes.add(item);\n    else seen.add(item);\n  }\n  return [...dupes];\n}\n\n// Rendering stays with the caller:\n// out.textContent = \"Duplicates: \" + findDuplicates(items).join(\", \");",
        changes: "Track seen values in a Set. Anything seen twice goes into a second Set, which also removes the hidden indexOf scan.", benefits: "Linear time. The function no longer touches the DOM, so it's testable on its own.",
        after: { time: "O(n)", space: "O(n)" }, tradeoffs: "Set uses SameValueZero equality, so '1' and 1 are now different. The original == treated them as equal. Decide which you want.", assumptions: "Assumes items are primitives. Objects would be compared by reference." } },
    { id: "java-search", lang: "java", name: "Binary search (Java)",
      code: "public class Search {\n    public static int binarySearch(int[] arr, int target) {\n        int low = 0, high = arr.length;\n        while (low <= high) {\n            int mid = (low + high) / 2;\n            if (arr[mid] == target) return mid;\n            else if (arr[mid] < target) low = mid + 1;\n            else high = mid - 1;\n        }\n        return -1;\n    }\n\n    public static boolean isAdmin(String role) {\n        return role == \"admin\";\n    }\n}\n" },
    { id: "c-greet", lang: "c", name: "Greeting (C)",
      code: "#include <stdio.h>\n#include <string.h>\n\nint main() {\n    char name[16];\n    char greeting[32];\n    printf(\"Enter name: \");\n    gets(name);\n    strcpy(greeting, \"Hello, \");\n    strcat(greeting, name);\n    for (int i = 0; i < strlen(greeting); i++) {\n        if (greeting[i] == ' ') greeting[i] = '_';\n    }\n    printf(\"%s\\n\", greeting);\n    return 0;\n}\n" },
    { id: "cpp-pairs", lang: "cpp", name: "Count pairs with difference k (C++)",
      code: "#include <bits/stdc++.h>\nusing namespace std;\n\nint countPairs(vector<int> nums, int k) {\n    int count = 0;\n    for (int i = 0; i < nums.size(); i++) {\n        for (int j = 0; j < nums.size(); j++) {\n            if (i != j && nums[i] - nums[j] == k) count++;\n        }\n    }\n    return count;\n}\n",
      fix: { code: "#include <unordered_map>\n#include <vector>\n\nint countPairs(const std::vector<int>& nums, int k) {\n    std::unordered_map<int, int> freq;\n    for (int x : nums) ++freq[x];\n    long long count = 0;\n    for (const auto& [value, c] : freq) {\n        if (k == 0) count += 1LL * c * (c - 1);\n        else if (auto it = freq.find(value - k); it != freq.end()) count += 1LL * c * it->second;\n    }\n    return static_cast<int>(count);\n}",
        changes: "Count each value once, then for every distinct value look up its partner value − k in the frequency map.", benefits: "Linear time, and no copy of the input vector.",
        after: { time: "O(n)", space: "O(n)" }, tradeoffs: "Needs C++17 (structured bindings, if-init). For k = 0 it counts ordered pairs of equal values, matching the original i ≠ j behaviour. Verify against your expected output.", assumptions: "Assumes the count fits in int after the long long accumulation." } },
    { id: "go-common", lang: "go", name: "Parse and intersect (Go)",
      code: "package main\n\nimport (\n    \"fmt\"\n    \"strconv\"\n)\n\nfunc sumStrings(values []string) int {\n    total := 0\n    for _, v := range values {\n        n, _ := strconv.Atoi(v)\n        total += n\n    }\n    return total\n}\n\nfunc hasCommon(a []int, b []int) bool {\n    for _, x := range a {\n        for _, y := range b {\n            if x == y {\n                return true\n            }\n        }\n    }\n    return false\n}\n\nfunc main() {\n    fmt.Println(sumStrings([]string{\"1\", \"2\", \"x\"}))\n}\n",
      fix: { code: "func hasCommon(a []int, b []int) bool {\n    if len(a) > len(b) {\n        a, b = b, a // build the set from the smaller slice\n    }\n    set := make(map[int]struct{}, len(a))\n    for _, x := range a {\n        set[x] = struct{}{}\n    }\n    for _, y := range b {\n        if _, ok := set[y]; ok {\n            return true\n        }\n    }\n    return false\n}",
        changes: "Put the smaller slice into a set, then scan the other slice once.", benefits: "O(n + m) instead of O(n·m), with memory proportional to the smaller input.",
        after: { time: "O(n + m)", space: "O(min(n, m))" }, tradeoffs: "Allocates a map. For very small slices (a handful of elements) the nested loop can actually be faster.", assumptions: "Assumes element equality is plain ==." } }
  ];

  window.CRX = window.CRX || {};
  window.CRX.Review = { analyze, CATEGORIES, SEVERITIES, PENALTY, SAMPLES, guessLang };
})();
