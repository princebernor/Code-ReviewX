package com.codereviewx.review;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.config.ApiException;
import com.codereviewx.config.CrxProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runs analyses on a small bounded worker pool with a deadline, so one pathological input can't tie up request
 * threads or the whole server. Workers get a large stack because tree conversion is recursive.
 */
@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final long WORKER_STACK = 16L * 1024 * 1024;

    private final ReviewEngine engine;
    private final LanguageRegistry languages;
    private final ThreadPoolExecutor pool;
    private final long timeoutMs;

    public ReviewService(CrxProperties props) {
        CrxProperties.Review cfg = props.review();
        this.languages = new LanguageRegistry();
        ExplanationProvider explainer = new ExplanationProvider.Deterministic();
        if (props.llm() != null && props.llm().enabled()) {
            log.warn("crx.llm.enabled=true, but no LLM provider is implemented in this build; using deterministic explanations.");
        }
        this.engine = new ReviewEngine(languages, new InputValidator(cfg), explainer);
        this.timeoutMs = cfg.timeoutMs();
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(cfg.workerThreads(), cfg.workerThreads(), 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(cfg.queueCapacity()),
            r -> {
                Thread t = new Thread(null, r, "crx-review-" + n.incrementAndGet(), WORKER_STACK);
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public LanguageRegistry languages() { return languages; }

    public Map<String, Object> review(String code, String lang, boolean explain) {
        return run(() -> engine.review(code, lang, explain));
    }

    public ParseResult parse(String code, String lang) {
        return run(() -> engine.parse(code, lang));
    }

    private <T> T run(Callable<T> task) {
        Future<T> f;
        try {
            f = pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "busy", "The review server is busy. Try again in a moment.");
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "timeout", "The analysis took too long. Try a smaller piece of code.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "busy", "The review was interrupted. Try again.");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof ApiException a) throw a;
            if (c instanceof StackOverflowError) throw ApiException.badRequest("too_deep", "The code is nested too deeply to analyse.");
            log.error("Analysis failed", c);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "analysis_failed", "The analysis failed on this input.");
        }
    }

    @PreDestroy
    void shutdown() { pool.shutdownNow(); }
}
