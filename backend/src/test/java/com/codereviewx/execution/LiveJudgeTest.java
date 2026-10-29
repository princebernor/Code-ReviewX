package com.codereviewx.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.codereviewx.config.CrxProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Sends one reference solution per language to the real Judge0 CE. Off by default (network + third party);
 * run with CRX_LIVE_JUDGE=1 mvn test -Dtest=LiveJudgeTest.
 */
@EnabledIfEnvironmentVariable(named = "CRX_LIVE_JUDGE", matches = "1")
class LiveJudgeTest {
    @ParameterizedTest(name = "#{0} {1} on Judge0 CE")
    @CsvSource({"1, python", "36, cpp", "92, java", "127, javascript", "170, go", "335, c"})
    @SuppressWarnings("unchecked")
    void referenceSolutionPassesOnTheRealJudge(int problem, String lang) throws Exception {
        CrxProperties.Judge cfg = new CrxProperties.Judge(true, "https://ce.judge0.com", "", "X-Auth-Token", "", Duration.ofSeconds(60), 5, 10,
            256000, 102400, 20, 10, Map.of("c", 103, "cpp", 105, "java", 91, "python", 113, "javascript", 102, "go", 107));
        CrxProperties props = new CrxProperties(null, null, null, null, null, null, null, cfg);
        RunService runs = new RunService(new Judge0Client(props, new ObjectMapper()), props);
        HarnessExecutionTest.setUp();
        Map<String, Object> p = HarnessExecutionTest.problems.stream().filter(x -> x.get("id").equals(problem)).findFirst().orElseThrow();
        String code = ((Map<String, String>) p.get("solution")).get(lang);
        Map<String, Object> r = runs.runTests(lang, code, HarnessExecutionTest.tests.get(problem));
        System.out.println("#" + problem + " " + lang + " → " + r.get("status") + " " + r.get("passed") + "/" + r.get("total") + " time=" + r.get("time"));
        assertThat(r.get("allPassed")).as(String.valueOf(r)).isEqualTo(true);
        assertThat((List<?>) r.get("cases")).isNotEmpty();
    }
}
