package com.codereviewx.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.codereviewx.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@Import(RunApiTest.FakeJudgeConfig.class)
class RunApiTest extends ApiTestSupport {
    static final AtomicReference<String> lastSource = new AtomicReference<>();

    @TestConfiguration
    static class FakeJudgeConfig {
        /** Answers like Judge0 would for a correct #1 solution: case 0 → Even, case 1 → Odd. */
        @Bean
        @Primary
        JudgeClient fakeJudge() {
            return new JudgeClient() {
                @Override
                public Result run(String lang, String source, String stdin) {
                    lastSource.set(source);
                    if (source.contains("compile_me_not")) return new Result(6, "Compilation Error", null, null, "main.py: error", null, null, null);
                    return new Result(3, "Accepted", "hi\n\n@@CRX:0:OK:Even\n\n@@CRX:1:OK:Odd\n", "", null, null, 0.01, 3000);
                }

                @Override
                public String name() { return "fake judge"; }
            };
        }
    }

    static final List<Map<String, String>> CASES = List.of(Map.of("in", "4", "out", "Even"), Map.of("in", "7", "out", "Odd"));

    @Test
    void runningCodeRequiresAnAccount() throws Exception {
        assertThat(postJson(new Browser(), "/api/run", Map.of("lang", "python", "mode", "tests", "code", "def f(n):\n    return n\n", "cases", CASES))
            .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testsAreGradedPerCase() throws Exception {
        Browser b = signedUp("runner@example.com");
        Map<String, Object> r = body(postJson(b, "/api/run", Map.of("lang", "python", "mode", "tests",
            "code", "def even_or_odd(n):\n    print('hi')\n    return 'Even' if n % 2 == 0 else 'Odd'\n", "cases", CASES)));
        assertThat(r).containsEntry("allPassed", true).containsEntry("passed", 2).containsEntry("judge", "fake judge").containsEntry("stdout", "hi");
        assertThat((List<Map<String, Object>>) r.get("cases")).extracting(c -> c.get("verdict")).containsExactly("Passed", "Passed");
        assertThat(lastSource.get()).contains("even_or_odd(*__crx_args)");
    }

    @Test
    void setupProblemsAndCompileErrorsAreExplained() throws Exception {
        Browser b = signedUp("runner2@example.com");
        Map<String, Object> noFn = body(postJson(b, "/api/run", Map.of("lang", "python", "mode", "tests", "code", "x = 1\nprint(x)\n", "cases", CASES)));
        assertThat(noFn.get("setupError").toString()).contains("Couldn't find a function");
        Map<String, Object> ce = body(postJson(b, "/api/run", Map.of("lang", "python", "mode", "tests", "code", "def compile_me_not(n):\n    return n\n", "cases", CASES)));
        assertThat(ce).containsEntry("statusId", 6).containsEntry("allPassed", false);
        assertThat(body(postJson(b, "/api/run", Map.of("lang", "cobol", "mode", "tests", "code", "x", "cases", CASES))).get("error")).isEqualTo("unsupported_language");
        assertThat(body(postJson(b, "/api/run", Map.of("lang", "python", "mode", "tests", "code", "def f(n):\n    return n\n", "cases", List.of()))).get("error")).isEqualTo("validation");
    }

    @Test
    void runsAreRateLimitedPerUser() throws Exception {
        Browser b = signedUp("runner3@example.com");
        int last = 0;
        for (int i = 0; i < 11; i++) {
            last = postJson(b, "/api/run", Map.of("lang", "python", "mode", "stdin", "code", "print(1)\n", "stdin", "")).getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
    }
}
