package com.codereviewx.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codereviewx.ast.fingerprint.Fingerprints;
import com.codereviewx.ast.lang.LanguageModule;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.model.UNode;
import com.codereviewx.config.CrxProperties;
import com.codereviewx.config.RateLimiter;
import com.codereviewx.history.HistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReviewController {
    /** 512 KB of code is enforced in bytes by InputValidator; this only stops absurd payloads early. */
    static final int MAX_CODE_CHARS = 600_000;

    public record ReviewRequest(@NotNull @Size(max = MAX_CODE_CHARS) String code, @NotNull @Size(max = 16) String lang,
                                @Size(max = 255) String filename, Boolean save, Boolean explain) {}
    public record AstRequest(@NotNull @Size(max = MAX_CODE_CHARS) String code, @NotNull @Size(max = 16) String lang, Integer maxDepth) {}
    public record Snippet(@NotNull @Size(max = MAX_CODE_CHARS) String code, @NotNull @Size(max = 16) String lang) {}
    public record CompareRequest(@NotNull @Valid Snippet a, @NotNull @Valid Snippet b) {}

    private final ReviewService reviews;
    private final HistoryService history;
    private final RateLimiter limiter;
    private final CrxProperties props;

    public ReviewController(ReviewService reviews, HistoryService history, RateLimiter limiter, CrxProperties props) {
        this.reviews = reviews;
        this.history = history;
        this.limiter = limiter;
        this.props = props;
    }

    @GetMapping("/languages")
    public List<Map<String, Object>> languages() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LanguageModule m : reviews.languages().all()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", m.id());
            l.put("label", m.label());
            l.put("extensions", m.fileExtensions().stream().sorted().toList());
            l.put("parser", m.parser());
            l.put("available", m.unavailableReason() == null);
            l.put("rules", m.rules().size());
            out.add(l);
        }
        return out;
    }

    /** Analyses code; when signed in and {@code save} is true the result is also stored in the user's history. */
    @PostMapping("/review")
    public Map<String, Object> review(@Valid @RequestBody ReviewRequest r, @AuthenticationPrincipal Jwt jwt, HttpServletRequest req) {
        limiter.check("review", jwt != null ? "u" + jwt.getSubject() : req.getRemoteAddr(), props.rateLimit().reviewPerMinute());
        Map<String, Object> result = reviews.review(r.code(), r.lang(), Boolean.TRUE.equals(r.explain()));
        if (jwt != null && Boolean.TRUE.equals(r.save())) {
            Map<String, Object> saved = history.saveServerReview(Long.valueOf(jwt.getSubject()), result, r.code(), r.filename());
            saved.put("saved", true);
            return saved;
        }
        result.put("saved", false);
        return result;
    }

    /** The normalized (unified) AST, for inspection and tooling. */
    @PostMapping("/ast")
    public Map<String, Object> ast(@Valid @RequestBody AstRequest r, HttpServletRequest req) {
        limiter.check("review", req.getRemoteAddr(), props.rateLimit().reviewPerMinute());
        ParseResult p = reviews.parse(r.code(), r.lang());
        int depth = r.maxDepth() == null ? 30 : Math.max(1, Math.min(r.maxDepth(), 60));
        int[] budget = {5000};
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lang", p.language());
        out.put("parser", p.parser());
        out.put("partial", p.partial());
        out.put("diagnostics", p.diagnostics());
        out.put("ast", node(p.root(), depth, budget));
        out.put("truncated", budget[0] <= 0);
        return out;
    }

    /** Structural similarity of two snippets (possibly in different languages). Not a semantic-equivalence check. */
    @PostMapping("/compare")
    public Map<String, Object> compare(@Valid @RequestBody CompareRequest r, HttpServletRequest req) {
        limiter.check("review", req.getRemoteAddr(), props.rateLimit().reviewPerMinute());
        ParseResult a = reviews.parse(r.a().code(), r.a().lang()), b = reviews.parse(r.b().code(), r.b().lang());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("similarity", Math.round(Fingerprints.similarity(a.root(), b.root()) * 1000) / 1000.0);
        out.put("a", Map.of("lang", a.language(), "fingerprint", Fingerprints.fingerprint(a.root()), "functions", Fingerprints.functions(a.root())));
        out.put("b", Map.of("lang", b.language(), "fingerprint", Fingerprints.fingerprint(b.root()), "functions", Fingerprints.functions(b.root())));
        out.put("note", "Multiset Jaccard similarity of structural AST shapes (identifiers and literals ignored). "
            + "High values mean similar structure, not identical behaviour.");
        return out;
    }

    private static Map<String, Object> node(UNode n, int depth, int[] budget) {
        budget[0]--;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", n.kind().name());
        m.put("type", n.type());
        if (n.name() != null) m.put("name", n.name());
        if (n.op() != null) m.put("op", n.op());
        m.put("path", n.path());
        m.put("span", Map.of("startLine", n.span().startLine(), "startCol", n.span().startCol(), "endLine", n.span().endLine(), "endCol", n.span().endCol()));
        if (!n.attrs().isEmpty()) m.put("attrs", n.attrs());
        if (!n.children().isEmpty()) {
            if (depth <= 0 || budget[0] <= 0) m.put("children", "truncated");
            else {
                List<Object> cs = new ArrayList<>();
                for (UNode c : n.children()) {
                    if (budget[0] <= 0) break;
                    cs.add(node(c, depth - 1, budget));
                }
                m.put("children", cs);
            }
        }
        return m;
    }
}
