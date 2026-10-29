package com.codereviewx.history;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The owner is always the authenticated user; ids from the client are only ever looked up within that owner. */
@RestController
@RequestMapping("/api/reviews")
public class HistoryController {
    private final HistoryService history;

    public HistoryController(HistoryService history) { this.history = history; }

    static Long user(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "60") int size) {
        return history.list(user(jwt), page, size);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> review) {
        return ResponseEntity.status(HttpStatus.CREATED).body(history.importReview(user(jwt), review));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return history.get(user(jwt), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        history.delete(user(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("deleted", history.deleteAll(user(jwt)));
    }
}
