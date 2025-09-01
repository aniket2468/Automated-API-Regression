package dev.apiguard;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** A deterministic local target, available only with --spring.profiles.active=demo. */
@Profile("demo")
@RestController
public class DemoController {
  @GetMapping("/demo-api/products")
  public ResponseEntity<?> products() {
    return ResponseEntity.ok()
        .header("X-API-Version", "1")
        .body(List.of(Map.of("id", 1, "name", "Mechanical keyboard", "price", 89.5)));
  }

  @GetMapping("/demo-api/products/{id}")
  public ResponseEntity<?> product(@PathVariable int id) {
    return ResponseEntity.ok()
        .header("X-API-Version", "1")
        .body(Map.of("id", id, "name", "Mechanical keyboard", "price", 89.5));
  }

  @PostMapping("/demo-api/products")
  public ResponseEntity<?> create(@RequestBody Map<String, Object> input) {
    return ResponseEntity.status(201)
        .body(
            Map.of(
                "id",
                2,
                "name",
                input.getOrDefault("name", "New product"),
                "price",
                input.getOrDefault("price", 10)));
  }

  @GetMapping("/demo-v2/products")
  public ResponseEntity<?> changedProducts() {
    return ResponseEntity.ok()
        .header("X-API-Version", "2")
        .body(List.of(Map.of("id", 1, "title", "Mechanical keyboard", "price", 89.5)));
  }

  @GetMapping("/demo-v2/products/{id}")
  public ResponseEntity<?> changedProduct(@PathVariable int id) {
    return ResponseEntity.ok()
        .header("X-API-Version", "2")
        .body(Map.of("id", id, "title", "Mechanical keyboard", "price", 89.5));
  }

  @GetMapping("/demo-v2/health")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
