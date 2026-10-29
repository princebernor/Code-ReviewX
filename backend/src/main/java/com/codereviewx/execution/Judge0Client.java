package com.codereviewx.execution;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.codereviewx.config.ApiException;
import com.codereviewx.config.CrxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Judge0 REST client (public CE, self-hosted, or RapidAPI). Uses one synchronous submission per run
 * ({@code wait=true}) with base64 payloads, CPU/wall/memory limits, and networking left disabled.
 */
@Component
public class Judge0Client implements JudgeClient {
    private static final Logger log = LoggerFactory.getLogger(Judge0Client.class);

    private final CrxProperties.Judge cfg;
    private final ObjectMapper json;
    private final HttpClient http;

    public Judge0Client(CrxProperties props, ObjectMapper json) {
        this.cfg = props.judge();
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    }

    @Override
    public String name() {
        String host = URI.create(cfg.url()).getHost();
        return "Judge0 (" + host + ")";
    }

    @Override
    public Result run(String lang, String source, String stdin) {
        Integer languageId = cfg.languageIds().get(lang);
        if (languageId == null) throw ApiException.badRequest("unsupported_language", "Running " + lang + " code isn't configured.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language_id", languageId);
        body.put("source_code", b64(source));
        body.put("stdin", b64(stdin == null ? "" : stdin));
        body.put("cpu_time_limit", cfg.cpuTimeLimit());
        body.put("wall_time_limit", cfg.wallTimeLimit());
        body.put("memory_limit", cfg.memoryLimitKb());
        body.put("enable_network", false);
        HttpRequest.Builder req;
        try {
            req = HttpRequest.newBuilder(URI.create(cfg.url().replaceAll("/+$", "") + "/submissions?base64_encoded=true&wait=true&fields=*"))
                .timeout(cfg.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) req.header(cfg.apiKeyHeader(), cfg.apiKey());
        if (cfg.rapidApiHost() != null && !cfg.rapidApiHost().isBlank()) req.header("X-RapidAPI-Host", cfg.rapidApiHost());
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "judge_timeout", "The code-execution service didn't answer in time. Try again.");
        } catch (IOException e) {
            log.warn("Judge unreachable: {}", e.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_unavailable", "The code-execution service can't be reached right now.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_unavailable", "The run was interrupted.");
        }
        if (res.statusCode() == 429) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_busy", "The code-execution service is rate-limiting requests. Wait a minute and try again.");
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            log.error("Judge rejected the request ({}). Check CRX_JUDGE_API_KEY.", res.statusCode());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_unavailable", "The code-execution service rejected the request (server configuration).");
        }
        if (res.statusCode() / 100 != 2) {
            log.warn("Judge returned HTTP {}: {}", res.statusCode(), res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_unavailable", "The code-execution service returned an error.");
        }
        try {
            JsonNode n = json.readTree(res.body());
            JsonNode st = n.path("status");
            return new Result(st.path("id").asInt(13), st.path("description").asText("Unknown"), unb64(n, "stdout"), unb64(n, "stderr"),
                unb64(n, "compile_output"), unb64(n, "message"), n.hasNonNull("time") ? n.get("time").asDouble() : null,
                n.hasNonNull("memory") ? n.get("memory").asInt() : null);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "judge_unavailable", "The code-execution service sent an unreadable reply.");
        }
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private static String unb64(JsonNode n, String field) {
        if (!n.hasNonNull(field)) return null;
        try {
            return new String(Base64.getMimeDecoder().decode(n.get(field).asText()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return n.get(field).asText();
        }
    }
}
