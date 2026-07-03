package com.example.epmmformquery.config;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * V4 component (kept as-is from your existing project).
 * Serves the Flutter SPA bundle from the classpath with SPA fallback to
 * index.html for client-side routes (paths without file extensions).
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    private static final Set<String> EXT = Set.of(
            "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico",
            "dart", "json", "wasm", "woff", "woff2", "ttf", "otf",
            "mp3", "mp4", "webp"
    );

    @Value("${app.frontend.url-prefix:/gui_epmmFormQuery/web}")
    private String urlPrefix;

    @Value("${app.frontend.resource-location:classpath:/gui_epmmFormQuery/web/}")
    private String resourceLocation;

    @Value("${app.frontend.index-path:/gui_epmmFormQuery/web/index.html}")
    private String indexPath;

    @Value("${app.frontend.classpath-base:gui_epmmFormQuery/web}")
    private String classpathBase;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(urlPrefix + "/**")
                .addResourceLocations(resourceLocation)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        // 1. Direct lookup in resourceLocation
                        Resource r = location.createRelative(path);
                        if (r.exists() && r.isReadable()) return r;

                        // 2. Classpath fallback
                        Resource cp = new ClassPathResource(classpathBase + "/" + path);
                        if (cp.exists() && cp.isReadable()) return cp;

                        // 3. Asset path-stripping fallback
                        if (hasExt(path) && path.contains("/")) {
                            String[] segs = path.split("/");
                            for (int i = 1; i < segs.length; i++) {
                                String stripped = String.join("/",
                                        java.util.Arrays.copyOfRange(segs, i, segs.length));
                                Resource alt = new ClassPathResource(classpathBase + "/" + stripped);
                                if (alt.exists() && alt.isReadable()) return alt;
                            }
                            return null;   // genuine 404 for missing asset
                        }
                        if (hasExt(path)) {
                            return null;   // missing asset, no slashes
                        }

                        // 4. SPA route → index.html
                        return new ClassPathResource(classpathBase + "/index.html");
                    }
                });
    }

    private boolean hasExt(String path) {
        int slash = path.lastIndexOf('/');
        String tail = slash < 0 ? path : path.substring(slash + 1);
        int dot = tail.lastIndexOf('.');
        if (dot < 0 || dot == tail.length() - 1) return false;
        String ext = tail.substring(dot + 1).toLowerCase();
        return EXT.contains(ext);
    }
}
