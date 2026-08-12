/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.proj.Project;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;

/** Restricts MCP file access to configured roots and directories of already-open projects. */
final class McpPathPolicy {
  private static final String PROPERTY = "logisim.mcp.allowedPaths";
  private static final String ENVIRONMENT = "LOGISIM_MCP_ALLOWED_PATHS";

  File requireAllowed(Project project, String rawPath, String purpose)
      throws McpJsonRpcDispatcher.McpRpcException {
    final Path requested;
    try {
      requested = Path.of(rawPath);
    } catch (InvalidPathException e) {
      throw rpc(-32602, "path is invalid");
    }
    if (!requested.isAbsolute()) throw rpc(-32602, "path must be absolute");

    final File canonical;
    try {
      canonical = requested.normalize().toFile().getCanonicalFile();
    } catch (IOException e) {
      throw rpc(-32602, "path cannot be resolved");
    }

    final var roots = allowedRoots(project);
    if (project == null) {
      for (final var openProject : com.cburch.logisim.proj.Projects.getOpenProjects()) {
        roots.addAll(allowedRoots(openProject));
      }
    }
    final var canonicalPath = canonical.toPath();
    for (final var root : roots) {
      if (canonicalPath.startsWith(root)) return canonical;
    }

    final var data = new JsonObject();
    data.addProperty("path", canonical.getPath());
    data.addProperty("purpose", purpose);
    final var allowed = new JsonArray();
    for (final var root : roots) allowed.add(root.toString());
    data.add("allowedRoots", allowed);
    data.addProperty(
        "configuration",
        "Set -D" + PROPERTY + "=<root" + File.pathSeparator + "root> or " + ENVIRONMENT);
    throw new McpJsonRpcDispatcher.McpRpcException(
        -32016, "MCP file access is outside the allowed roots", data);
  }

  JsonArray allowedRootsJson(Project project) {
    final var result = new JsonArray();
    for (final var root : allowedRoots(project)) result.add(root.toString());
    return result;
  }

  private static LinkedHashSet<Path> allowedRoots(Project project) {
    final var result = new LinkedHashSet<Path>();
    final var configured = configuredValue();
    if (configured != null) {
      for (final var value : configured.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
        addRoot(result, value);
      }
    }
    if (project != null && project.getLogisimFile() != null) {
      final var loader = project.getLogisimFile().getLoader();
      final var mainFile = loader == null ? null : loader.getMainFile();
      if (mainFile != null) addRoot(result, mainFile.getParent());
    }
    return result;
  }

  private static String configuredValue() {
    final var property = System.getProperty(PROPERTY);
    if (property != null && !property.isBlank()) return property;
    final var environment = System.getenv(ENVIRONMENT);
    return environment == null || environment.isBlank() ? null : environment;
  }

  private static void addRoot(LinkedHashSet<Path> roots, String rawPath) {
    if (rawPath == null || rawPath.isBlank()) return;
    try {
      final var path = Path.of(rawPath.trim());
      if (!path.isAbsolute()) return;
      final var canonical = path.normalize().toFile().getCanonicalFile().toPath();
      if (Files.isDirectory(canonical)) roots.add(canonical);
    } catch (IOException | InvalidPathException ignored) {
      // Invalid configured roots do not grant access and are omitted from error metadata.
    }
  }

  private static McpJsonRpcDispatcher.McpRpcException rpc(int code, String message) {
    return new McpJsonRpcDispatcher.McpRpcException(code, message);
  }
}
