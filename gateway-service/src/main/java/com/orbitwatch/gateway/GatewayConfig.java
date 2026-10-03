package com.orbitwatch.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GatewayConfig implements WebFluxConfigurer {

    @Bean
    WebClient.Builder webClientBuilder() { return WebClient.builder(); }

    /** Dev convenience: the Vite dev server (5173) talks to the gateway directly. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("http://localhost:5173").allowedMethods("GET");
        registry.addMapping("/graphql").allowedOrigins("http://localhost:5173").allowedMethods("GET", "POST");
    }
}
