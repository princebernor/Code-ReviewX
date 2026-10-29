package com.codereviewx.progress;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.codereviewx.config.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Practice progress in the frontend's own structure:
 * {@code progress: {"<problemId>": {status: "solved"|"attempted", at}}} and {@code activity: [{type, ref, at}]}.
 * PUT replaces the stored state; when {@code version} is sent and is stale it returns 409, and the client reloads,
 * merges and retries.
 */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {
    static final int MAX_PROBLEM_ID = 10_000, MAX_ACTIVITY = 400;
    private static final Set<String> STATUSES = Set.of("solved", "attempted");
    private static final Set<String> ACTIVITY_TYPES = Set.of("review", "solved", "attempted");

    private final ProgressRepository repo;
    private final ObjectMapper json;
    private final Clock clock;

    public ProgressController(ProgressRepository repo, ObjectMapper json, Optional<Clock> clock) {
        this.repo = repo;
        this.json = json;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt) {
        return view(repo.findById(Long.valueOf(jwt.getSubject())).orElse(null));
    }

    @PutMapping
    @Transactional
    public Map<String, Object> put(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        Long user = Long.valueOf(jwt.getSubject());
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("progress", progress(body.get("progress")));
        clean.put("activity", activity(body.get("activity")));
        ProgressRecord rec = repo.findById(user).orElse(null);
        if (body.get("version") instanceof Number v && rec != null && v.longValue() != rec.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "stale_version", "Progress changed elsewhere. Reload it, merge and retry.");
        }
        String data;
        try {
            data = json.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("validation", "Progress could not be stored.");
        }
        Instant now = clock.instant();
        if (rec == null) rec = new ProgressRecord(user, data, now);
        else rec.replace(data, now);
        return view(repo.saveAndFlush(rec));
    }

    private static Map<String, Object> progress(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> m)) throw ApiException.badRequest("validation", "progress must be an object.");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (!id.matches("\\d{1,5}") || Integer.parseInt(id) < 1 || Integer.parseInt(id) > MAX_PROBLEM_ID) {
                throw ApiException.badRequest("validation", "progress keys must be problem ids.");
            }
            if (!(e.getValue() instanceof Map<?, ?> v) || !STATUSES.contains(String.valueOf(v.get("status"))) || !(v.get("at") instanceof Number at)) {
                throw ApiException.badRequest("validation", "Each progress entry needs status (solved|attempted) and at.");
            }
            out.put(id, Map.of("status", v.get("status"), "at", at.longValue()));
        }
        return out;
    }

    private static List<Object> activity(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l) || l.size() > MAX_ACTIVITY) throw ApiException.badRequest("validation", "activity must be a list of at most 400 entries.");
        List<Object> out = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> a) || !ACTIVITY_TYPES.contains(String.valueOf(a.get("type"))) || !(a.get("at") instanceof Number at)) {
                throw ApiException.badRequest("validation", "Each activity entry needs type and at.");
            }
            String ref = a.get("ref") == null ? "" : String.valueOf(a.get("ref"));
            out.add(Map.of("type", a.get("type"), "ref", ref.length() > 40 ? ref.substring(0, 40) : ref, "at", at.longValue()));
        }
        return out;
    }

    private Map<String, Object> view(ProgressRecord rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> data = Map.of();
        if (rec != null) {
            try {
                data = json.readValue(rec.getDataJson(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                data = Map.of();
            }
        }
        out.put("progress", data.getOrDefault("progress", Map.of()));
        out.put("activity", data.getOrDefault("activity", List.of()));
        out.put("updatedAt", rec == null ? null : rec.getUpdatedAt().toEpochMilli());
        out.put("version", rec == null ? 0 : rec.getVersion());
        return out;
    }
}
