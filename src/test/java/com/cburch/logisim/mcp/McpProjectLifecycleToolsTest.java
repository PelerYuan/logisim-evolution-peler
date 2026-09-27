/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the six lifecycle tools that survive the P0 teardown of the 47-tool MCP implementation
 * (see docs/peler-edition/design/mcp-v2.md, section 十/十一). {@code new_project} and the
 * frame-owning half of {@code open_project} are not covered here: both construct a real
 * {@code javax.swing.JFrame}, which throws {@code HeadlessException} under this module's test
 * task (headless is forced deliberately, see {@code build.gradle.kts}). This is not a regression
 * -- the old {@code McpProjectService} had the same two code paths and, per the P0 design review,
 * neither was ever covered by a test either.
 */
class McpProjectLifecycleToolsTest {
  @TempDir java.nio.file.Path tempDir;
  private Project project;
  private McpModelExecutor executor;
  private McpProjectRegistry registry;
  private McpProjectLifecycleTools tools;
  private McpJsonRpcDispatcher dispatcher;
  private List<Project> openProjects;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty("logisim.mcp.allowedPaths", tempDir.toString());
    try (InputStream input = getClass().getResourceAsStream("/htmlexport/and2.circ")) {
      assertNotNull(input, "test circuit fixture is missing");
      final var loader = new Loader(null);
      project = new Project(loader.openLogisimFile(input));
      for (final var circuit : project.getLogisimFile().getCircuits()) circuit.setProject(project);
    }
    openProjects = mutableOpenProjects();
    openProjects.add(project);

    executor = new McpModelExecutor();
    registry = new McpProjectRegistry();
    registry.register(project);
    tools = new McpProjectLifecycleTools(executor, registry);
    dispatcher = new McpJsonRpcDispatcher("test", "1", null);
    tools.registerTools(dispatcher);
  }

  @AfterEach
  void tearDown() {
    if (tools != null) tools.close();
    if (executor != null) executor.close();
    if (project != null) project.getSimulator().shutDown();
    if (openProjects != null) openProjects.remove(project);
    System.clearProperty("logisim.mcp.allowedPaths");
  }

  @Test
  void toolsListExposesExactlyTheSixSurvivingLifecycleTools() {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", 0);
    request.addProperty("method", "tools/list");
    final var response = dispatcher.dispatch(request);
    final var names =
        response
            .getAsJsonObject("result")
            .getAsJsonArray("tools")
            .asList()
            .stream()
            .map(tool -> tool.getAsJsonObject().get("name").getAsString())
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(
        java.util.Set.of(
            "list_projects", "new_project", "open_project", "close_project", "save_project",
            "save_project_as"),
        names);
  }

  @Test
  void listProjectsReportsIdentityFieldsOnly() {
    final var result = call("list_projects", new JsonObject(), 1);
    assertEquals(1, result.get("count").getAsInt());
    final var entry = result.getAsJsonArray("projects").get(0).getAsJsonObject();
    assertEquals(registry.projectId(project), entry.get("projectId").getAsString());
    assertTrue(entry.has("name"));
    assertTrue(entry.has("dirty"));
    // The full circuit/geometry dump belongs to the P1/P4 query-based read interface, not to this
    // lifecycle tool -- these fields must not reappear here by accident.
    assertFalse(entry.has("revision"));
    assertFalse(entry.has("circuits"));
    assertFalse(entry.has("currentCircuitId"));
  }

  @Test
  void openProjectRejectsPathOutsideAllowedRoots() {
    final var args = new JsonObject();
    args.addProperty("path", "/definitely-not-an-allowed-root/project.pcirc");
    final var response = callRaw("open_project", args, 2);
    assertEquals(-32016, response.getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void openProjectRejectsPathWithAutosaveConflict() throws Exception {
    final var target = tempDir.resolve("conflicted.pcirc");
    Files.writeString(target, "not a real project, only existence matters here");
    Files.writeString(tempDir.resolve(".conflicted.pcirc.autosave"), "autosave placeholder");

    final var args = new JsonObject();
    args.addProperty("path", target.toString());
    final var response = callRaw("open_project", args, 3);
    assertEquals(-32012, response.getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void openProjectDetectsAlreadyOpenProject() {
    final var destination = tempDir.resolve("already-open.pcirc");
    final var save = new JsonObject();
    save.addProperty("projectId", registry.projectId(project));
    save.addProperty("path", destination.toString());
    call("save_project_as", save, 4);

    final var open = new JsonObject();
    open.addProperty("path", destination.toString());
    final var result = call("open_project", open, 5);
    assertTrue(result.get("alreadyOpen").getAsBoolean());
    assertEquals(registry.projectId(project), result.get("projectId").getAsString());
  }

  @Test
  void saveProjectRequiresPathWhenProjectHasNoMainFile() {
    final var args = new JsonObject();
    args.addProperty("projectId", registry.projectId(project));
    final var response = callRaw("save_project", args, 6);
    final var error = response.getAsJsonObject("error");
    assertEquals(-32006, error.get("code").getAsInt());
    assertTrue(error.getAsJsonObject("data").get("requiresPath").getAsBoolean());
  }

  @Test
  void saveProjectAsRequiresConfirmationAndRoundTrips() throws Exception {
    final var destination = tempDir.resolve("roundtrip.pcirc");
    final var save = new JsonObject();
    save.addProperty("projectId", registry.projectId(project));
    save.addProperty("path", destination.toString());

    final var saved = call("save_project_as", save, 7);
    assertEquals(destination.toAbsolutePath().toString(), saved.get("savedPath").getAsString());
    assertFalse(saved.get("overwrote").getAsBoolean());
    assertTrue(Files.size(destination) > 0);
    assertFalse(project.isFileDirty());

    final var refused = callRaw("save_project_as", save, 8);
    assertEquals(-32012, refused.getAsJsonObject("error").get("code").getAsInt());

    save.addProperty("confirm", true);
    assertTrue(call("save_project_as", save, 9).get("overwrote").getAsBoolean());
    try (InputStream input = Files.newInputStream(destination)) {
      assertNotNull(new Loader(null).openLogisimFile(input));
    }
  }

  @Test
  void closeProjectRequiresConfirmationForDirtyProject() {
    project.setForcedDirty();
    final var args = new JsonObject();
    args.addProperty("projectId", registry.projectId(project));
    final var response = callRaw("close_project", args, 10);
    final var error = response.getAsJsonObject("error");
    assertEquals(-32012, error.get("code").getAsInt());
    assertTrue(error.getAsJsonObject("data").get("requiresConfirmation").getAsBoolean());
    // Refused, so the project must still be open and resolvable.
    assertNotNull(registry.resolve(registry.projectId(project)));
  }

  @Test
  void closeProjectClosesAfterConfirmation() {
    project.setForcedDirty();
    final var projectId = registry.projectId(project);
    final var args = new JsonObject();
    args.addProperty("projectId", projectId);
    args.addProperty("confirm", true);
    final var result = call("close_project", args, 11);
    assertEquals(projectId, result.get("closedProjectId").getAsString());
    assertTrue(result.get("discardedUnsavedChanges").getAsBoolean());
    // The fixture project has no Frame, so the "replace the last project" branch (which would
    // construct a new one) never triggers -- nothing here should have opened a blank project.
    assertFalse(result.has("replacementProjectId"));
    // registry.unregister() only drops the explicit registration; a Frame-less fixture project
    // stays in Projects.getOpenProjects() until this harness removes it (a real close disposes the
    // Frame, which is what actually drops it there), so resolve() would otherwise re-discover it
    // on the very next refresh(). Simulate that real removal before checking it is really gone.
    openProjects.remove(project);
    assertNull(registry.resolve(projectId));
  }

  /** Regression test for the reported bug: a new MCP-created project used the bare
   * {@link com.cburch.logisim.file.LogisimFile#createNew} instead of loading the default
   * template, so {@code file.getLibraries()} was empty even though a component like an XOR gate
   * could still be placed (placement resolves against the always-present builtin loader tree, not
   * the project's own library list). The gap only surfaced later, when
   * {@link com.cburch.logisim.file.XmlWriter} tried to save: it could not find which of the
   * project's own libraries owned the XOR gate's factory, dropped the component, and reported
   * "XOR Gate component not found". {@link McpProjectLifecycleTools#openTemplate} is the fix, and
   * is exercised directly here because the surrounding {@code new_project} tool also constructs a
   * real {@code Frame}, which this headless test task cannot do. */
  @Test
  void newProjectTemplateKeepsBuiltInLibrariesSoASavedNonBaseComponentSurvives() throws Exception {
    final var loader = new Loader(null);
    final var file = McpProjectLifecycleTools.openTemplate(loader);
    assertFalse(
        file.getLibraries().isEmpty(),
        "a template-loaded project should already have its libraries, unlike the bare"
            + " LogisimFile.createNew that used to leave new MCP projects library-less");

    final var templateProject = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(templateProject);

    final var space = com.cburch.logisim.dsl.Space.of(templateProject);
    final var xorGate = com.cburch.logisim.dsl.Kind.of(space, "gates/xor_gate");
    space.place(xorGate).anchorAt(0, 0).place();
    space.commit("place an xor gate");

    final var dest = tempDir.resolve("template-project.circ").toFile();
    final var wasHeadless = com.cburch.logisim.Main.headless;
    com.cburch.logisim.Main.headless = true;
    final boolean saved;
    try {
      saved = loader.save(file, dest);
    } finally {
      com.cburch.logisim.Main.headless = wasHeadless;
    }
    assertTrue(saved, "save should succeed without a \"component not found\" file error");

    final var reopened = new Loader(null).openLogisimFile(dest);
    final var hasXorGate =
        reopened.getMainCircuit().getNonWires().stream()
            .anyMatch(c -> c.getFactory().getName().equals("XOR Gate"));
    assertTrue(hasXorGate, "the XOR gate should have survived the save/reload round trip");
  }

  private JsonObject call(String method, JsonObject arguments, int id) {
    final var response = callRaw(method, arguments, id);
    assertFalse(response.has("error"), response.toString());
    return response.getAsJsonObject("result").getAsJsonObject("structuredContent");
  }

  private JsonObject callRaw(String method, JsonObject arguments, int id) {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", id);
    request.addProperty("method", "tools/call");
    final var params = new JsonObject();
    params.addProperty("name", method);
    params.add("arguments", arguments);
    request.add("params", params);
    return dispatcher.dispatch(request);
  }

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }
}
