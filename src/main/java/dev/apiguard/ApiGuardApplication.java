package dev.apiguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PlatformProperties.class)
public class ApiGuardApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApiGuardApplication.class, args);
  }
}
