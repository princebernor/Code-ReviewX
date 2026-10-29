package com.codereviewx.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All application settings under the {@code crx.} prefix. Defaults live in application.yml. */
@ConfigurationProperties("crx")
public record CrxProperties(Frontend frontend, Auth auth, Cors cors, Review review, History history,
                            RateLimit rateLimit, Llm llm, Judge judge) {

    /**
     * Remote code execution through a Judge0-compatible API. Submitted code leaves this server and runs on the
     * judge's infrastructure. {@code languageIds} maps the frontend's language ids to the judge's ids.
     */
    public record Judge(boolean enabled, String url, String apiKey, String apiKeyHeader, String rapidApiHost,
                        java.time.Duration timeout, double cpuTimeLimit, double wallTimeLimit, int memoryLimitKb,
                        int maxCodeBytes, int maxCases, int runsPerMinute, java.util.Map<String, Integer> languageIds) {}

    /** Serving the existing static frontend from {@code dir} (relative to the working directory). */
    public record Frontend(boolean enabled, String dir) {}

    public record Auth(String jwtSecret, String issuer, Duration accessTtl, Duration refreshTtl, Duration resetTtl,
                       Duration refreshReuseGrace, boolean secureCookies, String sameSite, int bcryptStrength,
                       boolean exposeResetToken, String resetLinkBase) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Review(int maxLines, int maxBytes, long timeoutMs, int maxNesting, int workerThreads,
                         int queueCapacity) {}

    public record History(boolean storeSource, int maxPerUser) {}

    /** Requests per minute for each limited operation. */
    public record RateLimit(int loginPerMinute, int loginPerEmailPerMinute, int registerPerMinute,
                            int forgotPerMinute, int resetPerMinute, int reviewPerMinute) {}

    public record Llm(boolean enabled, String provider) {}
}
