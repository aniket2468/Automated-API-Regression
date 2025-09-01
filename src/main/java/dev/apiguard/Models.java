package dev.apiguard;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Models {
  private Models() {}

  public record Specification(
      String id,
      String name,
      String version,
      Instant createdAt,
      JsonNode document,
      List<String> warnings) {}

  public record SpecSummary(
      String id, String name, String version, Instant createdAt, int operations) {}

  public record Assertion(String pointer, JsonNode expected) {}

  public record TestConfig(
      boolean enabled,
      String disabledReason,
      Map<String, String> pathParams,
      Map<String, String> queryParams,
      Map<String, String> requestHeaders,
      JsonNode requestBody,
      Map<String, String> expectedHeaders,
      List<Assertion> assertions,
      long maxLatencyMs) {}

  public record TestCase(
      String id,
      String operation,
      String method,
      String path,
      int expectedStatus,
      String responseMediaType,
      JsonNode responseSchema,
      List<String> responseHeaders,
      TestConfig config) {}

  public record Suite(
      String id, String name, String specId, Instant createdAt, List<TestCase> tests) {}

  public record Check(String name, boolean passed, String detail) {}

  public record TestResult(
      String testId,
      String operation,
      String status,
      Integer actualStatus,
      long latencyMs,
      List<Check> checks,
      String error) {}

  public record Run(
      String id,
      String suiteId,
      String suiteName,
      String specId,
      String baseUrl,
      String status,
      Instant createdAt,
      Instant finishedAt,
      int total,
      int passed,
      int failed,
      int skipped,
      String error) {}

  public record RunDetails(Run run, List<TestResult> results) {}

  public record Change(String operation, String kind, List<String> reasons) {}

  public record Impact(
      String oldSpecId,
      String newSpecId,
      List<Change> changes,
      List<String> affectedTestIds,
      List<String> addedOperations) {}
}
