package dev.apiguard;

import static dev.apiguard.Models.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RunService {
  private static final Logger log = LoggerFactory.getLogger(RunService.class);
  private final PlatformStore store;
  private final RegressionExecutor executor;
  private final TargetPolicy targets;
  private final ImpactService impact;
  private final PlatformProperties properties;
  private final ThreadPoolExecutor coordinators;
  private final ExecutorService workers;

  public RunService(
      PlatformStore store,
      RegressionExecutor executor,
      TargetPolicy targets,
      ImpactService impact,
      PlatformProperties properties) {
    this.store = store;
    this.executor = executor;
    this.targets = targets;
    this.impact = impact;
    this.properties = properties;
    coordinators =
        new ThreadPoolExecutor(
            properties.maxConcurrentRuns(),
            properties.maxConcurrentRuns(),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(properties.queueCapacity()),
            new ThreadPoolExecutor.AbortPolicy());
    workers = Executors.newFixedThreadPool(properties.workers());
  }

  @PostConstruct
  void recover() {
    for (Run run : store.unfinishedRuns())
      finish(run, "INTERRUPTED", "Application stopped before this run completed");
  }

  public Run start(
      String suiteId,
      String baseUrl,
      int parallelism,
      int timeoutMs,
      List<String> testIds,
      String compareToSpecId) {
    if (parallelism < 1 || parallelism > properties.workers())
      throw ApiException.badRequest("parallelism must be between 1 and " + properties.workers());
    if (timeoutMs < 100 || timeoutMs > 120_000)
      throw ApiException.badRequest("timeoutMs must be between 100 and 120000");
    String target = targets.validate(baseUrl);
    Suite suite = store.suite(suiteId);
    if (testIds != null && compareToSpecId != null)
      throw ApiException.badRequest("Use either testIds or compareToSpecId, not both");
    List<String> selectedIds =
        compareToSpecId == null
            ? testIds
            : impact.compare(suite.specId(), compareToSpecId, suite.id()).affectedTestIds();
    Set<String> known = new HashSet<>();
    suite.tests().forEach(t -> known.add(t.id()));
    if (selectedIds != null && !known.containsAll(selectedIds))
      throw ApiException.badRequest("testIds contains an unknown test");
    List<TestCase> selected =
        suite.tests().stream()
            .filter(t -> selectedIds == null || selectedIds.contains(t.id()))
            .toList();
    if (selected.isEmpty())
      throw ApiException.badRequest(
          "No tests selected; the comparison may contain no affected existing tests");
    Run run =
        new Run(
            UUID.randomUUID().toString(),
            suite.id(),
            suite.name(),
            suite.specId(),
            target,
            "QUEUED",
            Instant.now(),
            null,
            selected.size(),
            0,
            0,
            0,
            null);
    store.saveRun(run);
    try {
      coordinators.execute(() -> execute(run, selected, parallelism, timeoutMs));
    } catch (RejectedExecutionException e) {
      finish(run, "ERROR", "Run queue is full");
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Run queue is full; try again later");
    }
    return run;
  }

  private void execute(Run run, List<TestCase> tests, int parallelism, int timeoutMs) {
    List<Future<TestResult>> pending = new ArrayList<>();
    try {
      store.updateRun(
          new Run(
              run.id(),
              run.suiteId(),
              run.suiteName(),
              run.specId(),
              run.baseUrl(),
              "RUNNING",
              run.createdAt(),
              null,
              run.total(),
              0,
              0,
              0,
              null));
      CompletionService<TestResult> completion = new ExecutorCompletionService<>(workers);
      int submitted = 0, completed = 0;
      while (submitted < Math.min(parallelism, tests.size())) {
        TestCase t = tests.get(submitted++);
        pending.add(completion.submit(() -> executor.execute(t, run.baseUrl(), timeoutMs)));
      }
      while (completed < tests.size()) {
        store.saveResult(run.id(), completion.take().get());
        completed++;
        if (submitted < tests.size()) {
          TestCase t = tests.get(submitted++);
          pending.add(completion.submit(() -> executor.execute(t, run.baseUrl(), timeoutMs)));
        }
      }
      finish(run, null, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finish(run, "INTERRUPTED", "Execution interrupted");
    } catch (Exception e) {
      log.error("Run {} failed", run.id(), e);
      finish(run, "ERROR", "Runner failed: " + e.getClass().getSimpleName());
    } finally {
      pending.forEach(
          f -> {
            if (!f.isDone()) f.cancel(true);
          });
    }
  }

  private void finish(Run run, String forcedStatus, String error) {
    List<TestResult> results = store.results(run.id());
    int passed = (int) results.stream().filter(r -> r.status().equals("PASSED")).count();
    int skipped = (int) results.stream().filter(r -> r.status().equals("SKIPPED")).count();
    int failed = results.size() - passed - skipped;
    String status =
        forcedStatus != null
            ? forcedStatus
            : failed > 0 ? "FAILED" : skipped == run.total() ? "SKIPPED" : "PASSED";
    store.updateRun(
        new Run(
            run.id(),
            run.suiteId(),
            run.suiteName(),
            run.specId(),
            run.baseUrl(),
            status,
            run.createdAt(),
            Instant.now(),
            run.total(),
            passed,
            failed,
            skipped,
            error));
  }

  public RunDetails details(String id) {
    return new RunDetails(store.run(id), store.results(id));
  }

  @PreDestroy
  void shutdown() {
    coordinators.shutdown();
    try {
      if (!coordinators.awaitTermination(5, TimeUnit.SECONDS)) coordinators.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      coordinators.shutdownNow();
    }
    workers.shutdownNow();
  }
}
