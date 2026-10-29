package com.codereviewx.execution;

import java.util.List;
import java.util.Map;

import com.codereviewx.config.CrxProperties;
import com.codereviewx.config.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Signed-in users only: code is sent to an external judge, so runs are tied to an account and rate-limited. */
@RestController
@RequestMapping("/api/run")
public class RunController {
    public record Case(@NotNull @Size(max = 10_000) String in, @NotNull @Size(max = 10_000) String out) {}
    public record RunRequest(@NotNull @Size(max = 16) String lang, @NotNull @Size(max = 200_000) String code,
                             @NotNull @Pattern(regexp = "tests|stdin") String mode, @Valid @Size(max = 50) List<Case> cases,
                             @Size(max = 64_000) String stdin) {}

    private final RunService runs;
    private final RateLimiter limiter;
    private final CrxProperties props;
    private final JudgeClient judge;

    public RunController(RunService runs, RateLimiter limiter, CrxProperties props, JudgeClient judge) {
        this.runs = runs;
        this.limiter = limiter;
        this.props = props;
        this.judge = judge;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of("enabled", props.judge().enabled(), "judge", judge.name(), "languages", props.judge().languageIds().keySet());
    }

    @PostMapping
    public Map<String, Object> run(@Valid @RequestBody RunRequest r, @AuthenticationPrincipal Jwt jwt) {
        limiter.check("run", "u" + jwt.getSubject(), props.judge().runsPerMinute());
        if (r.mode().equals("stdin")) return runs.runStdin(r.lang(), r.code(), r.stdin());
        List<RunService.TestCase> cases = r.cases() == null ? List.of() : r.cases().stream().map(c -> new RunService.TestCase(c.in(), c.out())).toList();
        return runs.runTests(r.lang(), r.code(), cases);
    }
}
