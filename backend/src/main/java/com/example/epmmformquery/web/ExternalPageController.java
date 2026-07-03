package com.example.epmmformquery.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a FIXED set of static HTML pages plus a fallback page, with the same
 * two Fortify fixes as before (allow-list breaks path-traversal taint; request
 * data is never reflected into the body).
 *
 * This version replaces the recursive serveFile(..., isFallback) form with a
 * flat, linear flow so the fallback path is obvious:
 *
 *     known page file present?    -> serve it
 *     else fallback file present? -> serve it          (THIS is the 501.html path)
 *     else                        -> serve EMERGENCY_HTML
 *
 * readPage() is the single file reader; it only ever receives constant
 * filenames (allow-list value or the configured fallback name), never request
 * data, and confines every read to baseDir.
 */
@RestController
@RequestMapping("/page")
public class ExternalPageController {

    private static final Logger log = LoggerFactory.getLogger(ExternalPageController.class);

    private static final Map<String, String> ALLOWED_PAGES = Map.of(
            "access-denied",   "access-denied",
            "logged-out",      "logged-out",
            "error",           "error",
            "session-expired", "session-expired"
    );

    private static final String EMERGENCY_HTML = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><title>Notice</title></head>
            <body><h1>Page not available</h1>
            <p>Please return to the application.</p></body></html>
            """;

    private final Path baseDir;
    private final String fallbackFile;

    public ExternalPageController(
            @Value("${app.pages.dir:#{systemProperties['user.dir']}}") String pagesDir,
            @Value("${app.pages.fallback-file:501.html}") String fallbackFile) {
        this.baseDir = Paths.get(pagesDir).toAbsolutePath().normalize();
        this.fallbackFile = Paths.get(fallbackFile).getFileName().toString();
        log.info("ExternalPageController serving from {} (fallback={})", baseDir, this.fallbackFile);
    }

    @GetMapping("/{pageName:[a-z0-9-]{1,40}}")
    public ResponseEntity<String> servePage(@PathVariable String pageName) {

        // 1. Known page whose file is present -> serve it.
        String safeName = ALLOWED_PAGES.get(pageName);
        if (safeName != null) {
            String body = readPage(safeName + ".html");
            if (body != null) {
                return html(body);
            }
            log.debug("Known page '{}' file missing; falling back to {}", safeName, fallbackFile);
        } else {
            log.debug("Unknown page '{}'; falling back to {}", pageName, fallbackFile);
        }

        // 2. Fallback file present -> serve it. (This is how you reach 501.html.)
        String fallbackBody = readPage(fallbackFile);
        if (fallbackBody != null) {
            return html(fallbackBody);
        }

        // 3. Fallback missing too -> emergency constant (never 500s).
        log.warn("Fallback file '{}' not found under {}; serving emergency HTML", fallbackFile, baseDir);
        return html(EMERGENCY_HTML);
    }

    /**
     * Reads one page file from baseDir. Returns null if it is missing,
     * unreadable, or would resolve outside baseDir. {@code filename} is always
     * a constant (allow-list value or configured fallback), never request data.
     */
    private String readPage(String filename) {
        Path filePath = baseDir.resolve(filename).normalize();

        if (!filePath.startsWith(baseDir)) {
            log.warn("Resolved path escaped base dir: {}", filePath);
            return null;
        }
        try {
            if (Files.isRegularFile(filePath) && Files.isReadable(filePath)) {
                return Files.readString(filePath, StandardCharsets.UTF_8);
            }
            log.debug("Page file not found/readable: {}", filePath);
        } catch (IOException ex) {
            log.warn("Failed reading {}: {}", filePath, ex.getMessage());
        }
        return null;
    }

    private ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }
}
