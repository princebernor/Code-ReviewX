package com.codereviewx.progress;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The browser's {@code crx.progress} map and {@code crx.activity} log for one user, stored as JSON. */
@Entity
@Table(name = "progress")
public class ProgressRecord {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "data_json", nullable = false, columnDefinition = "text")
    private String dataJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private long version;

    protected ProgressRecord() {}

    public ProgressRecord(Long userId, String dataJson, Instant updatedAt) {
        this.userId = userId;
        this.dataJson = dataJson;
        this.updatedAt = updatedAt;
    }

    public String getDataJson() { return dataJson; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void replace(String json, Instant now) {
        this.dataJson = json;
        this.updatedAt = now;
        this.version++;
    }
}
