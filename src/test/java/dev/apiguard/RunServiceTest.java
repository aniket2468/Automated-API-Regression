package dev.apiguard;

import static dev.apiguard.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RunServiceTest {
  static class Harness implements AutoCloseable {
    final Map<String, Run> runs = new ConcurrentHashMap<>();
    final Map<String, List<TestResult>> results = new ConcurrentHashMap<>();
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    final RunService service;

    Harness(int workers, int coordinators, int queue) {
      PlatformStore store = mock(PlatformStore.class);
      RegressionExecutor executor = mock(RegressionExecutor.class);
      PlatformProperties props =
          new PlatformProperties(List.of("localhost"), workers, coordinators, queue);
      TestConfig config =
          new TestConfig(true, "", Map.of(), Map.of(), Map.of(), null, Map.of(), List.of(), 1000);
      List<TestCase> tests =
          IntStream.range(0, 6)
              .mapToObj(
                  i ->
                      new TestCase(
                          "test-" + i,
                          "GET /" + i,
                          "GET",
                          "/" + i,
                          200,
                          null,
                          null,
                          List.of(),
                          config))
              .toList();
      Suite suite = new Suite("suite", "Parallel suite", "spec", Instant.now(), tests);
      when(store.suite("suite")).thenReturn(suite);
      doAnswer(
              call -> {
                Run run = call.getArgument(0);
                runs.put(run.id(), run);
                results.put(run.id(), new CopyOnWriteArrayList<>());
                return null;
              })
          .when(store)
          .saveRun(any());
      doAnswer(
              call -> {
                Run run = call.getArgument(0);
                runs.put(run.id(), run);
                return null;
              })
          .when(store)
          .updateRun(any());
      doAnswer(
              call -> {
                results.get(call.<String>getArgument(0)).add(call.getArgument(1));
                return null;
              })
          .when(store)
          .saveResult(anyString(), any());
      when(store.results(anyString())).thenAnswer(call -> results.get(call.<String>getArgument(0)));
      when(executor.execute(any(), anyString(), anyInt()))
          .thenAnswer(
              call -> {
                TestCase test = call.getArgument(0);
                int count = active.incrementAndGet();
                peak.accumulateAndGet(count, Math::max);
                try {
                  if (!release.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Test gate timed out");
                  Thread.sleep(15);
                  return new TestResult(
                      test.id(), test.operation(), "PASSED", 200, 15, List.of(), null);
                } finally {
                  active.decrementAndGet();
                }
              });
      service =
          new RunService(
              store, executor, new TargetPolicy(props), mock(ImpactService.class), props);
    }

    Run start(int parallelism) {
      return service.start("suite", "http://localhost", parallelism, 1000, null, null);
    }

    void finished(Run run) {
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> runs.get(run.id()).status().equals("PASSED"));
      assertThat(results.get(run.id())).hasSize(6);
    }

    @Override
    public void close() {
      release.countDown();
      service.shutdown();
    }
  }

  @Test
  void enforcesPerRunParallelismAndCompletesEverySelectedTest() {
    try (Harness h = new Harness(4, 1, 2)) {
      Run run = h.start(2);
      await().atMost(Duration.ofSeconds(5)).until(() -> h.active.get() == 2);
      h.release.countDown();
      h.finished(run);
      assertThat(h.peak.get()).isEqualTo(2);
    }
  }

  @Test
  void sharesGlobalWorkerLimitAcrossRuns() {
    try (Harness h = new Harness(3, 2, 2)) {
      Run first = h.start(2), second = h.start(2);
      await().atMost(Duration.ofSeconds(5)).until(() -> h.active.get() == 3);
      h.release.countDown();
      h.finished(first);
      h.finished(second);
      assertThat(h.peak.get()).isEqualTo(3);
    }
  }

  @Test
  void rejectsQueueOverflowWithoutLosingAcceptedRuns() {
    try (Harness h = new Harness(1, 1, 1)) {
      Run first = h.start(1);
      await().atMost(Duration.ofSeconds(5)).until(() -> h.active.get() == 1);
      Run queued = h.start(1);
      assertThatThrownBy(() -> h.start(1))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
      assertThat(h.runs.values())
          .anyMatch(r -> r.status().equals("ERROR") && r.error().equals("Run queue is full"));
      h.release.countDown();
      h.finished(first);
      h.finished(queued);
    }
  }
}
