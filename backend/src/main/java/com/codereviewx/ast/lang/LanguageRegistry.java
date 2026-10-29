package com.codereviewx.ast.lang;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The six languages offered by the frontend's selector. Unknown ids are rejected, never re-routed. */
public final class LanguageRegistry {
    private static final Logger log = LoggerFactory.getLogger(LanguageRegistry.class);
    private final Map<String, LanguageModule> modules = new LinkedHashMap<>();

    public LanguageRegistry() {
        for (LanguageModule m : List.of(new CFamilyModule.C(), new CFamilyModule.Cpp(), new JavaModule(), new PythonModule(),
                new JavaScriptModule(), new GoModule())) {
            modules.put(m.id(), m);
            if (m.unavailableReason() != null) log.error("Language {} is unavailable: {}", m.id(), m.unavailableReason());
        }
    }

    public Optional<LanguageModule> get(String id) { return Optional.ofNullable(id == null ? null : modules.get(id)); }

    public List<LanguageModule> all() { return List.copyOf(modules.values()); }
}
