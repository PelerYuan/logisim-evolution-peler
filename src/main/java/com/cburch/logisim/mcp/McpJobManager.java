/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs a bounded number of MCP background jobs without blocking the Swing event thread. */
final class McpJobManager implements AutoCloseable {
  static final int MAX_JOBS = 128;
  static final int MAX_CONCURRENT_JOBS = 2;

  private final Object lock = new Object();
  private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
  private final ExecutorService executor =
      Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS, new JobThreadFactory());
  private boolean closed;

  Job submit(String type, String projectId, String circuitId, long revision, JobTask task) {
    final var job = new Job(type, projectId, circuitId, revision);
    synchronized (lock) {
      if (closed) throw new IllegalStateException("MCP job manager is closed");
      removeExpiredJobs();
      if (jobs.size() >= MAX_JOBS) {
        throw new IllegalStateException("MCP job limit reached; clear completed jobs and retry");
      }
      jobs.put(job.id, job);
      job.future = executor.submit(() -> execute(job, task));
    }
    return job;
  }

  Job get(String jobId) {
    synchronized (lock) {
      removeExpiredJobs();
      return jobs.get(jobId);
    }
  }

  List<Job> list(String projectId) {
    synchronized (lock) {
      removeExpiredJobs();
      final var result = new ArrayList<Job>();
      for (final var job : jobs.values()) {
        if (projectId == null || projectId.equals(job.projectId)) result.add(job);
      }
      return List.copyOf(result);
    }
  }

  boolean cancel(String jobId) {
    final Future<?> future;
    synchronized (lock) {
      removeExpiredJobs();
      final var job = jobs.get(jobId);
      if (job == null || job.terminal()) return false;
      job.cancelRequested.set(true);
      job.updatedAt = Instant.now();
      future = job.future;
    }
    if (future != null) future.cancel(true);
    return true;
  }

  boolean remove(String jobId) {
    synchronized (lock) {
      final var job = jobs.get(jobId);
      if (job == null || !job.terminal()) return false;
      jobs.remove(jobId);
      return true;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      for (final var job : jobs.values()) {
        if (!job.terminal()) job.cancelRequested.set(true);
        if (job.future != null) job.future.cancel(true);
      }
      jobs.clear();
    }
    executor.shutdownNow();
  }

  private void execute(Job job, JobTask task) {
    synchronized (lock) {
      if (job.cancelRequested.get()) {
        finishCanceled(job);
        return;
      }
      job.status = Status.RUNNING;
      job.startedAt = Instant.now();
      job.updatedAt = job.startedAt;
    }
    try {
      final var result = task.execute(job);
      synchronized (lock) {
        if (job.cancelRequested.get() || Thread.currentThread().isInterrupted()) {
          finishCanceled(job);
        } else {
          job.result = result == null ? new JsonObject() : result.deepCopy();
          job.progress = 1.0;
          job.status = Status.SUCCEEDED;
          job.finishedAt = Instant.now();
          job.updatedAt = job.finishedAt;
        }
      }
    } catch (CancellationException | InterruptedException e) {
      Thread.currentThread().interrupt();
      synchronized (lock) {
        finishCanceled(job);
      }
    } catch (Exception e) {
      synchronized (lock) {
        if (job.cancelRequested.get()) {
          finishCanceled(job);
        } else {
          job.status = Status.FAILED;
          job.error = safeMessage(e);
          job.finishedAt = Instant.now();
          job.updatedAt = job.finishedAt;
        }
      }
    }
  }

  private void finishCanceled(Job job) {
    job.status = Status.CANCELED;
    job.finishedAt = Instant.now();
    job.updatedAt = job.finishedAt;
  }

  private void removeExpiredJobs() {
    while (jobs.size() >= MAX_JOBS) {
      final var iterator = jobs.entrySet().iterator();
      var removed = false;
      while (iterator.hasNext()) {
        if (iterator.next().getValue().terminal()) {
          iterator.remove();
          removed = true;
          break;
        }
      }
      if (!removed) return;
    }
  }

  private static String safeMessage(Exception exception) {
    final var message = exception.getMessage();
    return message == null || message.isBlank()
        ? exception.getClass().getSimpleName()
        : message;
  }

  enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED
  }

  static final class Job {
    private final String id = UUID.randomUUID().toString();
    private final String type;
    private final String projectId;
    private final String circuitId;
    private final long revision;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile Status status = Status.QUEUED;
    private volatile double progress;
    private volatile Instant startedAt;
    private volatile Instant updatedAt = createdAt;
    private volatile Instant finishedAt;
    private volatile String message;
    private volatile JsonElement result;
    private volatile String error;
    private volatile Future<?> future;

    private Job(String type, String projectId, String circuitId, long revision) {
      this.type = type;
      this.projectId = projectId;
      this.circuitId = circuitId;
      this.revision = revision;
    }

    String id() {
      return id;
    }

    boolean cancelRequested() {
      return cancelRequested.get() || Thread.currentThread().isInterrupted();
    }

    void progress(double value, String detail) {
      progress = Math.max(0.0, Math.min(1.0, value));
      message = detail;
      updatedAt = Instant.now();
    }

    boolean terminal() {
      return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELED;
    }

    JsonObject toJson() {
      final var value = new JsonObject();
      value.addProperty("jobId", id);
      value.addProperty("type", type);
      value.addProperty("status", status.name().toLowerCase(java.util.Locale.ROOT));
      value.addProperty("projectId", projectId);
      if (circuitId != null) value.addProperty("circuitId", circuitId);
      value.addProperty("revision", revision);
      value.addProperty("progress", progress);
      value.addProperty("cancelRequested", cancelRequested.get());
      value.addProperty("createdAt", createdAt.toString());
      if (startedAt != null) value.addProperty("startedAt", startedAt.toString());
      value.addProperty("updatedAt", updatedAt.toString());
      if (finishedAt != null) value.addProperty("finishedAt", finishedAt.toString());
      if (message != null) value.addProperty("message", message);
      if (result != null) value.add("result", result.deepCopy());
      if (error != null) value.addProperty("error", error);
      return value;
    }
  }

  @FunctionalInterface
  interface JobTask {
    JsonElement execute(Job job) throws Exception;
  }

  private static final class JobThreadFactory implements ThreadFactory {
    private final AtomicInteger serial = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      final var thread =
          new Thread(runnable, "logisim-mcp-job-" + serial.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
