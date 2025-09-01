package dev.apiguard;

import static dev.apiguard.Models.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final PlatformStore store;
  private final SpecEngine specs;
  private final SuiteService suites;
  private final RunService runs;
  private final ImpactService impact;

  public ApiController(
      PlatformStore store,
      SpecEngine specs,
      SuiteService suites,
      RunService runs,
      ImpactService impact) {
    this.store = store;
    this.specs = specs;
    this.suites = suites;
    this.runs = runs;
    this.impact = impact;
  }

  public record ImportRequest(
      @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 2_000_000) String content) {}

  public record SuiteRequest(@NotBlank String specId, @NotBlank @Size(max = 160) String name) {}

  public record RunRequest(
      @NotBlank String suiteId,
      @NotBlank String baseUrl,
      Integer parallelism,
      Integer timeoutMs,
      List<String> testIds,
      String compareToSpecId) {}

  @PostMapping("/specs")
  @ResponseStatus(HttpStatus.CREATED)
  public Specification importSpec(@Valid @RequestBody ImportRequest request) {
    return specs.importSpec(request.name(), request.content());
  }

  @PostMapping(value = "/specs/upload", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  public Specification upload(@RequestParam String name, @RequestParam MultipartFile file)
      throws IOException {
    return specs.importSpec(name, new String(file.getBytes(), StandardCharsets.UTF_8));
  }

  @GetMapping("/specs")
  public List<SpecSummary> specifications() {
    return store.specs().stream()
        .map(
            s ->
                new SpecSummary(
                    s.id(),
                    s.name(),
                    s.version(),
                    s.createdAt(),
                    specs.operations(s.document()).size()))
        .toList();
  }

  @GetMapping("/specs/{id}")
  public Specification specification(@PathVariable String id) {
    return store.spec(id);
  }

  @PostMapping("/suites")
  @ResponseStatus(HttpStatus.CREATED)
  public Suite generate(@Valid @RequestBody SuiteRequest request) {
    return suites.generate(request.specId(), request.name());
  }

  @GetMapping("/suites")
  public List<Suite> suites() {
    return store.suites();
  }

  @GetMapping("/suites/{id}")
  public Suite suite(@PathVariable String id) {
    return store.suite(id);
  }

  @PutMapping("/suites/{suiteId}/tests/{testId}")
  public Suite configure(
      @PathVariable String suiteId, @PathVariable String testId, @RequestBody TestConfig config) {
    return suites.configure(suiteId, testId, config);
  }

  @PostMapping("/runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Run start(@Valid @RequestBody RunRequest request) {
    return runs.start(
        request.suiteId(),
        request.baseUrl(),
        request.parallelism() == null ? 4 : request.parallelism(),
        request.timeoutMs() == null ? 5000 : request.timeoutMs(),
        request.testIds(),
        request.compareToSpecId());
  }

  @GetMapping("/runs")
  public List<Run> history(
      @RequestParam(required = false) String suiteId,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 200) throw ApiException.badRequest("limit must be between 1 and 200");
    return store.runs(suiteId, limit);
  }

  @GetMapping("/runs/{id}")
  public RunDetails details(@PathVariable String id) {
    return runs.details(id);
  }

  @GetMapping("/changes")
  public Impact compare(
      @RequestParam String oldSpecId,
      @RequestParam String newSpecId,
      @RequestParam(required = false) String suiteId) {
    return impact.compare(oldSpecId, newSpecId, suiteId);
  }
}
