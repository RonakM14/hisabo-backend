package com.ledgerone.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final String[] origins;

  public WebConfiguration(@Value("${ledger.allowed-origins}") String origins) {
    this.origins =
        java.util.Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(origins)
        .allowedMethods("GET", "POST", "PUT", "DELETE")
        .allowedHeaders("Content-Type")
        .maxAge(3600);
  }
}
