package com.codereviewx.execution;

/** Runs one program on an isolated remote judge. Implementations never execute code on this server. */
public interface JudgeClient {
    /** Judge0 status ids: 3 accepted, 5 time limit, 6 compilation error, 7-12 runtime errors, 13 internal error. */
    record Result(int statusId, String status, String stdout, String stderr, String compileOutput, String message,
                  Double timeSeconds, Integer memoryKb) {
        public boolean compileError() { return statusId == 6; }
        public boolean timeLimit() { return statusId == 5; }
        public boolean internalError() { return statusId == 13 || statusId == 14; }
    }

    Result run(String lang, String source, String stdin);

    /** Human-readable name shown next to results, e.g. "Judge0 CE (ce.judge0.com)". */
    String name();
}
