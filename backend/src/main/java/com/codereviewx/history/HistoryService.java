package com.codereviewx.history;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.codereviewx.config.ApiException;
import com.codereviewx.config.CrxProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user review history. Items are returned in the same shape as the browser's {@code crx.reviews} entries
 * (the result plus {@code id, at, code, file, sample}), so the existing History page renders them unchanged.
 */
@Service
public class HistoryService {
    static final int MAX_RESULT_CHARS = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> SUPPORTED = List.of("c", "cpp", "java", "python", "javascript", "go");

    private final ReviewRecordRepository repo;
    private final ObjectMapper json;
    private final CrxProperties props;
    private final Clock clock;

    public HistoryService(ReviewRecordRepository repo, ObjectMapper json, CrxProperties props, Optional<Clock> clock) {
        this.repo = repo;
        this.json = json;
        this.props = props;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Saves a result produced by the server engine; returns the stored item. */
    @Transactional
    public Map<String, Object> saveServerReview(Long userId, Map<String, Object> result, String code, String filename) {
        return save(userId, result, code, filename, false, clock.instant());
    }

    /**
     * Stores a review the client already has (e.g. demo reviews being imported into the account, or the Settings
     * page's sample records). It is the user's own record; it is validated for shape and size only.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importReview(Long userId, Map<String, Object> review) {
        Object lang = review.get("lang");
        if (!(lang instanceof String l) || !SUPPORTED.contains(l)) throw ApiException.badRequest("validation", "lang must be a supported language id.");
        for (String k : List.of("overall", "efficiency")) {
            if (!(review.get(k) instanceof Number n) || n.intValue() < 0 || n.intValue() > 100) throw ApiException.badRequest("validation", k + " must be 0-100.");
        }
        if (!(review.get("findings") instanceof List<?> fs) || fs.size() > 500) throw ApiException.badRequest("validation", "findings must be a list.");
        if (!(review.get("counts") instanceof Map) || !(review.get("categories") instanceof Map)) throw ApiException.badRequest("validation", "counts and categories are required.");
        Map<String, Object> result = new LinkedHashMap<>(review);
        String code = review.get("code") instanceof String s ? s : null;
        if (code != null && code.length() > props.review().maxBytes()) throw ApiException.badRequest("validation", "code is too large.");
        String file = review.get("file") instanceof String s ? s : null;
        boolean sample = Boolean.TRUE.equals(review.get("sample"));
        Instant at = review.get("at") instanceof Number n && n.longValue() > 0 && n.longValue() <= clock.millis() + 86_400_000L
            ? Instant.ofEpochMilli(n.longValue()) : clock.instant();
        for (String k : List.of("id", "at", "code", "file", "sample")) result.remove(k);
        return save(userId, result, code, file, sample, at);
    }

    private Map<String, Object> save(Long userId, Map<String, Object> result, String code, String filename, boolean sample, Instant at) {
        String body;
        try {
            body = json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("validation", "The review could not be serialized.");
        }
        if (body.length() > MAX_RESULT_CHARS) throw ApiException.badRequest("validation", "The review is too large to store.");
        String id = "r" + Long.toString(at.toEpochMilli(), 36) + (Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36) + "000000").substring(0, 6);
        String file = filename == null || filename.isBlank() ? null : filename.length() > 255 ? filename.substring(0, 255) : filename;
        repo.save(new ReviewRecord(id, userId, String.valueOf(result.get("lang")), file, at, num(result.get("overall")), num(result.get("efficiency")),
            str(result.get("time"), "O(?)"), str(result.get("space"), "O(?)"), str(result.get("engine"), "unknown"), sample, body,
            props.history().storeSource() ? code : null));
        trim(userId);
        return item(repo.findByIdAndUserId(id, userId).orElseThrow());
    }

    /** Keeps at most crx.history.max-per-user reviews, dropping the oldest. */
    private void trim(Long userId) {
        int max = props.history().maxPerUser();
        if (repo.countByUserId(userId) <= max) return;
        List<String> ids = repo.idsNewestFirst(userId);
        for (String old : ids.subList(max, ids.size())) repo.deleteOwned(old, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Long userId, int page, int size) {
        int s = Math.max(1, Math.min(size, 100));
        Page<ReviewRecord> p = repo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(0, page), s));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", p.getContent().stream().map(this::item).toList());
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        out.put("total", p.getTotalElements());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long userId, String id) {
        return item(repo.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound));
    }

    @Transactional
    public void delete(Long userId, String id) {
        if (repo.deleteOwned(id, userId) == 0) throw ApiException.notFound();
    }

    @Transactional
    public int deleteAll(Long userId) { return repo.deleteAllOwned(userId); }

    private Map<String, Object> item(ReviewRecord r) {
        Map<String, Object> m;
        try {
            m = json.readValue(r.getResultJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            m = new LinkedHashMap<>();
        }
        m.put("id", r.getId());
        m.put("at", r.getCreatedAt().toEpochMilli());
        m.put("code", r.getSourceCode());
        m.put("file", r.getFilename());
        if (r.isSample()) m.put("sample", true);
        return m;
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    private static String str(Object o, String d) {
        String s = o == null ? d : o.toString();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
