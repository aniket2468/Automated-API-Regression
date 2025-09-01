package dev.apiguard;

import static dev.apiguard.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:platformtest;DB_CLOSE_DELAY=-1",
      "server.address=127.0.0.1"
    })
@ActiveProfiles("demo")
class PlatformIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired SpecEngine specs;
  @Autowired SuiteService suites;
  @Autowired RunService runs;
  @Autowired PlatformStore store;
  @Autowired ImpactService impact;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  Specification importSample(String file) throws Exception {
    var response =
        http.postForEntity(
            "/api/specs",
            new ApiController.ImportRequest("Catalog", Files.readString(Path.of("examples", file))),
            Specification.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }

  Suite generated(Specification spec) {
    var response =
        http.postForEntity(
            "/api/suites",
            new ApiController.SuiteRequest(spec.id(), "Catalog regression"),
            Suite.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Suite suite = response.getBody();
    // Include cold JVM/client startup without weakening the dedicated latency tests.
    for (TestCase t : suite.tests()) {
      var c = t.config();
      suites.configure(
          suite.id(),
          t.id(),
          new TestConfig(
              c.enabled(),
              c.disabledReason(),
              c.pathParams(),
              c.queryParams(),
              c.requestHeaders(),
              c.requestBody(),
              c.expectedHeaders(),
              c.assertions(),
              10000));
    }
    return store.suite(suite.id());
  }

  RunDetails waitFor(String id) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> !List.of("QUEUED", "RUNNING").contains(store.run(id).status()));
    return runs.details(id);
  }

  @Test
  void importsGeneratesExecutesAndRendersReports() throws Exception {
    Specification spec = importSample("catalog-v1.yaml");
    Suite suite = generated(spec);
    assertThat(suite.tests()).hasSize(3);
    assertThat(suite.tests())
        .filteredOn(t -> !t.config().enabled())
        .extracting(TestCase::method)
        .containsExactly("POST");
    var response =
        http.postForEntity(
            "/api/runs",
            new ApiController.RunRequest(
                suite.id(), "http://localhost:" + port + "/demo-api", 2, 5000, null, null),
            Run.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    RunDetails result = waitFor(response.getBody().id());
    assertThat(result.run().status()).isEqualTo("PASSED");
    assertThat(result.run().passed()).isEqualTo(2);
    assertThat(result.run().skipped()).isEqualTo(1);
    assertThat(result.results())
        .filteredOn(r -> r.status().equals("PASSED"))
        .allSatisfy(
            r -> {
              assertThat(r.checks())
                  .extracting(Check::name)
                  .contains("status", "schema", "latency", "content-type", "header:X-API-Version");
            });
    var report = http.getForEntity("/reports/" + result.run().id(), String.class);
    assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(report.getBody())
        .contains(
            "Recent suite history",
            "Catalog regression",
            "Response matches the OpenAPI response schema");
    assertThat(http.getForObject("/api/runs?suiteId=" + suite.id(), String.class))
        .contains(result.run().id());
    assertThat(http.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void detectsTransitiveSchemaChangesAndRunsAffectedBaselineTests() throws Exception {
    var oldSpec = importSample("catalog-v1.yaml");
    var candidate = importSample("catalog-v2.yaml");
    var suite = generated(oldSpec);
    Impact diff = impact.compare(oldSpec.id(), candidate.id(), suite.id());
    assertThat(diff.affectedTestIds()).hasSize(3);
    assertThat(diff.addedOperations()).containsExactly("GET /health");
    assertThat(diff.changes())
        .anySatisfy(
            c -> {
              assertThat(c.operation()).isEqualTo("GET /products/{id}");
              assertThat(c.reasons()).contains("Dependency changed: #/components/schemas/Product");
            });
    assertThat(diff.changes())
        .anySatisfy(
            c -> {
              assertThat(c.operation()).isEqualTo("POST /products");
              assertThat(c.kind()).isEqualTo("REMOVED");
            });
    Run run =
        runs.start(
            suite.id(), "http://localhost:" + port + "/demo-v2", 2, 5000, null, candidate.id());
    RunDetails result = waitFor(run.id());
    assertThat(result.run().status()).isEqualTo("FAILED");
    assertThat(result.run().failed()).isEqualTo(2);
    assertThat(result.results())
        .filteredOn(r -> r.status().equals("FAILED"))
        .allSatisfy(
            r -> assertThat(r.checks()).anyMatch(c -> c.name().equals("schema") && !c.passed()));
  }

  @Test
  void savesConfigurationAndAppliesDataAndHeaderAssertions() throws Exception {
    Suite suite = generated(importSample("catalog-v1.yaml"));
    TestCase t =
        suite.tests().stream()
            .filter(c -> c.operation().equals("GET /products/{id}"))
            .findFirst()
            .orElseThrow();
    var c = t.config();
    var config =
        new TestConfig(
            true,
            "",
            c.pathParams(),
            c.queryParams(),
            c.requestHeaders(),
            null,
            Map.of("X-API-Version", "1"),
            List.of(new Assertion("/name", json.readTree("\"Mechanical keyboard\""))),
            10000);
    var response =
        http.exchange(
            "/api/suites/" + suite.id() + "/tests/" + t.id(),
            HttpMethod.PUT,
            new HttpEntity<>(config),
            Suite.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    RunDetails result =
        waitFor(
            runs.start(
                    suite.id(),
                    "http://localhost:" + port + "/demo-api",
                    1,
                    5000,
                    List.of(t.id()),
                    null)
                .id());
    assertThat(result.run().passed()).isEqualTo(1);
    assertThat(result.results().get(0).checks())
        .extracting(Check::name)
        .contains("data:/name", "header-value:X-API-Version");
  }

  @Test
  void explicitlyEnabledWriteOperationExecutes() throws Exception {
    Suite suite = generated(importSample("catalog-v1.yaml"));
    TestCase t =
        suite.tests().stream().filter(c -> c.method().equals("POST")).findFirst().orElseThrow();
    var c = t.config();
    suites.configure(
        suite.id(),
        t.id(),
        new TestConfig(
            true,
            "",
            c.pathParams(),
            c.queryParams(),
            c.requestHeaders(),
            c.requestBody(),
            c.expectedHeaders(),
            c.assertions(),
            10000));
    var result =
        waitFor(
            runs.start(
                    suite.id(),
                    "http://localhost:" + port + "/demo-api",
                    1,
                    5000,
                    List.of(t.id()),
                    null)
                .id());
    assertThat(result.run().status()).isEqualTo("PASSED");
    assertThat(result.results().get(0).actualStatus()).isEqualTo(201);
  }

  @Test
  void unchangedSpecHasNoImpactAndEmptySelectionDoesNotRunEverything() throws Exception {
    var original = importSample("catalog-v1.yaml");
    var identical = importSample("catalog-v1.yaml");
    var suite = generated(original);
    assertThat(impact.compare(original.id(), identical.id(), suite.id()).changes()).isEmpty();
    assertThatThrownBy(
            () -> runs.start(suite.id(), "http://localhost:" + port, 1, 1000, null, identical.id()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("No tests selected");
    assertThatThrownBy(
            () -> runs.start(suite.id(), "http://localhost:" + port, 1, 1000, List.of(), null))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("No tests selected");
  }

  @Test
  void rejectsInvalidInputAndDisallowedTargets() throws Exception {
    assertThat(
            http.postForEntity(
                    "/api/specs", Map.of("name", "Broken", "content", "not a spec"), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(http.getForEntity("/api/specs/missing", String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(http.getForEntity("/api/runs?limit=0", String.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(http.postForEntity("/api/runs", Map.of(), String.class).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    var suite = generated(importSample("catalog-v1.yaml"));
    assertThatThrownBy(() -> runs.start(suite.id(), "https://example.com", 1, 500, null, null))
        .hasMessageContaining("API_ALLOWED_HOSTS");
    assertThatThrownBy(() -> runs.start(suite.id(), "http://localhost", 9, 500, null, null))
        .hasMessageContaining("parallelism");
    assertThatThrownBy(
            () -> runs.start(suite.id(), "http://localhost", 1, 500, List.of("missing"), null))
        .hasMessageContaining("unknown test");
  }

  @Test
  void rejectsExternalReferencesAndUnresolvedLocalReferences() throws Exception {
    String source = Files.readString(Path.of("examples/catalog-v1.yaml"));
    assertThatThrownBy(
            () ->
                specs.importSpec(
                    "External",
                    source.replace(
                        "#/components/schemas/Product", "https://example.com/schema.json")))
        .hasMessageContaining("Only local $ref");
    assertThatThrownBy(
            () ->
                specs.importSpec(
                    "Missing",
                    source.replace("#/components/schemas/Product", "#/components/schemas/Missing")))
        .hasMessageContaining("Unresolved reference");
    assertThatThrownBy(() -> specs.importSpec("Wrong version", source.replace("3.0.3", "3.1.0")))
        .hasMessageContaining("Only OpenAPI 3.0");
  }

  @Test
  void unrelatedComponentChangesDoNotAffectTests() throws Exception {
    var original = importSample("catalog-v1.yaml");
    var changed = original.document().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("components").path("schemas"))
        .putObject("Unused")
        .put("type", "string");
    var candidate = specs.importSpec("With unused schema", changed.toString());
    assertThat(impact.compare(original.id(), candidate.id(), null).changes()).isEmpty();
  }

  @Test
  void reportEscapesUserControlledSuiteNames() throws Exception {
    var spec = importSample("catalog-v1.yaml");
    var suite = suites.generate(spec.id(), "<script>alert('x')</script>");
    String post =
        suite.tests().stream()
            .filter(t -> t.method().equals("POST"))
            .findFirst()
            .orElseThrow()
            .id();
    var result =
        waitFor(
            runs.start(
                    suite.id(),
                    "http://localhost:" + port + "/demo-api",
                    1,
                    1000,
                    List.of(post),
                    null)
                .id());
    assertThat(result.run().status()).isEqualTo("SKIPPED");
    String html = http.getForObject("/reports/" + result.run().id(), String.class);
    assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>alert");
  }

  @Test
  void restartRecoveryMarksAbandonedRunsInterrupted() throws Exception {
    var suite = generated(importSample("catalog-v1.yaml"));
    var abandoned =
        new Run(
            java.util.UUID.randomUUID().toString(),
            suite.id(),
            suite.name(),
            suite.specId(),
            "http://localhost",
            "RUNNING",
            java.time.Instant.now(),
            null,
            3,
            0,
            0,
            0,
            null);
    store.saveRun(abandoned);
    runs.recover();
    assertThat(store.run(abandoned.id()).status()).isEqualTo("INTERRUPTED");
  }
}
