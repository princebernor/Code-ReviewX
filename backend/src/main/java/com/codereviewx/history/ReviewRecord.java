package com.codereviewx.history;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One saved review. {@code resultJson} is the full result the UI renders; scores are duplicated for querying. */
@Entity
@Table(name = "reviews")
public class ReviewRecord {
    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String lang;

    @Column(length = 255)
    private String filename;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int overall;

    @Column(nullable = false)
    private int efficiency;

    @Column(name = "time_complexity", nullable = false, length = 40)
    private String timeComplexity;

    @Column(name = "space_complexity", nullable = false, length = 40)
    private String spaceComplexity;

    @Column(nullable = false, length = 40)
    private String engine;

    @Column(nullable = false)
    private boolean sample;

    @Column(name = "result_json", nullable = false, columnDefinition = "text")
    private String resultJson;

    @Column(name = "source_code", columnDefinition = "text")
    private String sourceCode;

    protected ReviewRecord() {}

    public ReviewRecord(String id, Long userId, String lang, String filename, Instant createdAt, int overall, int efficiency,
                        String timeComplexity, String spaceComplexity, String engine, boolean sample, String resultJson, String sourceCode) {
        this.id = id;
        this.userId = userId;
        this.lang = lang;
        this.filename = filename;
        this.createdAt = createdAt;
        this.overall = overall;
        this.efficiency = efficiency;
        this.timeComplexity = timeComplexity;
        this.spaceComplexity = spaceComplexity;
        this.engine = engine;
        this.sample = sample;
        this.resultJson = resultJson;
        this.sourceCode = sourceCode;
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getFilename() { return filename; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isSample() { return sample; }
    public String getResultJson() { return resultJson; }
    public String getSourceCode() { return sourceCode; }
}
