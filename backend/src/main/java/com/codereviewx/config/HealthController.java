package com.codereviewx.config;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight probe the frontend uses to decide whether a real account server is available. */
@RestController
public class HealthController {
    private final CrxProperties props;

    public HealthController(CrxProperties props) { this.props = props; }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "llm", props.llm() != null && props.llm().enabled());
    }
}
