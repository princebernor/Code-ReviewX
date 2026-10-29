package com.codereviewx.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-only judge that compiles and runs the generated programs with toolchains installed on the build machine.
 * It only ever runs the fixture solutions written for these tests, to prove the harness output is valid code.
 */
final class LocalJudge implements JudgeClient {
    private final Path work;

    LocalJudge(Path work) { this.work = work; }

    static boolean available(String lang) {
        String cmd = switch (lang) {
            case "python" -> "python";
            case "javascript" -> "node";
            case "java" -> "javac";
            case "cpp" -> "g++";
            case "c" -> "gcc";
            case "go" -> "go";
            default -> null;
        };
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public String name() { return "local test judge"; }

    @Override
    public Result run(String lang, String source, String stdin) {
        try {
            Path dir = Files.createTempDirectory(work, lang);
            List<String> compile = null, run;
            switch (lang) {
                case "python" -> { Files.writeString(dir.resolve("main.py"), source); run = List.of("python", "main.py"); }
                case "javascript" -> { Files.writeString(dir.resolve("main.js"), source); run = List.of("node", "main.js"); }
                case "java" -> { Files.writeString(dir.resolve("Main.java"), source); compile = List.of("javac", "-encoding", "UTF-8", "Main.java"); run = List.of("java", "-cp", ".", "Main"); }
                case "cpp" -> { Files.writeString(dir.resolve("main.cpp"), source); compile = List.of("g++", "-std=c++14", "-o", "prog.exe", "main.cpp"); run = List.of(dir.resolve("prog.exe").toString()); }
                case "c" -> { Files.writeString(dir.resolve("main.c"), source); compile = List.of("gcc", "-std=gnu11", "-o", "prog.exe", "main.c"); run = List.of(dir.resolve("prog.exe").toString()); }
                case "go" -> { Files.writeString(dir.resolve("main.go"), source); run = List.of("go", "run", "main.go"); }
                default -> throw new IllegalArgumentException(lang);
            }
            if (compile != null) {
                String[] c = exec(compile, dir, "");
                if (!"0".equals(c[2])) return new Result(6, "Compilation Error", null, null, c[0] + c[1], null, null, null);
            }
            String[] r = exec(run, dir, stdin);
            if ("timeout".equals(r[2])) return new Result(5, "Time Limit Exceeded", r[0], r[1], null, null, null, null);
            boolean ok = "0".equals(r[2]);
            return new Result(ok ? 3 : 11, ok ? "Accepted" : "Runtime Error (NZEC)", r[0], r[1], null, null, null, null);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String[] exec(List<String> cmd, Path dir, String stdin) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(new ArrayList<>(cmd)).directory(dir.toFile()).start();
        p.getOutputStream().write((stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        var out = new java.util.concurrent.CompletableFuture<String>();
        var err = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofVirtual().start(() -> { try { out.complete(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException e) { out.complete(""); } });
        Thread.ofVirtual().start(() -> { try { err.complete(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException e) { err.complete(""); } });
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new String[] {"", "", "timeout"};
        }
        try {
            return new String[] {out.get(10, TimeUnit.SECONDS), err.get(10, TimeUnit.SECONDS), String.valueOf(p.exitValue())};
        } catch (Exception e) {
            return new String[] {"", "", String.valueOf(p.exitValue())};
        }
    }
}
