/* Code ReveiwX — starter code for the problem editor. Problems with test cases get a signature that matches their
   inputs and output, so the code can be run against the tests as soon as the body is written. */
(function () {
  "use strict";

  function names(p) {
    const fn = p.t.replace(/\(.*?\)/g, "").replace(/[^a-zA-Z0-9 ]/g, " ").trim().split(/\s+/).slice(0, 5).map((w, i) => (i ? w.charAt(0).toUpperCase() + w.slice(1).toLowerCase() : w.toLowerCase())).join("") || "solve";
    const camel = /^[0-9]/.test(fn) ? "solve" + fn : fn;
    return { camel, snake: camel.replace(/[A-Z]/g, (c) => "_" + c.toLowerCase()) };
  }

  /** The template for problems with no test data. */
  function generic(p, lang) {
    const { camel, snake } = names(p), head = "Problem #" + p.id + ": " + p.t;
    return {
      c: "/* " + head + " */\n#include <stdio.h>\n\nint " + camel + "(int arr[], int n) {\n    // write your solution here\n    return 0;\n}\n\nint main(void) {\n    return 0;\n}\n",
      cpp: "// " + head + "\n#include <iostream>\n#include <vector>\n\nint " + camel + "(const std::vector<int>& arr) {\n    // write your solution here\n    return 0;\n}\n\nint main() {\n    return 0;\n}\n",
      java: "// " + head + "\nclass Solution {\n    public int " + camel + "(int[] arr) {\n        // write your solution here\n        return 0;\n    }\n}\n",
      python: "# " + head + "\ndef " + snake + "(arr):\n    # write your solution here\n    pass\n",
      javascript: "// " + head + "\nfunction " + camel + "(arr) {\n  // write your solution here\n}\n",
      go: "// " + head + "\npackage main\n\nfunc " + camel + "(arr []int) int {\n    // write your solution here\n    return 0\n}\n\nfunc main() {}\n"
    }[lang];
  }

  /* ---------- reading the test cases ---------- */
  function splitArgs(input) {
    const out = []; let depth = 0, start = 0, quoted = false;
    for (let i = 0; i < input.length; i++) {
      const c = input[i];
      if (quoted) { if (c === "\\") i++; else if (c === '"') quoted = false; continue; }
      if (c === '"') quoted = true;
      else if ("[({".indexOf(c) > -1) depth++;
      else if ("])}".indexOf(c) > -1) depth--;
      else if (c === "," && depth === 0) { out.push(input.slice(start, i).trim()); start = i + 1; }
    }
    out.push(input.slice(start).trim());
    return out;
  }
  /** int | float | bool | string | ints | strings | grid | list */
  function kindOf(v) {
    if (v === "(empty)" || v.indexOf("->") > -1) return "list";
    if (v[0] === '"') return "string";
    if (/^\[\s*\[/.test(v)) return "grid";
    if (/^\[\s*"/.test(v)) return "strings";
    if (v[0] === "[") return "ints";
    if (v === "true" || v === "false") return "bool";
    if (/^[-+]?\d+$/.test(v)) return "int";
    if (/^[-+]?\d*\.\d+$/.test(v)) return "float";
    return "string"; // bare output text such as Even
  }
  function outputKind(tests) {
    const kinds = tests.map((t) => kindOf(String(t.out).trim()));
    return ["bool", "list", "grid", "ints", "strings", "float", "string", "int"].find((k) => kinds.indexOf(k) > -1) || "int";
  }
  function params(tests) {
    const kinds = splitArgs(String(tests[0].in)).map(kindOf);
    return kinds.map((k, i) => ({
      kind: k,
      name: { list: "head", string: i ? "t" : "s", grid: "grid", ints: "arr", strings: "words", bool: "flag", float: "x", int: i && kinds[i - 1] === "ints" ? "target" : i ? "k" : "n" }[k]
    }));
  }

  /* ---------- per-language signatures ---------- */
  const TYPES = {
    java: { int: "int", float: "double", bool: "boolean", string: "String", ints: "int[]", strings: "String[]", grid: "int[][]", list: "ListNode" },
    cpp: { int: "int", float: "double", bool: "bool", string: "std::string", ints: "std::vector<int>", strings: "std::vector<std::string>", grid: "std::vector<std::vector<int>>", list: "ListNode*" },
    c: { int: "int", float: "double", bool: "bool", string: "const char*", ints: "int", strings: "const char**", grid: "int**", list: "struct ListNode*" },
    go: { int: "int", float: "float64", bool: "bool", string: "string", ints: "[]int", strings: "[]string", grid: "[][]int", list: "*ListNode" }
  };
  const ZERO = {
    java: { int: "0", float: "0", bool: "false", string: '""', ints: "new int[0]", strings: "new String[0]", grid: "new int[0][0]", list: "null" },
    cpp: { int: "0", float: "0", bool: "false", string: '""', ints: "{}", strings: "{}", grid: "{}", list: "nullptr" },
    c: { int: "0", float: "0", bool: "false", string: '""', ints: "0", strings: "NULL", grid: "NULL", list: "NULL" },
    go: { int: "0", float: "0", bool: "false", string: '""', ints: "nil", strings: "nil", grid: "nil", list: "nil" }
  };
  const NODE_NOTE = { java: "ListNode has fields val and next", cpp: "ListNode has fields val and next", c: "struct ListNode has fields val and next", go: "ListNode has fields Val and Next", python: "ListNode has fields val and next", javascript: "ListNode has fields val and next" };

  function typed(p, tests, lang) {
    const { camel, snake } = names(p), head = "Problem #" + p.id + ": " + p.t;
    const ps = params(tests), ret = outputKind(tests);
    const usesList = ret === "list" || ps.some((x) => x.kind === "list");
    const note = usesList ? NODE_NOTE[lang] : null;
    const T = TYPES[lang], Z = ZERO[lang];
    switch (lang) {
      case "python":
        return "# " + head + "\n" + (note ? "# " + note + "\n" : "") + "def " + snake + "(" + ps.map((x) => x.name).join(", ") + "):\n    # write your solution here\n    pass\n";
      case "javascript":
        return "// " + head + "\n" + (note ? "// " + note + "\n" : "") + "function " + camel + "(" + ps.map((x) => x.name).join(", ") + ") {\n  // write your solution here\n}\n";
      case "java":
        return "// " + head + "\n" + (note ? "// " + note + "\n" : "") + "class Solution {\n    public " + T[ret] + " " + camel + "(" + ps.map((x) => T[x.kind] + " " + x.name).join(", ") + ") {\n        // write your solution here\n        return " + Z[ret] + ";\n    }\n}\n";
      case "cpp": {
        const sig = ps.map((x) => (/vector|string/.test(T[x.kind]) ? "const " + T[x.kind] + "& " : T[x.kind] + " ") + x.name).join(", ");
        const zero = ret === "string" ? "std::string()" : Z[ret];
        return "// " + head + "\n" + (note ? "// " + note + "\n" : "") + "#include <iostream>\n#include <string>\n#include <vector>\n\n" + T[ret] + " " + camel + "(" + sig + ") {\n    // write your solution here\n    return " + zero + ";\n}\n\nint main() {\n    return 0;\n}\n";
      }
      case "c": {
        const sig = ps.map((x) => (x.kind === "ints" ? "int " + x.name + "[], int " + x.name + "Len" : T[x.kind] + " " + x.name)).join(", ");
        const retType = ret === "ints" ? "int" : T[ret];
        return "/* " + head + (note ? "\n   " + note : "") + " */\n#include <stdio.h>\n#include <stdbool.h>\n\n" + retType + " " + camel + "(" + sig + ") {\n    // write your solution here\n    return " + Z[ret] + ";\n}\n\nint main(void) {\n    return 0;\n}\n";
      }
      default: // go
        return "// " + head + "\n" + (note ? "// " + note + "\n" : "") + "package main\n\nfunc " + camel + "(" + ps.map((x) => x.name + " " + T[x.kind]).join(", ") + ") " + T[ret] + " {\n    // write your solution here\n    return " + Z[ret] + "\n}\n\nfunc main() {}\n";
    }
  }

  window.CRX = window.CRX || {};
  window.CRX.Starters = {
    generic,
    forProblem: (p, tests, lang) => (tests && tests.length ? typed(p, tests, lang) : generic(p, lang))
  };
})();
