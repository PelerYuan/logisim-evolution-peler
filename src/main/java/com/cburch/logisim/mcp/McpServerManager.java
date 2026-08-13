/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.prefs.AppPreferences;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Owns the embedded MCP transport and its application-lifetime cleanup. */
public final class McpServerManager implements AutoCloseable {
  private static final McpServerManager INSTANCE = new McpServerManager();

  private final Object lock = new Object();
  private HttpServer httpServer;
  private ExecutorService httpExecutor;
  private McpModelExecutor modelExecutor;
  private McpProjectRegistry projectRegistry;
  private McpProjectService projectService;
  private McpJsonRpcDispatcher dispatcher;
  private McpHttpHandler httpHandler;
  private McpServerConfig config;
  private int boundPort = -1;
  private boolean shutdownHookInstalled;

  private McpServerManager() {}

  public static McpServerManager getInstance() {
    return INSTANCE;
  }

  /** Starts the default localhost service. Repeated calls are harmless. */
  public static McpServerManager startDefault() {
    final var manager = getInstance();
    try {
      manager.start(McpServerConfig.fromSystemProperties());
    } catch (IOException e) {
      System.err.println("Unable to start embedded MCP server: " + e.getMessage());
    }
    return manager;
  }

  /**
   * Starts the service the user asked for in Preferences, if they asked for one.
   *
   * <p>Separate from {@link #startDefault()} because this is the only path that reads
   * {@link AppPreferences}, and the tests must not: its static initialiser reads the real
   * preference store, and reading it headless writes wrong defaults back. Only {@code Main} calls
   * this, after the GUI is up.
   */
  public static McpServerManager startFromPreferences() {
    final var manager = getInstance();
    final var enabled = AppPreferences.MCP_ENABLED.getBoolean();
    if (enabled) ensureToken();
    final var config =
        McpServerConfig.fromPreferences(
            enabled, AppPreferences.MCP_PORT.get(), AppPreferences.MCP_TOKEN.get());
    try {
      manager.start(config);
    } catch (IOException e) {
      System.err.println("Unable to start embedded MCP server: " + e.getMessage());
    }
    return manager;
  }

  /**
   * Makes sure a bearer token exists, returning it.
   *
   * <p>Generated rather than asked for, because a token a person invents is a token they reuse. It
   * is stored so the same one survives a restart -- the client configuration would otherwise stop
   * working every time the application is reopened.
   */
  public static String ensureToken() {
    final var existing = AppPreferences.MCP_TOKEN.get();
    if (existing != null && !existing.isBlank()) return existing;
    final var bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    final var token = HexFormat.of().formatHex(bytes);
    AppPreferences.MCP_TOKEN.set(token);
    return token;
  }

  public void start(McpServerConfig requested) throws IOException {
    synchronized (lock) {
      if (httpServer != null) return;
      if (!requested.enabled()) {
        config = requested;
        return;
      }
    }

    // Built before the lock is taken, and this is not a style preference. Constructing the project
    // service hops to the event dispatch thread, and this object is asked questions from that
    // thread. Holding the lock across the hop deadlocks the two against each other, and the
    // application then never finishes opening its window -- which is what it did, when the MCP
    // menu was still asking isRunning() while the main window was being built. Compare
    // McpModelExecutor, which had the same shape.
    final var executor = new McpModelExecutor();
    final var registry = new McpProjectRegistry();
    final McpProjectService service;
    final McpJsonRpcDispatcher jsonRpc;
    try {
      service = new McpProjectService(executor, registry);
      jsonRpc = new McpJsonRpcDispatcher(BuildInfo.name, BuildInfo.version.toString(), service);
      service.registerTools(jsonRpc);
    } catch (RuntimeException e) {
      executor.close();
      throw e;
    }

    synchronized (lock) {
      if (httpServer != null) {
        // Another caller won the race while this one was building. Theirs is the live one.
        service.close();
        executor.close();
        return;
      }
      config = requested;
      modelExecutor = executor;
      projectRegistry = registry;
      projectService = service;
      dispatcher = jsonRpc;
      final var firstPort = requested.port();
      IOException last = null;
      for (var offset = 0; offset < 100; offset++) {
        final var candidate = firstPort == 0 ? 0 : firstPort + offset;
        if (candidate > 65535) break;
        try {
          httpServer = HttpServer.create(new InetSocketAddress(requested.host(), candidate), 0);
          boundPort = httpServer.getAddress().getPort();
          break;
        } catch (IOException e) {
          last = e;
        }
      }
      if (httpServer == null) {
        // The service has already attached listeners to every open project, so dropping the
        // executor alone would leave them attached to a server that never started.
        projectService.close();
        modelExecutor.close();
        projectService = null;
        projectRegistry = null;
        dispatcher = null;
        modelExecutor = null;
        throw last == null ? new IOException("No MCP port available") : last;
      }
      httpExecutor = Executors.newCachedThreadPool(new McpThreadFactory());
      httpHandler = new McpHttpHandler(dispatcher, projectService, config);
      httpServer.createContext("/mcp", httpHandler);
      httpServer.setExecutor(httpExecutor);
      httpServer.start();
      installShutdownHook();
      final var endpoint = endpoint();
      System.err.println("Embedded MCP server listening at " + endpoint);
      if (config.token() != null && !config.token().isBlank()) {
        System.err.println("Embedded MCP server authentication is enabled");
      }
    }
  }

  public McpJsonRpcDispatcher dispatcher() {
    synchronized (lock) {
      return dispatcher;
    }
  }

  public McpModelExecutor modelExecutor() {
    synchronized (lock) {
      return modelExecutor;
    }
  }

  public String endpoint() {
    synchronized (lock) {
      return boundPort < 0 ? null : "http://" + config.host() + ":" + boundPort + "/mcp";
    }
  }

  /**
   * Returns a Claude/Codex MCP configuration JSON string, or null if the server is not running.
   * The caller can paste this directly into a Claude Desktop or VS Code MCP config.
   */
  public String clientConfigJson() {
    synchronized (lock) {
      if (boundPort < 0 || config == null) return null;
      final var url = endpoint();
      final var token = config.token();
      final var sb = new StringBuilder();
      sb.append("{\n  \"mcpServers\": {\n    \"logisim\": {\n");
      sb.append("      \"url\": \"").append(url).append("\"");
      if (token != null && !token.isBlank()) {
        sb.append(",\n      \"headers\": { \"Authorization\": \"Bearer ").append(token).append("\" }");
      }
      sb.append("\n    }\n  }\n}");
      return sb.toString();
    }
  }

  /** Returns true if the server is running with a non-blank authentication token. */
  public boolean tokenConfigured() {
    synchronized (lock) {
      return config != null && config.token() != null && !config.token().isBlank();
    }
  }

  /**
   * The token the running server actually accepts, or an empty string when it wants none.
   *
   * <p>Read from the live configuration rather than from preferences: a system property overrides
   * the stored value, so the two disagree exactly when it would matter.
   */
  public String token() {
    synchronized (lock) {
      return config == null || config.token() == null ? "" : config.token();
    }
  }

  public boolean isRunning() {
    synchronized (lock) {
      return httpServer != null;
    }
  }

  @Override
  public void close() {
    final McpProjectService service;
    final McpModelExecutor executor;
    synchronized (lock) {
      if (httpServer != null) {
        httpServer.stop(0);
        httpServer = null;
      }
      if (httpHandler != null) {
        httpHandler.close();
        httpHandler = null;
      }
      if (httpExecutor != null) {
        httpExecutor.shutdownNow();
        httpExecutor = null;
      }
      service = projectService;
      executor = modelExecutor;
      projectService = null;
      modelExecutor = null;
      projectRegistry = null;
      dispatcher = null;
      boundPort = -1;
    }
    // Detached outside the lock for the reason given in start(): removing the listeners hops to
    // the event dispatch thread, and that thread asks this object whether it is running. By here
    // the fields are already cleared, so anyone who asks gets the right answer meanwhile.
    if (service != null) service.close();
    if (executor != null) executor.close();
  }

  private void installShutdownHook() {
    if (shutdownHookInstalled) return;
    shutdownHookInstalled = true;
    Runtime.getRuntime().addShutdownHook(new Thread(this::close, "logisim-mcp-shutdown"));
  }

  private static final class McpThreadFactory implements ThreadFactory {
    private int serial;

    @Override
    public synchronized Thread newThread(Runnable runnable) {
      final var thread = new Thread(runnable, "logisim-mcp-http-" + (++serial));
      thread.setDaemon(true);
      return thread;
    }
  }
}
