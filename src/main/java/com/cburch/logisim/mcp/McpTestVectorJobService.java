/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

/** Creates isolated project snapshots and evaluates test vectors as cancellable MCP jobs. */
final class McpTestVectorJobService implements AutoCloseable {
  private static final int MAX_FAILURE_DETAILS = 100;
  private final McpJobManager jobs = new McpJobManager();

  JsonObject start(
      Project source,
      Circuit sourceCircuit,
      McpProjectRegistry registry,
      long revision,
      String vectorPath)
      throws Exception {
    final var vectorFile = validateVectorPath(vectorPath);
    final var projectId = registry.projectId(source);
    final var circuitId = registry.circuitId(sourceCircuit);
    final var circuitName = sourceCircuit.getName();
    final var snapshot = cloneProject(source);
    final var job =
        jobs.submit(
            "test_vector",
            projectId,
            circuitId,
            revision,
            handle -> runVector(handle, snapshot, circuitName, vectorFile));
    return job.toJson();
  }

  /** File-accepting overload that skips internal path validation; path policy must be enforced by the caller. */
  JsonObject start(
      Project source,
      Circuit sourceCircuit,
      McpProjectRegistry registry,
      long revision,
      File vectorFile)
      throws Exception {
    final var projectId = registry.projectId(source);
    final var circuitId = registry.circuitId(sourceCircuit);
    final var circuitName = sourceCircuit.getName();
    final var snapshot = cloneProject(source);
    final var job =
        jobs.submit(
            "test_vector",
            projectId,
            circuitId,
            revision,
            handle -> runVector(handle, snapshot, circuitName, vectorFile));
    return job.toJson();
  }

  JsonObject get(String jobId) {
    final var job = jobs.get(jobId);
    return job == null ? null : job.toJson();
  }

  JsonObject list(String projectId) {
    final var result = new JsonObject();
    final var values = new JsonArray();
    for (final var job : jobs.list(projectId)) values.add(job.toJson());
    result.add("jobs", values);
    result.addProperty("count", values.size());
    return result;
  }

  boolean cancel(String jobId) {
    return jobs.cancel(jobId);
  }

  boolean remove(String jobId) {
    return jobs.remove(jobId);
  }

  @Override
  public void close() {
    jobs.close();
  }

  private static JsonObject runVector(
      McpJobManager.Job job, LogisimFile snapshot, String circuitName, File vectorFile)
      throws Exception {
    final var project = new Project(snapshot);
    for (final var circuit : snapshot.getCircuits()) circuit.setProject(project);
    final var circuit = snapshot.getCircuit(circuitName);
    if (circuit == null) throw new IllegalStateException("Circuit disappeared from project snapshot");
    project.setCurrentCircuit(circuit);

    final var vector = new TestVector(vectorFile);
    final var total = vector.data.size();
    final var failures = new JsonArray();
    final var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
    final var evaluator = new TestVectorEvaluator(state, vector);
    job.progress(0.0, "Running " + total + " vector rows");
    final var passFail =
        evaluator.evaluate(
            (row, report) -> {
              if (job.cancelRequested()) {
                evaluator.setCanceled(true);
                return;
              }
              if (report != null && !report.isEmpty() && failures.size() < MAX_FAILURE_DETAILS) {
                final var rowResult = new JsonObject();
                rowResult.addProperty("row", row + 1);
                final var messages = new JsonArray();
                for (final var item : report) messages.add(item.toString());
                rowResult.add("failures", messages);
                failures.add(rowResult);
              }
              job.progress(
                  total == 0 ? 1.0 : (double) (row + 1) / total,
                  "Processed row " + (row + 1) + " of " + total);
            });
    if (job.cancelRequested()) throw new CancellationException("Test-vector job was canceled");

    final var result = new JsonObject();
    result.addProperty("vectorPath", vectorFile.getPath());
    result.addProperty("circuitName", circuitName);
    result.addProperty("rows", total);
    result.addProperty("passed", passFail[0]);
    result.addProperty("failed", passFail[1]);
    result.addProperty("failureDetailsTruncated", passFail[1] > failures.size());
    result.add("failureDetails", failures);
    project.getSimulator().shutDown();
    return result;
  }

  private static LogisimFile cloneProject(Project source) throws IOException {
    final var cloneLoader = new Loader(null);
    final var clone = source.getLogisimFile().cloneLogisimFile(cloneLoader);
    if (clone == null) throw new IOException("Unable to create an isolated project snapshot");
    return clone;
  }

  private static File validateVectorPath(String rawPath)
      throws McpJsonRpcDispatcher.McpRpcException {
    final Path path;
    try {
      path = Path.of(rawPath);
    } catch (InvalidPathException e) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, "vectorPath is invalid");
    }
    if (!path.isAbsolute()) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, "vectorPath must be absolute");
    }
    final File canonical;
    try {
      canonical = path.normalize().toFile().getCanonicalFile();
    } catch (IOException e) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, "vectorPath cannot be resolved");
    }
    if (!Files.isRegularFile(canonical.toPath()) || !Files.isReadable(canonical.toPath())) {
      throw new McpJsonRpcDispatcher.McpRpcException(
          -32602, "vectorPath must name a readable test-vector file");
    }
    if (!canonical.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, "vectorPath must end with .txt");
    }
    return canonical;
  }
}
