package dev.apiguard;

import static dev.apiguard.Models.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class RegressionExecutorTest {
  static HttpServer server;
  static ExecutorService serverThreads;
  static String baseUrl;
  static AtomicInteger active = new AtomicInteger(),
      peak = new AtomicInteger(),
      redirected = new AtomicInteger();
  final ObjectMapper json = new ObjectMapper();
  final RegressionExecutor runner = new RegressionExecutor(json);

  @BeforeAll
  static void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverThreads = Executors.newCachedThreadPool();
    server.setExecutor(serverThreads);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          int concurrency = active.incrementAndGet();
          peak.accumulateAndGet(concurrency, Math::max);
          try {
            if (path.equals("/slow")) Thread.sleep(300);
            if (path.equals("/redirect")) {
              exchange.getResponseHeaders().set("Location", baseUrl + "/destination");
              exchange.sendResponseHeaders(302, -1);
              return;
            }
            if (path.equals("/destination")) redirected.incrementAndGet();
            String body =
                switch (path) {
                  case "/invalid" -> "not-json";
                  case "/schema" -> "{\"id\":\"wrong\"}";
                  case "/echo" ->
                      "{\"id\":1,\"token\":\""
                          + exchange.getRequestHeaders().getFirst("X-Test")
                          + "\"}";
                  default -> "{\"id\":1,\"name\":null}";
                };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (!path.equals("/headers")) exchange.getResponseHeaders().set("X-Version", "1");
            exchange.sendResponseHeaders(path.equals("/status") ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            active.decrementAndGet();
            exchange.close();
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
    serverThreads.shutdownNow();
  }

  TestCase test(String path, long budget) throws Exception {
    var config =
        new TestConfig(
            true,
            "",
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            Map.of("X-Version", "1"),
            List.of(new Assertion("/id", json.readTree("1"))),
            budget);
    return new TestCase(
        path,
        "GET " + path,
        "GET",
        path,
        200,
        "application/json",
        json.readTree(
            "{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"integer\"}}}"),
        List.of("X-Version"),
        config);
  }

  @Test
  void successfulResponsePassesAllChecks() throws Exception {
    var result = runner.execute(test("/ok", 10000), baseUrl, 2000);
    assertThat(result.status()).isEqualTo("PASSED");
    assertThat(result.checks()).allMatch(Check::passed);
  }

  @Test
  void statusFailureIsReported() throws Exception {
    assertThat(runner.execute(test("/status", 10000), baseUrl, 2000).checks())
        .anyMatch(c -> c.name().equals("status") && !c.passed());
  }

  @Test
  void schemaAndDataFailuresAreReportedSeparately() throws Exception {
    var result = runner.execute(test("/schema", 10000), baseUrl, 2000);
    assertThat(result.checks())
        .anyMatch(c -> c.name().equals("schema") && !c.passed())
        .anyMatch(c -> c.name().equals("data:/id") && !c.passed());
  }

  @Test
  void missingHeadersFailPresenceAndEquality() throws Exception {
    var result = runner.execute(test("/headers", 10000), baseUrl, 2000);
    assertThat(result.checks())
        .anyMatch(c -> c.name().equals("header:X-Version") && !c.passed())
        .anyMatch(c -> c.name().equals("header-value:X-Version") && !c.passed());
  }

  @Test
  void latencyBudgetCanFailAnOtherwiseValidResponse() throws Exception {
    var result = runner.execute(test("/slow", 10), baseUrl, 2000);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.checks()).anyMatch(c -> c.name().equals("latency") && !c.passed());
  }

  @Test
  void timeoutIsAnErrorAndDoesNotHang() throws Exception {
    var result = runner.execute(test("/slow", 10000), baseUrl, 100);
    assertThat(result.status()).isEqualTo("ERROR");
    assertThat(result.latencyMs()).isLessThan(3000);
  }

  @Test
  void invalidJsonFailsValidation() throws Exception {
    var result = runner.execute(test("/invalid", 10000), baseUrl, 2000);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.checks()).anyMatch(c -> c.name().equals("json-validation") && !c.passed());
  }

  @Test
  void redirectsAreNotFollowed() throws Exception {
    var result = runner.execute(test("/redirect", 10000), baseUrl, 2000);
    assertThat(result.actualStatus()).isEqualTo(302);
    assertThat(redirected.get()).isZero();
  }

  @Test
  void parallelRequestsKeepHeadersIsolated() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<TestResult>> tasks = new java.util.ArrayList<>();
      for (int i = 0; i < 12; i++) {
        String token = "request-" + i;
        var base = test("/echo", 10000);
        var c = base.config();
        var config =
            new TestConfig(
                true,
                "",
                Map.of(),
                Map.of(),
                Map.of("X-Test", token),
                null,
                c.expectedHeaders(),
                List.of(new Assertion("/token", json.getNodeFactory().textNode(token))),
                10000);
        var test =
            new TestCase(
                token,
                base.operation(),
                base.method(),
                base.path(),
                200,
                "application/json",
                base.responseSchema(),
                base.responseHeaders(),
                config);
        tasks.add(() -> runner.execute(test, baseUrl, 2000));
      }
      for (Future<TestResult> future : pool.invokeAll(tasks))
        assertThat(future.get().status()).isEqualTo("PASSED");
    } finally {
      pool.shutdownNow();
    }
  }
}
