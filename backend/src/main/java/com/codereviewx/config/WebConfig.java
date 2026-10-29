package com.codereviewx.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the existing static frontend from an explicit allow-list: top-level *.html pages and the css/, js/,
 * data/ and assets/ folders. Nothing else under the frontend folder (backend/, docs/, README, .env, the H2
 * database) is reachable.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);
    static final String[] FOLDERS = {"css", "js", "data", "assets"};

    private final Path root;

    public WebConfig(CrxProperties props) {
        Path p = null;
        if (props.frontend() != null && props.frontend().enabled()) {
            p = Path.of(props.frontend().dir()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p.resolve("index.html"))) {
                log.warn("Frontend folder {} has no index.html; static serving is disabled. Set CRX_FRONTEND_DIR.", p);
                p = null;
            } else {
                log.info("Serving frontend from {}", p);
            }
        }
        this.root = p;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (root == null) return;
        String base = root.toUri().toString();
        for (String f : FOLDERS) registry.addResourceHandler("/" + f + "/**").addResourceLocations(base + f + "/");
        registry.addResourceHandler("/*.html").addResourceLocations(base);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (root != null) registry.addViewController("/").setViewName("forward:/index.html");
    }
}
