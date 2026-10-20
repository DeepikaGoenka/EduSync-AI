package com.edusync.edusync;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the frontend (running on another port, e.g. React on 3000 / Vite on 5173) call this backend
 * from the browser. Without it the browser blocks the requests with a "CORS" error.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:5173",
                        "http://localhost:4200",
                        "http://127.0.0.1:5500")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}