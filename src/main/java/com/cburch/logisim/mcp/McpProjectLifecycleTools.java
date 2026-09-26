/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.LibraryManager;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.PelerCompat;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.mcp.McpJsonRpcDispatcher.McpRpcException;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import javax.swing.JOptionPane;

/**
 * The small set of engineering-level project tools that survive the P0 teardown of the 47-tool
 * MCP implementation (see docs/peler-edition/design/mcp-v2.md, section 十). Everything here is
 * project bookkeeping -- list, create, open, close, save -- with no dependency on the deleted
 * change journal, operation ledger, or job manager: those existed for a multi-client optimistic-
 * concurrency scenario this fork does not have (one person, one model).
 */
final class McpProjectLifecycleTools implements AutoCloseable {
  private final McpModelExecutor executor;
  private final McpProjectRegistry registry;
  private final McpPathPolicy pathPolicy = new McpPathPolicy();

  McpProjectLifecycleTools(McpModelExecutor executor, McpProjectRegistry registry) {
    this.executor = executor;
    this.registry = registry;
  }

  @Override
  public void close() {
    // Nothing to release: this class installs no listeners and owns no background threads.
    // Kept as a real method anyway -- McpServerManager's teardown path calls it unconditionally,
    // and the three deadlocks recorded in CLAUDE.md all trace back to skipping that structure
    // "because there was nothing to clean up yet".
  }

  void registerTools(McpJsonRpcDispatcher dispatcher) {
    dispatcher.registerTool(
        new McpToolDefinition(
            "list_projects", "List open Logisim projects.", schema(), args -> onModel(this::listProjects)));
    dispatcher.registerTool(
        new McpToolDefinition(
            "new_project", "Open a new blank Logisim project window.", schema(), args -> onModel(this::newProject)));
    dispatcher.registerTool(
        new McpToolDefinition(
            "open_project",
            "Open a project from an explicitly allowed local path.",
            schema("path", "string", true),
            this::openProject));
    dispatcher.registerTool(
        new McpToolDefinition(
            "close_project",
            "Close an open project; discarding dirty work requires confirmation.",
            schema("projectId", "string", true, "confirm", "boolean", false),
            this::closeProject));
    dispatcher.registerTool(
        new McpToolDefinition(
            "save_project",
            "Save using the normal Logisim writer.",
            schema("projectId", "string", true),
            this::saveProject));
    dispatcher.registerTool(
        new McpToolDefinition(
            "save_project_as",
            "Save to an explicit local path; overwrite requires confirmation.",
            schema("projectId", "string", true, "path", "string", true, "confirm", "boolean", false),
            this::saveProjectAs));
  }

  private <T> T onModel(java.util.concurrent.Callable<T> callable) throws Exception {
    return executor.call(callable);
  }

  private JsonObject listProjects() {
    final var result = new JsonObject();
    final var values = new JsonArray();
    for (final var project : registry.projects()) values.add(projectSummary(project));
    result.add("projects", values);
    result.addProperty("count", values.size());
    return result;
  }

  private JsonObject projectSummary(Project project) {
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    result.addProperty("name", project.getLogisimFile().getName());
    result.addProperty("dirty", project.isFileDirty());
    final var loader = project.getLogisimFile().getLoader();
    if (loader != null && loader.getMainFile() != null) {
      result.addProperty("file", loader.getMainFile().getAbsolutePath());
    }
    return result;
  }

  private JsonObject newProject() {
    final var project = createBlankProject();
    registry.register(project);
    final var result = projectSummary(project);
    result.addProperty("created", true);
    return result;
  }

  private JsonElement openProject(JsonObject args) throws Exception {
    return onModel(
        () -> {
          final var source = pathPolicy.requireAllowed(null, required(args, "path"), "open_project");
          validateProjectSource(source);
          final var alreadyOpen = Projects.findProjectFor(source);
          if (alreadyOpen != null) {
            final var result = projectSummary(alreadyOpen);
            result.addProperty("alreadyOpen", true);
            return result;
          }
          final Project project;
          try {
            final var loader = new QuietLoader();
            final var file = loader.openLogisimFile(source, false);
            project = new Project(file);
            assignProject(file, project);
            final var frame = new Frame(project);
            frame.setVisible(true);
            frame.toFront();
            frame.getCanvas().requestFocus();
            loader.setParent(frame);
          } catch (LoadFailedException | RuntimeException e) {
            throw rpc(-32006, "Project open failed: " + safeMessage(e));
          }
          registry.register(project);
          final var result = projectSummary(project);
          result.addProperty("alreadyOpen", false);
          return result;
        });
  }

  private JsonElement closeProject(JsonObject args) throws Exception {
    return onModel(
        () -> {
          final var project = requireProject(args);
          final var dirty = project.isFileDirty();
          if (dirty && !booleanValue(args, "confirm", false)) {
            final var data = new JsonObject();
            data.addProperty("projectId", registry.projectId(project));
            data.addProperty("name", project.getLogisimFile().getName());
            data.addProperty("dirty", true);
            data.addProperty("requiresConfirmation", true);
            throw new McpRpcException(-32012, "Closing a dirty project requires confirm=true", data);
          }
          final var closedProjectId = registry.projectId(project);
          Project replacement = null;
          if (project.getFrame() != null && Projects.getOpenProjects().size() <= 1) {
            replacement = createBlankProject();
          }
          project.getLogisimFile().stopAutosaveThread(dirty);
          if (project.getFrame() != null) project.getFrame().dispose();
          else project.getSimulator().shutDown();
          registry.unregister(project);
          final var result = new JsonObject();
          result.addProperty("closedProjectId", closedProjectId);
          result.addProperty("discardedUnsavedChanges", dirty);
          if (replacement != null) {
            registry.register(replacement);
            result.addProperty("replacementProjectId", registry.projectId(replacement));
          }
          return result;
        });
  }

  private JsonElement saveProject(JsonObject args) throws Exception {
    return onModel(() -> save(requireProject(args)));
  }

  private JsonObject save(Project project) throws Exception {
    final var loader = project.getLogisimFile().getLoader();
    final var target = loader == null ? null : loader.getMainFile();
    if (target == null) {
      final var data = new JsonObject();
      data.addProperty("requiresPath", true);
      throw new McpRpcException(
          -32006,
          "Project has no save target; call save_project_as with an absolute .pcirc or .circ path",
          data);
    }
    validateSaveTarget(project, target, false);
    mcpDoSave(project, target);
    return savedProject(project, canonicalPath(target), true);
  }

  private JsonElement saveProjectAs(JsonObject args) throws Exception {
    return onModel(
        () -> {
          final var project = requireProject(args);
          final var rawPath = required(args, "path");
          final var destination = pathPolicy.requireAllowed(project, rawPath, "save_project_as");
          validateSaveTarget(project, destination, true);
          final var existed = Files.exists(destination.toPath());
          if (existed && !booleanValue(args, "confirm", false)) {
            final var data = new JsonObject();
            data.addProperty("path", destination.getPath());
            data.addProperty("requiresConfirmation", true);
            throw new McpRpcException(
                -32012, "Refusing to overwrite an existing file without confirm=true", data);
          }
          mcpDoSave(project, destination);
          return savedProject(project, destination, existed);
        });
  }

  private Project requireProject(JsonObject args) throws McpRpcException {
    final var project = registry.resolve(optional(args, "projectId"));
    if (project == null) throw rpc(-32001, "No open Logisim project");
    return project;
  }

  private JsonObject savedProject(Project project, File target, boolean overwrote) {
    final var result = projectSummary(project);
    result.addProperty("savedPath", target.getPath());
    result.addProperty("overwrote", overwrote);
    return result;
  }

  private static void validateProjectSource(File source) throws McpRpcException {
    if (!Files.isRegularFile(source.toPath()) || !Files.isReadable(source.toPath())) {
      throw rpc(-32602, "path must identify a readable project file");
    }
    final var name = source.getName().toLowerCase(Locale.ROOT);
    if (!name.endsWith(Loader.PELER_EXTENSION) && !name.endsWith(Loader.LOGISIM_EXTENSION)) {
      throw rpc(-32602, "project path must end with .pcirc or .circ");
    }
    final var autosave = source.toPath().resolveSibling("." + source.getName() + ".autosave");
    if (Files.exists(autosave)) {
      final var data = new JsonObject();
      data.addProperty("path", source.getPath());
      data.addProperty("autosavePath", autosave.toString());
      throw new McpRpcException(
          -32012, "An autosave exists; resolve it in the Logisim UI before using MCP open", data);
    }
  }

  private static void validateSaveTarget(Project project, File target, boolean requireExtension)
      throws McpRpcException {
    final var name = target.getName().toLowerCase(Locale.ROOT);
    if (requireExtension
        && !name.endsWith(Loader.PELER_EXTENSION)
        && !name.endsWith(Loader.LOGISIM_EXTENSION)) {
      throw rpc(-32602, "path must end with .pcirc or .circ");
    }
    if (Files.isDirectory(target.toPath())) throw rpc(-32602, "path must name a file");
    final var parent = target.toPath().getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw rpc(-32602, "path parent directory does not exist");
    }
    if (LibraryManager.instance.findReference(project.getLogisimFile(), target) != null) {
      throw rpc(-32006, "Project cannot be saved over a referenced library");
    }
    if (PelerCompat.isCompatTarget(target) && PelerCompat.isLossy(project.getLogisimFile())) {
      throw rpc(
          -32006,
          ".circ would discard this edition's own content (annotations, TTL logic symbols);"
              + " use an explicit .pcirc target");
    }
  }

  private static void mcpDoSave(Project project, File dest) throws McpRpcException {
    final var loader = project.getLogisimFile().getLoader();
    if (loader == null) throw rpc(-32006, "Project has no loader");
    final var oldTool = project.getTool();
    project.setTool(null);
    final var wasHeadless = com.cburch.logisim.Main.headless;
    com.cburch.logisim.Main.headless = true;
    try {
      if (!loader.save(project.getLogisimFile(), dest)) throw rpc(-32006, "Project save failed");
      AppPreferences.updateRecentFile(dest);
      project.setFileAsClean();
    } finally {
      com.cburch.logisim.Main.headless = wasHeadless;
      project.setTool(oldTool);
    }
  }

  private static File canonicalPath(File path) throws McpRpcException {
    try {
      return path.getCanonicalFile();
    } catch (IOException e) {
      throw rpc(-32602, "path cannot be resolved");
    }
  }

  private static void assignProject(LogisimFile file, Project project) {
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    for (final var library : file.getLibraries()) {
      if (library instanceof LoadedLibrary loaded && loaded.getBase() instanceof LogisimFile nested) {
        assignProject(nested, project);
      } else if (library instanceof LogisimFile nested) {
        assignProject(nested, project);
      }
    }
  }

  private static Project createBlankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    assignProject(file, project);
    final var frame = new Frame(project);
    frame.setVisible(true);
    frame.toFront();
    frame.getCanvas().requestFocus();
    loader.setParent(frame);
    return project;
  }

  private static String safeMessage(Exception exception) {
    final var message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private static final class QuietLoader extends Loader {
    QuietLoader() {
      super(null);
    }

    @Override
    public void showError(String description) {
      throw new QuietLoadException(description);
    }

    @Override
    public int showOptions(String message, String title, String[] options, int initialSelection) {
      return JOptionPane.CLOSED_OPTION;
    }
  }

  private static final class QuietLoadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    QuietLoadException(String message) {
      super(message);
    }
  }

  private static String required(JsonObject object, String name) throws McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
      throw rpc(-32602, "Missing parameter: " + name);
    }
    return value.getAsString();
  }

  private static String optional(JsonObject object, String name) {
    final var value = object.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  private static boolean booleanValue(JsonObject object, String name, boolean fallback)
      throws McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) return fallback;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw rpc(-32602, name + " must be a boolean");
    }
    return value.getAsBoolean();
  }

  private static JsonObject schema(Object... values) {
    final var object = new JsonObject();
    object.addProperty("type", "object");
    final var properties = new JsonObject();
    final var required = new JsonArray();
    for (var i = 0; i < values.length; i += 3) {
      final var property = new JsonObject();
      property.addProperty("type", (String) values[i + 1]);
      properties.add((String) values[i], property);
      if ((Boolean) values[i + 2]) required.add((String) values[i]);
    }
    object.add("properties", properties);
    object.add("required", required);
    return object;
  }

  private static McpRpcException rpc(int code, String message) {
    return new McpRpcException(code, message);
  }
}
