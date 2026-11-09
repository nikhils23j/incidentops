package dev.nikhil.incidentops.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig implements WebMvcConfigurer {
    /** Everything time-related reads this Clock, so tests can fast-forward SLA deadlines. */
    @Bean
    public Clock clock() { return Clock.systemUTC(); }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:5173", "http://localhost:4173")
            .allowedMethods("GET", "POST", "PATCH", "OPTIONS");
    }
}
