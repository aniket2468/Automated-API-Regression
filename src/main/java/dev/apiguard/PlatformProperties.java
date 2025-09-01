package dev.apiguard;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform")
public record PlatformProperties(
    List<String> allowedHosts, int workers, int maxConcurrentRuns, int queueCapacity) {}
