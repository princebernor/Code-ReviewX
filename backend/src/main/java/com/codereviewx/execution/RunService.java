package com.codereviewx.execution;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.config.ApiException;
import com.codereviewx.config.CrxProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Runs practice code on the judge: test cases against expected outputs, or a whole program with custom input. */
@Service
public class RunService {
    public record TestCase(String in, String out) {}

    private static final Pattern RESULT = Pattern.compile("^" + Pattern.quote(Harness.MARK) + "(\\d+):(OK|ERR):(.*)$");
    private static final int MAX_OUTPUT = 10_000;

    private final JudgeClient judge;
    private final Harness harness;
    private final CrxProperties.Judge cfg;

    public RunService(JudgeClient judge, CrxProperties props) {
        this.judge = judge;
        this.cfg = props.judge();
        this.harness = new Harness(new LanguageRegistry());
    }

    public Map<String, Object> runTests(String lang, String code, List<TestCase> cases) {
        check(lang, code);
        if (cases == null || cases.isEmpty()) throw ApiException.badRequest("validation", "Select at least one test case.");
        if (cases.size() > cfg.maxCases()) throw ApiException.badRequest("validation", "At most " + cfg.maxCases() + " test cases per run.");
        List<List<TestValue>> parsed = new ArrayList<>();
        for (TestCase c : cases) {
            try {
                parsed.add(TestValue.parseArgs(c.in()));
            } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
                throw ApiException.badRequest("validation", "A test input couldn't be read: " + c.in());
            }
        }
        Harness.Program program;
        try {
            program = harness.forTests(lang, code, parsed);
        } catch (Harness.HarnessException e) {
            return setupError(e.getMessage());
        }
        JudgeClient.Result r = judge.run(lang, program.source(), program.stdin());
        Map<String, Object> out = base(r);
        out.put("mode", "tests");
        out.put("entry", program.entry());
        String[] lines = r.stdout() == null ? new String[0] : r.stdout().split("\n", -1);
        Map<Integer, String[]> results = new LinkedHashMap<>();
        StringBuilder userOut = new StringBuilder();
        for (String line : lines) {
            Matcher m = RESULT.matcher(line.replace("\r", ""));
            if (m.matches()) results.putIfAbsent(Integer.parseInt(m.group(1)), new String[] {m.group(2), m.group(3)});
            else userOut.append(line).append('\n');
        }
        out.put("stdout", clip(userOut.toString().replaceAll("\\n+$", "").replaceAll("^\\n+", "")));
        List<Map<String, Object>> rows = new ArrayList<>();
        int passed = 0;
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("input", cases.get(i).in());
            row.put("expected", cases.get(i).out());
            String[] res = results.get(i);
            if (res == null) {
                row.put("verdict", r.compileError() ? "Compilation error" : r.timeLimit() ? "Time limit exceeded" : "Runtime error");
                row.put("actual", null);
                row.put("passed", false);
            } else if (res[0].equals("ERR")) {
                row.put("verdict", "Runtime error");
                row.put("actual", null);
                row.put("error", res[1]);
                row.put("passed", false);
            } else {
                String actual = res[1].replace("\\n", "\n");
                boolean ok = matches(actual, cases.get(i).out());
                row.put("verdict", ok ? "Passed" : "Wrong answer");
                row.put("actual", actual);
                row.put("passed", ok);
                if (ok) passed++;
            }
            rows.add(row);
        }
        out.put("cases", rows);
        out.put("passed", passed);
        out.put("total", cases.size());
        out.put("allPassed", passed == cases.size());
        return out;
    }

    public Map<String, Object> runStdin(String lang, String code, String stdin) {
        check(lang, code);
        if (stdin != null && stdin.length() > 64_000) throw ApiException.badRequest("validation", "Input is limited to 64 KB.");
        Harness.Program program;
        try {
            program = harness.forStdin(lang, code, stdin);
        } catch (Harness.HarnessException e) {
            return setupError(e.getMessage());
        }
        JudgeClient.Result r = judge.run(lang, program.source(), program.stdin());
        Map<String, Object> out = base(r);
        out.put("mode", "stdin");
        out.put("stdout", clip(r.stdout()));
        return out;
    }

    private void check(String lang, String code) {
        if (!cfg.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_disabled", "Code execution is turned off on this server.");
        if (!cfg.languageIds().containsKey(lang)) throw ApiException.badRequest("unsupported_language", "That language can't be run.");
        if (code == null || code.isBlank()) throw ApiException.badRequest("empty", "Write some code first.");
        if (code.getBytes(StandardCharsets.UTF_8).length > cfg.maxCodeBytes()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Code is limited to 100 KB per run.");
    }

    private Map<String, Object> base(JudgeClient.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("judge", judge.name());
        out.put("status", r.status());
        out.put("statusId", r.statusId());
        out.put("compileOutput", clip(r.compileOutput()));
        out.put("stderr", clip(r.stderr()));
        out.put("message", r.message());
        out.put("time", r.timeSeconds());
        out.put("memoryKb", r.memoryKb());
        return out;
    }

    private Map<String, Object> setupError(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("judge", judge.name());
        out.put("status", "Not run");
        out.put("setupError", message);
        return out;
    }

    /** Output comparison: whitespace-insensitive around separators, case-insensitive booleans, numeric equality, empty lists. */
    static boolean matches(String actual, String expected) {
        String a = norm(actual), e = norm(expected);
        if (a.equals(e)) return true;
        if (e.equals("(empty)")) return a.equals("null") || a.equals("none") || a.equals("[]") || a.isEmpty() || a.equals("nil");
        if (a.equalsIgnoreCase(e) && (e.equalsIgnoreCase("true") || e.equalsIgnoreCase("false"))) return true;
        try {
            return Double.parseDouble(a) == Double.parseDouble(e) && !a.isEmpty();
        } catch (NumberFormatException x) {
            return false;
        }
    }

    private static String norm(String s) {
        if (s == null) return "null";
        String t = s.strip().replaceAll("\\s*([,\\[\\]{}()]|->)\\s*", "$1").replaceAll("\\s+", " ");
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.equals("None") ? "none" : t;
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() <= MAX_OUTPUT ? s : s.substring(0, MAX_OUTPUT) + "\n… (output truncated)";
    }
}
