package dev.apiguard;

import static dev.apiguard.Models.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RegressionExecutor {
  private final ObjectMapper json;
  private final JsonSchemaFactory schemas =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

  public RegressionExecutor(ObjectMapper json) {
    this.json = json;
  }

  public TestResult execute(TestCase test, String baseUrl, int timeoutMs) {
    if (!test.config().enabled())
      return new TestResult(
          test.id(),
          test.operation(),
          "SKIPPED",
          null,
          0,
          List.of(),
          test.config().disabledReason());
    List<Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    try {
      // No shared RestAssured static state: each parallel test owns its request and configuration.
      var client =
          HttpClientConfig.httpClientConfig()
              .setParam("http.connection.timeout", timeoutMs)
              .setParam("http.socket.timeout", timeoutMs)
              .setParam("http.connection-manager.timeout", (long) timeoutMs);
      var config =
          RestAssuredConfig.config()
              .httpClient(client)
              .redirect(RedirectConfig.redirectConfig().followRedirects(false));
      var builder =
          new RequestSpecBuilder()
              .setBaseUri(baseUrl)
              .setConfig(config)
              .addPathParams(test.config().pathParams())
              .addQueryParams(test.config().queryParams())
              .addHeaders(test.config().requestHeaders());
      if (test.responseMediaType() != null) builder.setAccept(test.responseMediaType());
      if (test.config().requestBody() != null) {
        if (test.config().requestHeaders().keySet().stream()
            .noneMatch(k -> k.equalsIgnoreCase("Content-Type")))
          builder.setContentType("application/json");
        builder.setBody(test.config().requestBody().toString());
      }
      Response response =
          io.restassured.RestAssured.given()
              .spec(builder.build())
              .request(test.method(), test.path());
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      checks.add(
          new Check(
              "status",
              response.statusCode() == test.expectedStatus(),
              "Expected " + test.expectedStatus() + ", received " + response.statusCode()));
      checks.add(
          new Check(
              "latency",
              elapsed <= test.config().maxLatencyMs(),
              elapsed + " ms; budget " + test.config().maxLatencyMs() + " ms"));
      if (test.responseMediaType() != null) {
        String actual =
            Optional.ofNullable(response.contentType()).orElse("").split(";", 2)[0].trim();
        checks.add(
            new Check(
                "content-type",
                test.responseMediaType().equalsIgnoreCase(actual),
                "Expected " + test.responseMediaType() + ", received " + actual));
      }
      for (String header : test.responseHeaders())
        checks.add(
            new Check(
                "header:" + header,
                response.header(header) != null,
                "Documented response header must be present"));
      test.config()
          .expectedHeaders()
          .forEach(
              (name, value) ->
                  checks.add(
                      new Check(
                          "header-value:" + name,
                          value.equals(response.header(name)),
                          "Configured header equality assertion")));
      if (test.responseSchema() != null || !test.config().assertions().isEmpty()) {
        try {
          JsonNode body = json.readTree(response.asByteArray());
          if (body == null) throw new IllegalArgumentException("Empty JSON response");
          if (test.responseSchema() != null) {
            var errors = schemas.getSchema(test.responseSchema()).validate(body);
            // Persist schema locations/types, not raw response bodies or credential values.
            String detail =
                errors.isEmpty()
                    ? "Response matches the OpenAPI response schema"
                    : errors.stream()
                        .sorted(
                            Comparator.comparing(
                                e -> e.getInstanceLocation().toString() + e.getType()))
                        .limit(20)
                        .map(e -> e.getInstanceLocation() + ": " + e.getType())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Schema mismatch");
            checks.add(new Check("schema", errors.isEmpty(), detail));
          }
          for (Assertion a : test.config().assertions())
            checks.add(
                new Check(
                    "data:" + a.pointer(),
                    a.expected().equals(body.at(a.pointer())),
                    "Configured JSON pointer equality assertion"));
        } catch (Exception e) {
          checks.add(
              new Check(
                  "json-validation",
                  false,
                  "Cannot parse JSON or evaluate response schema: "
                      + e.getClass().getSimpleName()));
        }
      }
      boolean passed = checks.stream().allMatch(Check::passed);
      return new TestResult(
          test.id(),
          test.operation(),
          passed ? "PASSED" : "FAILED",
          response.statusCode(),
          elapsed,
          List.copyOf(checks),
          null);
    } catch (Exception e) {
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      return new TestResult(
          test.id(),
          test.operation(),
          "ERROR",
          null,
          elapsed,
          List.copyOf(checks),
          "Request could not complete: "
              + e.getClass().getSimpleName()
              + ". Check target availability, timeout and configured inputs.");
    }
  }
}
