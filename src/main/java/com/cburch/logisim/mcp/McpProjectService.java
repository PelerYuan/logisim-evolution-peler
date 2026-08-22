/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitEvent;
import com.cburch.logisim.circuit.CircuitListener;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LibraryEvent;
import com.cburch.logisim.file.LibraryListener;
import com.cburch.logisim.file.LibraryManager;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PelerCompat;
import com.cburch.logisim.fpga.designrulecheck.CorrectLabel;
import com.cburch.logisim.gui.log.ComponentSelector;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectEvent;
import com.cburch.logisim.proj.ProjectListener;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.AnalyzeException;
import com.cburch.logisim.gui.htmlexport.HtmlExporter;
import com.cburch.logisim.util.SyntaxChecker;
import com.cburch.logisim.vhdl.base.VhdlContent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** MCP-facing project and circuit operations. All model access is serialized on the EDT. */
public final class McpProjectService
    implements McpJsonRpcDispatcher.McpResourceProvider, AutoCloseable {
  private static final String PROJECTS_URI = "logisim://projects";
  private static final String SNAPSHOT_URI_PREFIX = "logisim://project/";
  private static final String SIMULATION_URI_SUFFIX = "/simulation";
  private static final int MAX_MANUAL_TICKS = 1_000_000;

  private final McpModelExecutor executor;
  private final McpProjectRegistry registry;
  private final McpOperationLedger operationLedger = new McpOperationLedger();
  private final McpOperationLedger lifecycleOperationLedger = new McpOperationLedger();
  private final McpPathPolicy pathPolicy = new McpPathPolicy();
  private final McpTestVectorJobService vectorJobService = new McpTestVectorJobService();
  private final McpChangeJournal changeJournal = new McpChangeJournal();
  private final IdentityHashMap<Project, Long> revisions = new IdentityHashMap<>();
  private final IdentityHashMap<Project, ProjectListener> listeners = new IdentityHashMap<>();
  private final IdentityHashMap<Circuit, CircuitListener> circuitListeners = new IdentityHashMap<>();
  private final IdentityHashMap<Circuit, Project> circuitOwners = new IdentityHashMap<>();
  private final IdentityHashMap<Project, LibraryListener> libraryListeners = new IdentityHashMap<>();
  private final IdentityHashMap<Project, Simulator.Listener> simulatorListeners = new IdentityHashMap<>();
  private final IdentityHashMap<Project, String> activeOperationIds = new IdentityHashMap<>();
  private final IdentityHashMap<Library, String> libraryIds = new IdentityHashMap<>();
  private final IdentityHashMap<Project, IdentityHashMap<Action, String>> ownedActions =
      new IdentityHashMap<>();
  private final IdentityHashMap<Project, LinkedHashSet<String>> pendingCircuitUris =
      new IdentityHashMap<>();
  private final PropertyChangeListener projectListListener;

  public McpProjectService(McpModelExecutor executor, McpProjectRegistry registry) {
    this.executor = executor;
    this.registry = registry;
    projectListListener =
        ignored ->
            this.executor.run(
                () -> {
                  refresh();
                  changeJournal.record(
                      "project_list_changed", null, 0, List.of(PROJECTS_URI), null);
                });
    Projects.addPropertyChangeListener(Projects.PROJECT_LIST_PROPERTY, projectListListener);
    executor.run(this::refresh);
  }

  @Override
  public void close() {
    Projects.removePropertyChangeListener(Projects.PROJECT_LIST_PROPERTY, projectListListener);
    // Detaching hops to the event dispatch thread, so it is only possible while that thread is
    // still taking events. At JVM shutdown it is not: quitting calls System.exit from it, and this
    // runs from a shutdown hook, so the hop would wait for the very thread that is waiting for the
    // hook. Nothing is lost by skipping it either -- the listeners and everything holding them go
    // with the process. Cancelling the jobs below is not skippable in the same way, because those
    // own threads of their own, so it stays outside the guard.
    if (executor.canReachModel()) {
      executor.run(
          () -> {
            for (final var entry : listeners.entrySet()) {
              entry.getKey().removeProjectListener(entry.getValue());
            }
            for (final var entry : circuitListeners.entrySet()) {
              entry.getKey().removeCircuitListener(entry.getValue());
            }
            for (final var entry : libraryListeners.entrySet()) {
              entry.getKey().removeLibraryListener(entry.getValue());
            }
            for (final var entry : simulatorListeners.entrySet()) {
              entry.getKey().getSimulator().removeSimulatorListener(entry.getValue());
            }
            listeners.clear();
            circuitListeners.clear();
            circuitOwners.clear();
            libraryListeners.clear();
            simulatorListeners.clear();
            activeOperationIds.clear();
            libraryIds.clear();
            ownedActions.clear();
            pendingCircuitUris.clear();
          });
    }
    vectorJobService.close();
  }

  public void registerTools(McpJsonRpcDispatcher dispatcher) {
    dispatcher.registerTool(new McpToolDefinition("list_projects", "List open Logisim projects.", schema(), args -> onModel(this::listProjects)));
    dispatcher.registerTool(new McpToolDefinition("new_project", "Open a new blank Logisim project window.", lifecycleSchema(), this::newProject));
    dispatcher.registerTool(new McpToolDefinition("open_project", "Open a project from an explicitly allowed local path.", lifecycleSchema("path", "string", true), this::openProject));
    dispatcher.registerTool(new McpToolDefinition("close_project", "Close an open project; discarding dirty work requires confirmation.", writeSchema("projectId", "string", true, "confirm", "boolean", false), this::closeProject));
    dispatcher.registerTool(new McpToolDefinition("get_project", "Read project metadata and circuit summaries.", schema("projectId", "string", true), args -> onModel(() -> getProject(requireProject(args)))));
    dispatcher.registerTool(new McpToolDefinition("list_circuits", "List circuits in an open project.", schema("projectId", "string", true), args -> onModel(() -> listCircuits(requireProject(args)))));
    dispatcher.registerTool(new McpToolDefinition("get_circuit_snapshot", "Read the live in-memory circuit.", schema("projectId", "string", true, "circuitId", "string", false), args -> onModel(() -> snapshot(requireProject(args), optional(args, "circuitId"), true))));
    dispatcher.registerTool(new McpToolDefinition("find_components", "Find live components by factory or label.", schema("projectId", "string", true, "circuitId", "string", false, "factory", "string", false, "label", "string", false), args -> onModel(() -> findComponents(requireProject(args), args))));
    dispatcher.registerTool(new McpToolDefinition("get_available_tools", "List component factories available to the project.", schema("projectId", "string", true), args -> onModel(() -> availableTools(requireProject(args)))));
    dispatcher.registerTool(new McpToolDefinition("list_libraries", "List loaded and built-in libraries for a project.", schema("projectId", "string", true), args -> onModel(() -> listLibraries(requireProject(args)))));
    dispatcher.registerTool(new McpToolDefinition("load_library", "Load a built-in, Logisim, or JAR library. JAR kind executes external code and requires confirm=true.", writeSchema("projectId", "string", true, "kind", "string", true, "name", "string", false, "path", "string", false, "className", "string", false, "confirm", "boolean", false), this::loadLibrary));
    dispatcher.registerTool(new McpToolDefinition("unload_library", "Unload an unused library through an undoable project action; confirmation is required.", writeSchema("projectId", "string", true, "libraryId", "string", true, "confirm", "boolean", false), this::unloadLibrary));
    dispatcher.registerTool(new McpToolDefinition("poll_changes", "Poll the bounded change journal and receive resource update notifications.", schema("afterSequence", "integer", false, "limit", "integer", false, "projectId", "string", false, "resourceUri", "string", false, "subscribedOnly", "boolean", false), this::pollChanges));
    dispatcher.registerTool(new McpToolDefinition("add_component", "Add a component through CircuitMutation and Project.doAction.", writeSchema("projectId", "string", true, "circuitId", "string", false, "factory", "string", true, "x", "integer", true, "y", "integer", true, "attributes", "object", false), this::addComponent));
    dispatcher.registerTool(new McpToolDefinition("remove_components", "Remove components through one undoable mutation.", writeSchema("projectId", "string", true, "componentIds", "array", true), this::removeComponents));
    dispatcher.registerTool(new McpToolDefinition("add_wire", "Add a wire through CircuitMutation and Project.doAction.", writeSchema("projectId", "string", true, "circuitId", "string", false, "x1", "integer", true, "y1", "integer", true, "x2", "integer", true, "y2", "integer", true), this::addWire));
    dispatcher.registerTool(new McpToolDefinition("remove_wires", "Remove wires through one undoable mutation.", writeSchema("projectId", "string", true, "wireIds", "array", true), this::removeWires));
    dispatcher.registerTool(new McpToolDefinition("set_component_attributes", "Set component attributes through CircuitMutation.", writeSchema("projectId", "string", true, "componentId", "string", true, "attributes", "object", true), this::setAttributes));
    dispatcher.registerTool(new McpToolDefinition("move_components", "Move components through replacement mutation.", writeSchema("projectId", "string", true, "moves", "array", true), this::moveComponents));
    dispatcher.registerTool(new McpToolDefinition("rotate_components", "Rotate components around their visual centers in one undoable mutation.", writeSchema("projectId", "string", true, "componentIds", "array", true, "direction", "string", false, "quarterTurns", "integer", false), this::rotateComponents));
    dispatcher.registerTool(new McpToolDefinition("set_circuit_attributes", "Set circuit attributes through one undoable mutation.", writeSchema("projectId", "string", true, "circuitId", "string", false, "attributes", "object", true), this::setCircuitAttributes));
    dispatcher.registerTool(new McpToolDefinition("batch_edit", "Apply core circuit edits atomically as one undoable action.", writeSchema("projectId", "string", true, "circuitId", "string", false, "operations", "array", true), this::batchEdit));
    dispatcher.registerTool(new McpToolDefinition("undo", "Undo a specific MCP-owned action when it is the current undo-stack head.", writeSchema("projectId", "string", true, "targetOperationId", "string", true), this::undo));
    dispatcher.registerTool(new McpToolDefinition("redo", "Redo a specific MCP-owned action when it is the current redo-stack head.", writeSchema("projectId", "string", true, "targetOperationId", "string", true), this::redo));
    dispatcher.registerTool(new McpToolDefinition("save_project", "Save using the normal Logisim writer.", writeSchema("projectId", "string", true), this::saveProject));
    dispatcher.registerTool(new McpToolDefinition("save_project_as", "Save to an explicit local path; overwrite requires confirmation.", writeSchema("projectId", "string", true, "path", "string", true, "confirm", "boolean", false), this::saveProjectAs));
    dispatcher.registerTool(new McpToolDefinition("create_circuit", "Create a new circuit through one undoable project action.", writeSchema("projectId", "string", true, "name", "string", true), this::createCircuit));
    dispatcher.registerTool(new McpToolDefinition("remove_circuit", "Remove an unused circuit; destructive confirmation is required.", writeSchema("projectId", "string", true, "circuitId", "string", true, "confirm", "boolean", false), this::removeCircuit));
    dispatcher.registerTool(new McpToolDefinition("rename_circuit", "Rename a circuit through one undoable action.", writeSchema("projectId", "string", true, "circuitId", "string", true, "name", "string", true), this::renameCircuit));
    dispatcher.registerTool(new McpToolDefinition("set_main_circuit", "Set the project's main circuit through one undoable action.", writeSchema("projectId", "string", true, "circuitId", "string", true), this::setMainCircuit));
    dispatcher.registerTool(new McpToolDefinition("switch_circuit", "Switch the visible circuit without changing the edit undo stack.", writeSchema("projectId", "string", true, "circuitId", "string", true), this::switchCircuit));
    dispatcher.registerTool(new McpToolDefinition("get_simulator_state", "Read live simulator state without changing the project.", schema("projectId", "string", true), args -> onModel(() -> simulatorState(requireProject(args)))));
    dispatcher.registerTool(new McpToolDefinition("simulator_reset", "Reset simulation state; does not create an undo entry or change revision.", schema("projectId", "string", true), this::simulatorReset));
    dispatcher.registerTool(new McpToolDefinition("simulator_step", "Request one simulation propagation step; does not create an undo entry. Returned state may precede propagation completion.", schema("projectId", "string", true), this::simulatorStep));
    dispatcher.registerTool(new McpToolDefinition("simulator_tick", "Request a bounded number of simulation clock ticks; does not create an undo entry.", schema("projectId", "string", true, "count", "integer", false), this::simulatorTick));
    dispatcher.registerTool(new McpToolDefinition("configure_simulator", "Set simulator propagation/ticking flags and frequency. Does not modify the circuit model or revision.", schema("projectId", "string", true, "autoPropagation", "boolean", false, "autoTicking", "boolean", false, "tickFrequency", "number", false), this::configureSimulator));
    // Kept as a compatibility alias for clients that adopted the pre-R6 draft name.
    dispatcher.registerTool(new McpToolDefinition("set_simulator_mode", "Set simulator propagation/ticking flags and frequency (alias for configure_simulator).", schema("projectId", "string", true, "autoPropagation", "boolean", false, "autoTicking", "boolean", false, "tickFrequency", "number", false), this::setSimulatorMode));
    dispatcher.registerTool(new McpToolDefinition("run_test_vector", "Run a test-vector file against a circuit snapshot as a background job. Returns a jobId immediately.", schema("projectId", "string", true, "circuitId", "string", false, "vectorPath", "string", true), this::runTestVector));
    dispatcher.registerTool(new McpToolDefinition("get_job", "Get the status and result of a background job.", schema("jobId", "string", true), this::getJob));
    dispatcher.registerTool(new McpToolDefinition("list_jobs", "List background jobs, optionally filtered by projectId.", schema("projectId", "string", false), this::listJobs));
    dispatcher.registerTool(new McpToolDefinition("cancel_job", "Request cancellation of a running or queued background job.", schema("jobId", "string", true), this::cancelJob));
    dispatcher.registerTool(new McpToolDefinition("remove_job", "Remove a completed, failed, or canceled job from the list.", schema("jobId", "string", true), this::removeJob));
    dispatcher.registerTool(new McpToolDefinition("analyze_circuit", "Analyze a circuit's truth table and Boolean expressions. Read-only; does not change the project.", schema("projectId", "string", true, "circuitId", "string", false), this::analyzeCircuit));
    dispatcher.registerTool(new McpToolDefinition("export_html", "Export a circuit as a self-contained interactive HTML page. Does not modify the project.", schema("projectId", "string", true, "circuitId", "string", false, "path", "string", true, "confirm", "boolean", false), this::exportHtml));
    dispatcher.registerTool(new McpToolDefinition("list_vhdl_entities", "List VHDL entities in a project.", schema("projectId", "string", true), args -> onModel(() -> listVhdlEntities(requireProject(args)))));
    dispatcher.registerTool(new McpToolDefinition("get_vhdl_content", "Get the VHDL source for a named entity.", schema("projectId", "string", true, "vhdlId", "string", true), args -> onModel(() -> getVhdlContent(requireProject(args), required(args, "vhdlId")))));
    dispatcher.registerTool(new McpToolDefinition("set_vhdl_content", "Add or replace a VHDL entity. Replacing requires confirm=true.", writeSchema("projectId", "string", true, "name", "string", true, "content", "string", true, "confirm", "boolean", false), this::setVhdlContent));
  }

  @Override
  public JsonObject list() throws Exception {
    return onModel(() -> {
      refresh();
      final var result = new JsonObject();
      final var resources = new JsonArray();
      final var root = new JsonObject();
      root.addProperty("uri", PROJECTS_URI);
      root.addProperty("name", "Open Logisim projects");
      root.addProperty("description", "Projects currently open in this Logisim process");
      root.addProperty("mimeType", "application/json");
      resources.add(root);
      for (final var project : registry.projects()) {
        final var projectId = registry.projectId(project);
        final var resource = new JsonObject();
        resource.addProperty("uri", SNAPSHOT_URI_PREFIX + projectId + "/snapshot");
        resource.addProperty("name", project.getLogisimFile().getName());
        resource.addProperty("description", "Live in-memory Logisim project snapshot");
        resource.addProperty("mimeType", "application/json");
        resources.add(resource);
        final var simulation = new JsonObject();
        simulation.addProperty("uri", SNAPSHOT_URI_PREFIX + projectId + SIMULATION_URI_SUFFIX);
        simulation.addProperty("name", project.getLogisimFile().getName() + " simulation");
        simulation.addProperty("description", "Live Logisim simulator state");
        simulation.addProperty("mimeType", "application/json");
        resources.add(simulation);
        for (final var circuit : project.getLogisimFile().getCircuits()) {
          final var circuitResource = new JsonObject();
          circuitResource.addProperty(
              "uri",
              SNAPSHOT_URI_PREFIX
                  + projectId
                  + "/circuit/"
                  + registry.circuitId(circuit));
          circuitResource.addProperty("name", circuit.getName());
          circuitResource.addProperty("description", "Live in-memory Logisim circuit snapshot");
          circuitResource.addProperty("mimeType", "application/json");
          resources.add(circuitResource);
        }
      }
      result.add("resources", resources);
      return result;
    });
  }

  @Override
  public JsonObject read(String uri) throws Exception {
    return onModel(() -> {
      refresh();
      final JsonObject value;
      if (PROJECTS_URI.equals(uri)) {
        value = listProjects();
      } else if (uri != null && uri.startsWith(SNAPSHOT_URI_PREFIX)) {
        final var parts = uri.substring(SNAPSHOT_URI_PREFIX.length()).split("/", -1);
        if (parts.length == 2 && "snapshot".equals(parts[1])) {
          final var project = registry.resolve(parts[0]);
          if (project == null) throw rpc(-32001, "Unknown project resource");
          value = getProject(project);
        } else if (parts.length == 2 && "simulation".equals(parts[1])) {
          final var project = registry.resolve(parts[0]);
          if (project == null) throw rpc(-32001, "Unknown project resource");
          value = simulatorState(project);
        } else if (parts.length == 3 && "circuit".equals(parts[1])) {
          final var project = registry.resolve(parts[0]);
          if (project == null) throw rpc(-32001, "Unknown project resource");
          value = snapshot(project, parts[2], true);
        } else {
          throw rpc(-32004, "Unknown resource URI: " + uri);
        }
      } else {
        throw rpc(-32004, "Unknown resource URI: " + uri);
      }
      final var content = new JsonObject();
      content.addProperty("uri", uri);
      content.addProperty("mimeType", "application/json");
      content.addProperty("text", value.toString());
      final var contents = new JsonArray();
      contents.add(content);
      final var result = new JsonObject();
      result.add("contents", contents);
      return result;
    });
  }

  @Override
  public boolean supportsSubscriptions() {
    return true;
  }

  @Override
  public void subscribe(String uri) throws Exception {
    final var sessionId = McpJsonRpcDispatcher.currentSessionId();
    onModel(
        () -> {
          validateResourceUri(uri);
          changeJournal.subscribe(sessionId, uri);
          return null;
        });
  }

  @Override
  public void unsubscribe(String uri) throws Exception {
    final var sessionId = McpJsonRpcDispatcher.currentSessionId();
    onModel(
        () -> {
          validateResourceUri(uri);
          changeJournal.unsubscribe(sessionId, uri);
          return null;
        });
  }

  @Override
  public JsonObject templates() {
    final var values = new JsonArray();
    final var project = new JsonObject();
    project.addProperty("uriTemplate", "logisim://project/{projectId}/snapshot");
    project.addProperty("name", "Logisim project snapshot");
    project.addProperty("mimeType", "application/json");
    values.add(project);
    final var circuit = new JsonObject();
    circuit.addProperty(
        "uriTemplate", "logisim://project/{projectId}/circuit/{circuitId}");
    circuit.addProperty("name", "Logisim circuit snapshot");
    circuit.addProperty("mimeType", "application/json");
    values.add(circuit);
    final var simulation = new JsonObject();
    simulation.addProperty("uriTemplate", "logisim://project/{projectId}/simulation");
    simulation.addProperty("name", "Logisim simulator state");
    simulation.addProperty("mimeType", "application/json");
    values.add(simulation);
    final var result = new JsonObject();
    result.add("resourceTemplates", values);
    return result;
  }

  McpChangeJournal.EventBatch waitForResourceNotifications(
      String sessionId, long afterSequence, long timeoutMillis) throws InterruptedException {
    return changeJournal.waitForNotifications(sessionId, afterSequence, timeoutMillis);
  }

  McpChangeJournal.EventBatch resourceNotifications(String sessionId, long afterSequence) {
    return changeJournal.notificationsAfter(sessionId, afterSequence);
  }

  long latestChangeSequence() {
    return changeJournal.latestSequence();
  }

  void closeSession(String sessionId) {
    changeJournal.closeSession(sessionId);
  }

  void wakeEventStreams() {
    changeJournal.wakeWaiters();
  }

  private JsonElement pollChanges(JsonObject arguments) throws Exception {
    final var sessionId = McpJsonRpcDispatcher.currentSessionId();
    return onModel(() -> changeJournal.poll(sessionId, arguments));
  }

  private <T> T onModel(Callable<T> callable) throws Exception {
    return executor.call(callable);
  }

  private void refresh() {
    registry.refresh();
    final var current = java.util.Collections.newSetFromMap(new IdentityHashMap<Project, Boolean>());
    final var currentCircuits =
        java.util.Collections.newSetFromMap(new IdentityHashMap<Circuit, Boolean>());
    current.addAll(registry.projects());
    for (final var project : current) {
      revisions.putIfAbsent(project, 0L);
      if (!listeners.containsKey(project)) {
        final ProjectListener listener = event -> projectChanged(project, event);
        listeners.put(project, listener);
        project.addProjectListener(listener);
      }
      if (!libraryListeners.containsKey(project)) {
        final LibraryListener listener = event -> libraryChanged(project, event);
        libraryListeners.put(project, listener);
        project.addLibraryListener(listener);
      }
      if (!simulatorListeners.containsKey(project)) {
        final Simulator.Listener listener =
            new Simulator.Listener() {
              @Override
              public void simulatorReset(Simulator.Event event) {
                simulatorChanged(project, "simulator_reset", event);
              }

              @Override
              public void simulatorStateChanged(Simulator.Event event) {
                simulatorChanged(project, "simulator_state_changed", event);
              }

              @Override
              public void propagationCompleted(Simulator.Event event) {
                simulatorChanged(project, "simulator_propagation_completed", event);
              }
            };
        simulatorListeners.put(project, listener);
        project.getSimulator().addSimulatorListener(listener);
      }
      for (final var circuit : project.getLogisimFile().getCircuits()) {
        currentCircuits.add(circuit);
        circuitOwners.put(circuit, project);
        if (!circuitListeners.containsKey(circuit)) {
          final CircuitListener listener =
              event -> circuitChanged(circuitOwners.get(event.getCircuit()), event);
          circuitListeners.put(circuit, listener);
          circuit.addCircuitListener(listener);
        }
      }
    }
    final var removedCircuits = new ArrayList<Circuit>();
    for (final var circuit : circuitListeners.keySet()) {
      if (!currentCircuits.contains(circuit)) removedCircuits.add(circuit);
    }
    for (final var circuit : removedCircuits) {
      circuit.removeCircuitListener(circuitListeners.remove(circuit));
      circuitOwners.remove(circuit);
    }
    final var removed = new ArrayList<Project>();
    for (final var project : listeners.keySet()) {
      if (!current.contains(project)) removed.add(project);
    }
    for (final var project : removed) {
      project.removeProjectListener(listeners.remove(project));
      project.removeLibraryListener(libraryListeners.remove(project));
      project.getSimulator().removeSimulatorListener(simulatorListeners.remove(project));
      revisions.remove(project);
      operationLedger.forget(project);
      activeOperationIds.remove(project);
      ownedActions.remove(project);
      pendingCircuitUris.remove(project);
    }
  }

  private void projectChanged(Project project, ProjectEvent event) {
    switch (event.getAction()) {
      case ProjectEvent.ACTION_COMPLETE, ProjectEvent.UNDO_COMPLETE, ProjectEvent.REDO_COMPLETE,
          ProjectEvent.ACTION_SET_CURRENT, ProjectEvent.ACTION_SET_FILE -> {
        final var nextRevision = revision(project) + 1;
        revisions.put(project, nextRevision);
        if (event.getAction() == ProjectEvent.ACTION_COMPLETE) {
          final var operationId = activeOperationIds.get(project);
          final var action = project.getLastAction();
          if (operationId != null && action != null) {
            ownedActions
                .computeIfAbsent(project, ignored -> new IdentityHashMap<>())
                .put(action, operationId);
          }
        }
        pruneOwnedActions(project);
        final var affected = affectedResources(project, event);
        changeJournal.record(
            projectEventType(event),
            registry.projectId(project),
            nextRevision,
            affected,
            activeOperationIds.get(project));
        if (event.getAction() == ProjectEvent.ACTION_COMPLETE
            || event.getAction() == ProjectEvent.UNDO_COMPLETE
            || event.getAction() == ProjectEvent.REDO_COMPLETE) {
          pendingCircuitUris.remove(project);
        }
        if (event.getAction() == ProjectEvent.ACTION_SET_FILE) refresh();
      }
      default -> { }
    }
  }

  private void circuitChanged(Project project, CircuitEvent event) {
    if (project == null || event.getAction() != CircuitEvent.TRANSACTION_DONE) return;
    final var replacements = event.getResult().getReplacementMap(event.getCircuit());
    for (final var original : replacements.getRemovals()) {
      final var successors = replacements.getReplacementsFor(original);
      if (successors != null && successors.size() == 1) {
        registry.linkComponentId(original, successors.iterator().next());
      }
    }
    // Circuit TRANSACTION_DONE fires before Project ACTION_COMPLETE. Remember every modified
    // circuit, then publish it at the authoritative project revision boundary.
    pendingCircuitUris
        .computeIfAbsent(project, ignored -> new LinkedHashSet<>())
        .add(circuitResourceUri(project, event.getCircuit()));
  }

  private void libraryChanged(Project project, LibraryEvent event) {
    if (project == null || event.getAction() == LibraryEvent.DIRTY_STATE) return;
    refresh();
    changeJournal.record(
        libraryEventType(event),
        registry.projectId(project),
        revision(project),
        libraryAffectedResources(project, event),
        activeOperationIds.get(project));
  }

  private void simulatorChanged(Project project, String type, Simulator.Event event) {
    final Runnable publish = () -> publishSimulatorChange(project, type, event);
    if (SwingUtilities.isEventDispatchThread()) publish.run();
    else executor.run(publish);
  }

  private void publishSimulatorChange(Project project, String type, Simulator.Event event) {
    if (!listeners.containsKey(project)) return;
    final var resources = new ArrayList<String>();
    resources.add(projectResourceUri(project));
    resources.add(simulationResourceUri(project));
    final var state = event.getSource().getCircuitState();
    if (state != null) resources.add(circuitResourceUri(project, state.getCircuit()));
    changeJournal.record(
        type,
        registry.projectId(project),
        revision(project),
        resources,
        activeOperationIds.get(project));
  }

  private Collection<String> libraryAffectedResources(Project project, LibraryEvent event) {
    final var resources = new ArrayList<String>();
    resources.add(PROJECTS_URI);
    resources.add(projectResourceUri(project));
    if (event.getData() instanceof Circuit circuit) {
      resources.add(circuitResourceUri(project, circuit));
    }
    return resources;
  }

  private static String libraryEventType(LibraryEvent event) {
    return switch (event.getAction()) {
      case LibraryEvent.ADD_TOOL -> "library_tool_added";
      case LibraryEvent.REMOVE_TOOL -> "library_tool_removed";
      case LibraryEvent.MOVE_TOOL -> "library_tool_moved";
      case LibraryEvent.ADD_LIBRARY -> "library_added";
      case LibraryEvent.REMOVE_LIBRARY -> "library_removed";
      case LibraryEvent.SET_MAIN -> "main_circuit_changed";
      case LibraryEvent.SET_NAME -> "project_name_changed";
      default -> "library_changed";
    };
  }

  private Collection<String> affectedResources(Project project, ProjectEvent event) {
    final var resources = new ArrayList<String>();
    resources.add(PROJECTS_URI);
    resources.add(projectResourceUri(project));
    final var circuit = project.getCurrentCircuit();
    if (circuit != null) resources.add(circuitResourceUri(project, circuit));
    if (event.getOldData() instanceof Circuit oldCircuit) {
      resources.add(circuitResourceUri(project, oldCircuit));
    }
    if (event.getData() instanceof Circuit newCircuit) {
      resources.add(circuitResourceUri(project, newCircuit));
    }
    final var pending = pendingCircuitUris.get(project);
    if (pending != null) resources.addAll(pending);
    return resources;
  }

  private static String projectEventType(ProjectEvent event) {
    return switch (event.getAction()) {
      case ProjectEvent.ACTION_COMPLETE -> "action_complete";
      case ProjectEvent.UNDO_COMPLETE -> "undo_complete";
      case ProjectEvent.REDO_COMPLETE -> "redo_complete";
      case ProjectEvent.ACTION_SET_CURRENT -> "current_circuit_changed";
      case ProjectEvent.ACTION_SET_FILE -> "project_file_changed";
      default -> "project_changed";
    };
  }

  private String projectResourceUri(Project project) {
    return SNAPSHOT_URI_PREFIX + registry.projectId(project) + "/snapshot";
  }

  private String circuitResourceUri(Project project, Circuit circuit) {
    return SNAPSHOT_URI_PREFIX
        + registry.projectId(project)
        + "/circuit/"
        + registry.circuitId(circuit);
  }

  private String simulationResourceUri(Project project) {
    return SNAPSHOT_URI_PREFIX + registry.projectId(project) + SIMULATION_URI_SUFFIX;
  }

  private void validateResourceUri(String uri) throws Exception {
    if (uri == null || uri.isBlank()) throw rpc(-32602, "uri is required");
    if (PROJECTS_URI.equals(uri)) return;
    if (!uri.startsWith(SNAPSHOT_URI_PREFIX)) {
      throw rpc(-32004, "Unknown resource URI: " + uri);
    }
    final var parts = uri.substring(SNAPSHOT_URI_PREFIX.length()).split("/", -1);
    if (parts.length != 2 && parts.length != 3) {
      throw rpc(-32004, "Unknown resource URI: " + uri);
    }
    final var project = registry.resolve(parts[0]);
    if (project == null) throw rpc(-32001, "Unknown project resource");
    if (parts.length == 2 && "snapshot".equals(parts[1])) return;
    if (parts.length == 2 && "simulation".equals(parts[1])) return;
    if (parts.length == 3 && "circuit".equals(parts[1])
        && registry.resolveCircuit(project, parts[2]) != null) return;
    throw rpc(-32004, "Unknown resource URI: " + uri);
  }

  private JsonObject listProjects() {
    refresh();
    final var result = new JsonObject();
    final var values = new JsonArray();
    for (final var project : registry.projects()) values.add(getProject(project));
    result.add("projects", values);
    result.addProperty("count", values.size());
    return result;
  }

  private JsonObject getProject(Project project) {
    final var result = McpSnapshot.project(project, registry, revision(project));
    final var loader = project.getLogisimFile().getLoader();
    if (loader != null && loader.getMainFile() != null) result.addProperty("file", loader.getMainFile().getAbsolutePath());
    return result;
  }

  private JsonObject listCircuits(Project project) {
    final var result = getProject(project);
    result.remove("circuits");
    final var circuits = new JsonArray();
    for (final var circuit : project.getLogisimFile().getCircuits()) {
      final var item = new JsonObject();
      item.addProperty("circuitId", registry.circuitId(circuit));
      item.addProperty("name", circuit.getName());
      item.addProperty("main", circuit == project.getLogisimFile().getMainCircuit());
      item.addProperty("current", circuit == project.getCurrentCircuit());
      item.addProperty("componentCount", circuit.getNonWires().size());
      item.addProperty("wireCount", circuit.getWires().size());
      circuits.add(item);
    }
    result.add("circuits", circuits);
    return result;
  }

  private JsonElement newProject(JsonObject args) throws Exception {
    return lifecycleWrite(
        "new_project",
        args,
        () -> {
          final var project = createBlankProject();
          registry.register(project);
          refresh();
          final var result = getProject(project);
          result.addProperty("created", true);
          return result;
        });
  }

  private JsonElement openProject(JsonObject args) throws Exception {
    return lifecycleWrite(
        "open_project",
        args,
        () -> {
          final var source = pathPolicy.requireAllowed(null, required(args, "path"), "open_project");
          validateProjectSource(source);
          final var alreadyOpen = Projects.findProjectFor(source);
          if (alreadyOpen != null) {
            final var result = getProject(alreadyOpen);
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
          if (project == null) throw rpc(-32006, "Project open failed");
          registry.register(project);
          refresh();
          final var result = getProject(project);
          result.addProperty("alreadyOpen", false);
          return result;
        });
  }

  private JsonElement closeProject(JsonObject args) throws Exception {
    return onModel(
        () ->
            lifecycleOperationLedger.execute(
                this,
                "close_project",
                args,
                () -> {
                  final var project = requireProject(args);
                  checkRevision(project, args);
                  final var dirty = project.isFileDirty();
                  if (dirty && !booleanValue(args, "confirm", false)) {
                    final var data = new JsonObject();
                    data.addProperty("projectId", registry.projectId(project));
                    data.addProperty("name", project.getLogisimFile().getName());
                    data.addProperty("dirty", true);
                    data.addProperty("requiresConfirmation", true);
                    throw new McpRpcException(
                        -32012, "Closing a dirty project requires confirm=true", data);
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
                  refresh();
                  final var result = new JsonObject();
                  result.addProperty("closedProjectId", closedProjectId);
                  result.addProperty("discardedUnsavedChanges", dirty);
                  if (replacement != null) {
                    registry.register(replacement);
                    refresh();
                    result.addProperty("replacementProjectId", registry.projectId(replacement));
                  }
                  return result;
                }));
  }

  private JsonObject listLibraries(Project project) {
    final var result = operation(project, project.getCurrentCircuit());
    final var loaded = new JsonArray();
    for (final var library : project.getLogisimFile().getLibraries()) {
      loaded.add(libraryJson(project, library, false));
    }
    final var availableBuiltins = new JsonArray();
    final var loader = project.getLogisimFile().getLoader();
    if (loader != null) {
      for (final var library : loader.getBuiltin().getLibraries()) {
        final var item = libraryJson(project, library, true);
        item.addProperty("loaded", containsLibrary(project, library));
        availableBuiltins.add(item);
      }
    }
    result.add("libraries", loaded);
    result.add("availableBuiltins", availableBuiltins);
    result.add("allowedRoots", pathPolicy.allowedRootsJson(project));
    return result;
  }

  private JsonElement loadLibrary(JsonObject args) throws Exception {
    return write(
        "load_library",
        args,
        project -> {
          final var loader = project.getLogisimFile().getLoader();
          if (loader == null) throw rpc(-32006, "Project has no library loader");
          final var kind = required(args, "kind").trim().toLowerCase(Locale.ROOT);
          final Library library;
          switch (kind) {
            case "builtin" -> {
              final var name = required(args, "name").trim();
              library = findBuiltin(loader, name);
              if (library == null) throw rpc(-32602, "Unknown built-in library: " + name);
            }
            case "file" -> {
              final var source =
                  pathPolicy.requireAllowed(project, required(args, "path"), "load_library");
              validateProjectSource(source);
              if (LibraryManager.instance.findReference(project.getLogisimFile(), source) != null) {
                throw rpc(-32013, "Library is already loaded by this project");
              }
              library = quietLoadLogisimLibrary(source);
            }
            case "jar" -> {
              if (!booleanValue(args, "confirm", false)) {
                final var data = new JsonObject();
                data.addProperty("requiresConfirmation", true);
                data.addProperty("kind", "jar");
                throw new McpRpcException(-32012, "Loading a JAR library executes external Java code; pass confirm=true to proceed", data);
              }
              final var source =
                  pathPolicy.requireAllowed(project, required(args, "path"), "load_library");
              if (!Files.isRegularFile(source.toPath()) || !Files.isReadable(source.toPath())) {
                throw rpc(-32602, "path must identify a readable JAR file");
              }
              if (!source.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw rpc(-32602, "JAR library path must end with .jar");
              }
              library = quietLoadJarLibrary(source, required(args, "className").trim());
            }
            default -> throw rpc(-32602, "kind must be builtin, file, or jar");
          }
          if (containsLibrary(project, library)) throw rpc(-32013, "Library is already loaded");
          try {
            project.doAction(
                LogisimFileActions.loadLibraryQuiet(library, project.getLogisimFile()));
          } catch (IllegalArgumentException e) {
            throw rpc(-32013, "Library conflicts with the current project: " + safeMessage(e));
          }
          final var result = operation(project, project.getCurrentCircuit());
          result.add("library", libraryJson(project, library, false));
          return result;
        });
  }

  private JsonElement unloadLibrary(JsonObject args) throws Exception {
    return write(
        "unload_library",
        args,
        project -> {
          final var library = findLoadedLibrary(project, required(args, "libraryId"));
          if (library == null) throw rpc(-32007, "Unknown libraryId");
          if (!booleanValue(args, "confirm", false)) {
            final var data = new JsonObject();
            data.add("library", libraryJson(project, library, false));
            data.addProperty("requiresConfirmation", true);
            throw new McpRpcException(-32012, "Unloading a library requires confirm=true", data);
          }
          final var reason = project.getLogisimFile().getUnloadLibraryMessage(library);
          if (reason != null) {
            final var data = new JsonObject();
            data.add("library", libraryJson(project, library, false));
            data.addProperty("reason", reason);
            throw new McpRpcException(-32014, "Library is still in use", data);
          }
          project.doAction(LogisimFileActions.unloadLibrary(library));
          return operation(project, project.getCurrentCircuit());
        });
  }

  private JsonObject libraryJson(Project project, Library library, boolean builtin) {
    final var item = new JsonObject();
    item.addProperty("libraryId", libraryId(project, library));
    item.addProperty("name", library.getName());
    item.addProperty("displayName", library.getDisplayName());
    item.addProperty("kind", libraryKind(library, builtin));
    item.addProperty("toolCount", library.getTools().size());
    item.addProperty("nestedLibraryCount", library.getLibraries().size());
    item.addProperty("dirty", library.isDirty());
    final var loader = project.getLogisimFile().getLoader();
    if (!builtin && loader != null) {
      try {
        item.addProperty("descriptor", loader.getDescriptor(library));
      } catch (RuntimeException ignored) {
        // Some in-memory libraries intentionally have no persisted descriptor.
      }
    }
    if (library instanceof LoadedLibrary loaded) {
      final var sourceLoader =
          loaded.getBase() instanceof LogisimFile file ? file.getLoader() : null;
      final var source = sourceLoader == null ? null : sourceLoader.getMainFile();
      if (source != null) item.addProperty("sourcePath", source.getAbsolutePath());
    }
    return item;
  }

  private static String libraryKind(Library library, boolean builtin) {
    if (builtin) return "builtin";
    if (library instanceof LoadedLibrary loaded
        && loaded.getBase() instanceof LogisimFile) return "file";
    if (library instanceof LoadedLibrary) return "jar";
    return "builtin";
  }

  private String libraryId(Project project, Library library) {
    return libraryIds.computeIfAbsent(library, ignored -> UUID.randomUUID().toString());
  }

  private Library findLoadedLibrary(Project project, String id) {
    for (final var library : project.getLogisimFile().getLibraries()) {
      if (id.equals(libraryId(project, library))
          || id.equalsIgnoreCase(library.getName())
          || id.equalsIgnoreCase(library.getDisplayName())) return library;
    }
    return null;
  }

  private static Library findBuiltin(Loader loader, String name) {
    for (final var library : loader.getBuiltin().getLibraries()) {
      if (name.equalsIgnoreCase(library.getName())
          || name.equalsIgnoreCase(library.getDisplayName())) return library;
    }
    return null;
  }

  private static boolean containsLibrary(Project project, Library candidate) {
    for (final var library : project.getLogisimFile().getLibraries()) {
      if (library == candidate || library.getName().equalsIgnoreCase(candidate.getName())) return true;
    }
    return false;
  }

  private static Library quietLoadLogisimLibrary(File source) throws McpRpcException {
    try {
      final var library = new QuietLoader().loadLogisimLibrary(source);
      if (library == null) throw rpc(-32006, "Library load failed");
      return library;
    } catch (RuntimeException e) {
      throw rpc(-32006, "Library load failed: " + safeMessage(e));
    }
  }

  private static Library quietLoadJarLibrary(File source, String className)
      throws McpRpcException {
    try {
      final var library = new QuietLoader().loadJarLibrary(source, className);
      if (library == null) throw rpc(-32006, "JAR library load failed");
      return library;
    } catch (RuntimeException e) {
      throw rpc(-32006, "JAR library load failed: " + safeMessage(e));
    }
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
    final var foundAutosave = autosave;
    if (Files.exists(foundAutosave)) {
      final var data = new JsonObject();
      data.addProperty("path", source.getPath());
      data.addProperty("autosavePath", foundAutosave.toString());
      throw new McpRpcException(
          -32012,
          "An autosave exists; resolve it in the Logisim UI before using MCP open",
          data);
    }
  }

  private JsonElement lifecycleWrite(String toolName, JsonObject args, LifecycleWrite operation)
      throws Exception {
    return onModel(
        () ->
            lifecycleOperationLedger.execute(
                this,
                toolName,
                args,
                () -> {
                  requireLifecycleRevision(args);
                  return operation.execute();
                }));
  }

  private static void requireLifecycleRevision(JsonObject args) throws McpRpcException {
    if (!args.has("expectedRevision") || args.get("expectedRevision").isJsonNull()) {
      throw rpc(-32602, "expectedRevision is required for mutating tools");
    }
    try {
      if (args.get("expectedRevision").getAsLong() != 0) {
        throw rpc(-32009, "Lifecycle tools require expectedRevision=0");
      }
    } catch (NumberFormatException | IllegalStateException e) {
      throw rpc(-32602, "expectedRevision must be an integer");
    }
  }

  private static void assignProject(LogisimFile file, Project project) {
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    for (final var library : file.getLibraries()) {
      if (library instanceof LoadedLibrary loaded
          && loaded.getBase() instanceof LogisimFile nested) assignProject(nested, project);
      else if (library instanceof LogisimFile nested) assignProject(nested, project);
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
    public int showOptions(
        String message, String title, String[] options, int initialSelection) {
      return JOptionPane.CLOSED_OPTION;
    }
  }

  private static final class QuietLoadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    QuietLoadException(String message) {
      super(message);
    }
  }

  private JsonObject snapshot(Project project, String circuitId, boolean detail) throws McpJsonRpcDispatcher.McpRpcException {
    final var circuit = circuitId == null ? project.getCurrentCircuit() : registry.resolveCircuit(project, circuitId);
    if (circuit == null) throw rpc(-32007, "Unknown circuitId");
    return McpSnapshot.circuit(project, circuit, registry, revision(project), detail);
  }

  private JsonObject findComponents(Project project, JsonObject args) throws McpJsonRpcDispatcher.McpRpcException {
    final var result = snapshot(project, optional(args, "circuitId"), true);
    final var factory = optional(args, "factory");
    final var label = optional(args, "label");
    final var filtered = new JsonArray();
    for (final var item : result.getAsJsonArray("components")) {
      final var object = item.getAsJsonObject();
      if (factory != null && !factory.equalsIgnoreCase(object.get("factory").getAsString()) && !factory.equalsIgnoreCase(object.get("displayName").getAsString())) continue;
      if (label != null) {
        final var attrs = object.getAsJsonObject("attributes");
        if (attrs == null || !label.equals(attrs.get("label") == null ? "" : attrs.get("label").getAsString())) continue;
      }
      filtered.add(object);
    }
    result.add("components", filtered);
    return result;
  }

  private JsonObject availableTools(Project project) {
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    result.add("tools", McpSnapshot.availableTools(project));
    return result;
  }

  private JsonElement addComponent(JsonObject args) throws Exception {
    return write("add_component", args, project -> {
      final var circuit = circuit(project, optional(args, "circuitId"));
      final var factoryName = required(args, "factory");
      final var factory = findFactory(project.getLogisimFile(), factoryName);
      if (factory == null) throw rpc(-32602, "Unknown component factory: " + factoryName);
      final var attrs = factory.createAttributeSet();
      applyAttributes(attrs, args.getAsJsonObject("attributes"));
      final var component = factory.createComponent(Location.create(requiredInt(args, "x"), requiredInt(args, "y"), true), attrs);
      if (circuit.hasConflict(component)) throw rpc(-32003, "Component conflicts at requested location");
      final var mutation = new CircuitMutation(circuit);
      mutation.add(component);
      project.doAction(mutation.toAction(null));
      final var result = operation(project, circuit);
      result.addProperty("componentId", registry.componentId(component));
      result.add("component", McpSnapshot.component(registry.componentId(component), component, true));
      return result;
    });
  }

  private JsonElement removeComponents(JsonObject args) throws Exception {
    return write("remove_components", args, project -> {
      final var ids = strings(args, "componentIds");
      if (ids.isEmpty()) throw rpc(-32602, "componentIds must not be empty");
      Circuit circuit = null;
      final var components = new ArrayList<Component>();
      for (final var id : ids) {
        final var found = findComponent(project, id);
        if (found == null || found instanceof Wire) {
          throw rpc(-32002, "Unknown componentId: " + id);
        }
        if (circuit == null) circuit = circuitFor(project, found);
        if (circuitFor(project, found) != circuit) {
          throw rpc(-32602, "Components must share a circuit");
        }
        components.add(found);
      }
      final var mutation = new CircuitMutation(circuit);
      for (final var component : components) mutation.remove(component);
      project.doAction(mutation.toAction(null));
      return operation(project, circuit);
    });
  }

  private JsonElement addWire(JsonObject args) throws Exception {
    return write("add_wire", args, project -> {
      final var circuit = circuit(project, optional(args, "circuitId"));
      final var wire = Wire.create(Location.create(requiredInt(args, "x1"), requiredInt(args, "y1"), true), Location.create(requiredInt(args, "x2"), requiredInt(args, "y2"), true));
      if (wire.getEnd0().equals(wire.getEnd1())) throw rpc(-32602, "Wire endpoints must differ");
      final var mutation = new CircuitMutation(circuit);
      mutation.add(wire);
      project.doAction(mutation.toAction(null));
      final var result = operation(project, circuit);
      result.addProperty("wireId", registry.componentId(wire));
      return result;
    });
  }

  private JsonElement removeWires(JsonObject args) throws Exception {
    return write("remove_wires", args, project -> {
      final var ids = strings(args, "wireIds");
      if (ids.isEmpty()) throw rpc(-32602, "wireIds must not be empty");
      Circuit circuit = null;
      final var wires = new ArrayList<Wire>();
      for (final var id : ids) {
        final var found = findComponent(project, id);
        if (!(found instanceof Wire wire)) throw rpc(-32002, "Unknown wireId: " + id);
        if (circuit == null) circuit = circuitFor(project, wire);
        wires.add(wire);
      }
      final var mutation = new CircuitMutation(circuit);
      for (final var wire : wires) mutation.remove(wire);
      project.doAction(mutation.toAction(null));
      return operation(project, circuit);
    });
  }

  private JsonElement setAttributes(JsonObject args) throws Exception {
    return write("set_component_attributes", args, project -> {
      final var component = findComponent(project, required(args, "componentId"));
      if (component == null) throw rpc(-32002, "Unknown componentId");
      final var circuit = circuitFor(project, component);
      final var values = args.getAsJsonObject("attributes");
      if (values == null) throw rpc(-32602, "attributes must be an object");
      final var mutation = new CircuitMutation(circuit);
      for (final var entry : values.entrySet()) {
        final var attribute = component.getAttributeSet().getAttribute(entry.getKey());
        if (attribute == null) throw rpc(-32602, "Unknown attribute: " + entry.getKey());
        mutation.set(component, attribute, parse(attribute, entry.getValue()));
      }
      project.doAction(mutation.toAction(null));
      return operation(project, circuit);
    });
  }

  private JsonElement moveComponents(JsonObject args) throws Exception {
    return write("move_components", args, project -> {
      final var values = args.getAsJsonArray("moves");
      if (values == null || values.isEmpty()) throw rpc(-32602, "moves must not be empty");
      Circuit circuit = null;
      final var originals = new ArrayList<Component>();
      final var replacements = new ArrayList<Component>();
      for (final var value : values) {
        final var move = value.getAsJsonObject();
        final var original = findComponent(project, required(move, "componentId"));
        if (original == null || original instanceof Wire) {
          throw rpc(-32002, "Unknown or non-movable component");
        }
        final var current = circuitFor(project, original);
        if (circuit == null) circuit = current;
        if (current != circuit) throw rpc(-32602, "Moves must share a circuit");
        originals.add(original);
        replacements.add(
            original
                .getFactory()
                .createComponent(
                    Location.create(requiredInt(move, "x"), requiredInt(move, "y"), true),
                    (AttributeSet) original.getAttributeSet().clone()));
      }
      final var mutation = new CircuitMutation(circuit);
      for (var i = 0; i < originals.size(); i++) {
        mutation.replace(originals.get(i), replacements.get(i));
      }
      project.doAction(mutation.toAction(null));
      final var moved = new JsonArray();
      for (var i = 0; i < originals.size(); i++) {
        final var id = registry.linkComponentId(originals.get(i), replacements.get(i));
        moved.add(McpSnapshot.component(id, replacements.get(i), true));
      }
      final var result = operation(project, circuit);
      result.add("components", moved);
      return result;
    });
  }

  private JsonElement rotateComponents(JsonObject args) throws Exception {
    return write("rotate_components", args, project -> {
      final var ids = strings(args, "componentIds");
      if (ids.isEmpty()) throw rpc(-32602, "componentIds must not be empty");
      final var quarterTurns = rotationQuarterTurns(args);
      Circuit circuit = null;
      final var originals = new ArrayList<Component>();
      final var replacements = new ArrayList<Component>();
      for (final var id : ids) {
        final var original = findComponent(project, id);
        if (original == null || original instanceof Wire) {
          throw rpc(-32002, "Unknown or non-rotatable componentId: " + id);
        }
        if (!original.getAttributeSet().containsAttribute(StdAttr.FACING)) {
          throw rpc(-32602, "Component does not support rotation: " + id);
        }
        final var current = circuitFor(project, original);
        if (circuit == null) circuit = current;
        if (current != circuit) throw rpc(-32602, "Components must share a circuit");
        originals.add(original);
        replacements.add(rotatedComponent(original, quarterTurns));
      }
      final var mutation = new CircuitMutation(circuit);
      for (var i = 0; i < originals.size(); i++) {
        mutation.replace(originals.get(i), replacements.get(i));
      }
      project.doAction(mutation.toAction(null));
      final var rotated = new JsonArray();
      for (var i = 0; i < originals.size(); i++) {
        final var id = registry.linkComponentId(originals.get(i), replacements.get(i));
        rotated.add(McpSnapshot.component(id, replacements.get(i), true));
      }
      final var result = operation(project, circuit);
      result.addProperty("quarterTurns", quarterTurns);
      result.add("components", rotated);
      return result;
    });
  }

  private JsonElement setCircuitAttributes(JsonObject args) throws Exception {
    return write("set_circuit_attributes", args, project -> {
      final var circuit = circuit(project, optional(args, "circuitId"));
      final var values = object(args, "attributes");
      if (values.isEmpty()) throw rpc(-32602, "attributes must not be empty");
      final var parsed = parseAttributes(circuit.getStaticAttributes(), values);
      final var mutation = new CircuitMutation(circuit);
      for (final var entry : parsed.entrySet()) {
        mutation.setForCircuit(entry.getKey(), entry.getValue());
      }
      project.doAction(mutation.toAction(null));
      final var result = operation(project, circuit);
      result.add("circuit", McpSnapshot.circuit(project, circuit, registry, revision(project), false));
      return result;
    });
  }

  private JsonElement batchEdit(JsonObject args) throws Exception {
    return write("batch_edit", args, project -> {
      final var operations = args.getAsJsonArray("operations");
      if (operations == null || operations.isEmpty()) {
        throw rpc(-32602, "operations must be a non-empty array");
      }
      final var state = new BatchState(project, circuit(project, optional(args, "circuitId")));
      for (var i = 0; i < operations.size(); i++) {
        final JsonObject edit;
        try {
          edit = operations.get(i).getAsJsonObject();
        } catch (RuntimeException e) {
          throw batchError(-32602, "Batch operation must be an object", i, null);
        }
        String type = null;
        try {
          type = batchOperationType(edit);
          applyBatchEdit(state, type, batchOperationArguments(edit));
        } catch (McpRpcException e) {
          throw batchError(e.code(), e.getMessage(), i, type);
        } catch (RuntimeException e) {
          throw batchError(-32602, "Invalid batch operation", i, type);
        }
      }

      final var mutation = state.toMutation();
      if (mutation.isEmpty()) throw rpc(-32602, "Batch does not contain any effective edits");
      project.doAction(mutation.toAction(null));
      state.linkStableIds();
      final var result = operation(project, state.circuit);
      result.addProperty("operationCount", operations.size());
      result.add("created", state.createdResult());
      return result;
    });
  }

  private void applyBatchEdit(BatchState state, String type, JsonObject edit)
      throws McpRpcException {
    switch (type) {
      case "add_component" -> state.addComponent(edit);
      case "remove_components" -> state.removeComponents(strings(edit, "componentIds"));
      case "move_components" -> state.moveComponents(array(edit, "moves"));
      case "rotate_components" ->
          state.rotateComponents(strings(edit, "componentIds"), rotationQuarterTurns(edit));
      case "set_component_attributes" -> state.setComponentAttributes(edit);
      case "set_circuit_attributes" -> state.setCircuitAttributes(object(edit, "attributes"));
      case "add_wire" -> state.addWire(edit);
      case "remove_wires" -> state.removeWires(strings(edit, "wireIds"));
      default -> throw rpc(-32602, "Unsupported batch operation type: " + type);
    }
  }

  private Component rotatedComponent(Component original, int quarterTurns) {
    final var factory = original.getFactory();
    final var oldAttrs = original.getAttributeSet();
    final var newAttrs = (AttributeSet) oldAttrs.clone();
    var facing = oldAttrs.getValue(StdAttr.FACING);
    for (var i = 0; i < Math.abs(quarterTurns); i++) {
      facing = quarterTurns > 0 ? facing.getRight() : facing.getLeft();
    }
    newAttrs.setValue(StdAttr.FACING, facing);
    final var oldBounds = factory.getOffsetBounds(oldAttrs);
    final var newBounds = factory.getOffsetBounds(newAttrs);
    final var dx = snapToGrid(oldBounds.getCenterX() - newBounds.getCenterX());
    final var dy = snapToGrid(oldBounds.getCenterY() - newBounds.getCenterY());
    return factory.createComponent(original.getLocation().translate(dx, dy), newAttrs);
  }

  private static int rotationQuarterTurns(JsonObject args) throws McpRpcException {
    if (args.has("quarterTurns") && !args.get("quarterTurns").isJsonNull()) {
      final int turns;
      try {
        turns = args.get("quarterTurns").getAsInt();
      } catch (RuntimeException e) {
        throw rpc(-32602, "quarterTurns must be an integer");
      }
      final var normalized = turns % 4;
      if (normalized == 0) throw rpc(-32602, "quarterTurns must rotate the component");
      return normalized == 3 ? -1 : normalized == -3 ? 1 : normalized;
    }
    final var direction = optional(args, "direction");
    if (direction == null) return 1;
    return switch (direction.trim().toLowerCase(Locale.ROOT)) {
      case "clockwise", "right", "cw", "90" -> 1;
      case "counterclockwise", "counter-clockwise", "anticlockwise", "left", "ccw", "-90" -> -1;
      case "180", "reverse" -> 2;
      default -> throw rpc(-32602, "Unknown rotation direction: " + direction);
    };
  }

  private static int snapToGrid(int value) {
    return value < 0 ? -((-value + 5) / 10) * 10 : ((value + 5) / 10) * 10;
  }

  private static String batchOperationType(JsonObject edit) throws McpRpcException {
    var type = optional(edit, "type");
    if (type == null) type = optional(edit, "op");
    if (type == null) type = optional(edit, "operation");
    if (type == null) type = optional(edit, "tool");
    if (type == null) type = optional(edit, "name");
    if (type == null) throw rpc(-32602, "Batch operation type is required");
    return type.trim().toLowerCase(Locale.ROOT);
  }

  private static JsonObject batchOperationArguments(JsonObject edit) throws McpRpcException {
    final var nested = edit.get("arguments");
    if (nested == null || nested.isJsonNull()) return edit;
    if (!nested.isJsonObject()) throw rpc(-32602, "Batch operation arguments must be an object");
    final var result = nested.getAsJsonObject().deepCopy();
    if (edit.has("ref") && !result.has("ref")) result.add("ref", edit.get("ref"));
    if (edit.has("circuitId") && !result.has("circuitId")) {
      result.add("circuitId", edit.get("circuitId"));
    }
    return result;
  }

  private static McpRpcException batchError(
      int code, String message, int operationIndex, String operationType) {
    final var data = new JsonObject();
    data.addProperty("operationIndex", operationIndex);
    if (operationType != null) data.addProperty("operationType", operationType);
    return new McpRpcException(code, message, data);
  }

  private static JsonArray array(JsonObject object, String name) throws McpRpcException {
    final var value = object.get(name);
    if (value == null || !value.isJsonArray()) throw rpc(-32602, name + " must be an array");
    return value.getAsJsonArray();
  }

  private static JsonObject object(JsonObject object, String name) throws McpRpcException {
    final var value = object.get(name);
    if (value == null || !value.isJsonObject()) throw rpc(-32602, name + " must be an object");
    return value.getAsJsonObject();
  }

  private static Map<Attribute<?>, Object> parseAttributes(AttributeSet set, JsonObject values)
      throws McpRpcException {
    final var result = new LinkedHashMap<Attribute<?>, Object>();
    for (final var entry : values.entrySet()) {
      final var attribute = set.getAttribute(entry.getKey());
      if (attribute == null) throw rpc(-32602, "Unknown attribute: " + entry.getKey());
      if (set.isReadOnly(attribute)) {
        throw rpc(-32602, "Read-only attribute: " + entry.getKey());
      }
      result.put(attribute, parse(attribute, entry.getValue()));
    }
    return result;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void setParsedAttribute(AttributeSet set, Attribute<?> attribute, Object value) {
    set.setValue((Attribute) attribute, value);
  }

  private final class BatchState {
    private final Project project;
    private final Circuit circuit;
    private final CircuitMutation mutation;
    private final Map<String, Component> objects = new LinkedHashMap<>();
    private final Map<String, Component> references = new LinkedHashMap<>();
    private final List<Component[]> replacements = new ArrayList<>();
    private final List<CreatedObject> created = new ArrayList<>();

    private BatchState(Project project, Circuit circuit) {
      this.project = project;
      this.circuit = circuit;
      mutation = new CircuitMutation(circuit);
      for (final var component : circuit.getNonWires()) {
        objects.put(registry.componentId(component), component);
      }
      for (final var wire : circuit.getWires()) objects.put(registry.componentId(wire), wire);
    }

    private CircuitMutation toMutation() {
      return mutation;
    }

    private void addComponent(JsonObject edit) throws McpRpcException {
      ensureCircuit(edit);
      final var factoryName = required(edit, "factory");
      final var factory = findFactory(project.getLogisimFile(), factoryName);
      if (factory == null) throw rpc(-32602, "Unknown component factory: " + factoryName);
      final var attrs = factory.createAttributeSet();
      applyAttributes(attrs, edit.getAsJsonObject("attributes"));
      final var component =
          factory.createComponent(
              Location.create(requiredInt(edit, "x"), requiredInt(edit, "y"), true), attrs);
      if (circuit.hasConflict(component)) {
        throw rpc(-32003, "Component conflicts at requested location");
      }
      mutation.add(component);
      addReference(edit, component);
      created.add(new CreatedObject(optional(edit, "ref"), component, false));
    }

    private void addWire(JsonObject edit) throws McpRpcException {
      ensureCircuit(edit);
      final var wire =
          Wire.create(
              Location.create(requiredInt(edit, "x1"), requiredInt(edit, "y1"), true),
              Location.create(requiredInt(edit, "x2"), requiredInt(edit, "y2"), true));
      if (wire.getEnd0().equals(wire.getEnd1())) {
        throw rpc(-32602, "Wire endpoints must differ");
      }
      mutation.add(wire);
      addReference(edit, wire);
      created.add(new CreatedObject(optional(edit, "ref"), wire, true));
    }

    private void removeComponents(List<String> ids) throws McpRpcException {
      if (ids.isEmpty()) throw rpc(-32602, "componentIds must not be empty");
      for (final var id : ids) {
        final var component = resolve(id);
        if (component instanceof Wire) throw rpc(-32002, "Unknown componentId: " + id);
        ensureNotCreated(component, id);
        mutation.remove(component);
        forget(component);
      }
    }

    private void removeWires(List<String> ids) throws McpRpcException {
      if (ids.isEmpty()) throw rpc(-32602, "wireIds must not be empty");
      for (final var id : ids) {
        final var component = resolve(id);
        if (!(component instanceof Wire)) throw rpc(-32002, "Unknown wireId: " + id);
        ensureNotCreated(component, id);
        mutation.remove(component);
        forget(component);
      }
    }

    private void moveComponents(JsonArray moves) throws McpRpcException {
      if (moves.isEmpty()) throw rpc(-32602, "moves must not be empty");
      for (final var value : moves) {
        if (!value.isJsonObject()) throw rpc(-32602, "moves entries must be objects");
        final var move = value.getAsJsonObject();
        final var original = resolve(required(move, "componentId"));
        if (original instanceof Wire) throw rpc(-32002, "Unknown or non-movable component");
        final var replacement =
            original
                .getFactory()
                .createComponent(
                    Location.create(requiredInt(move, "x"), requiredInt(move, "y"), true),
                    (AttributeSet) original.getAttributeSet().clone());
        replace(original, replacement);
      }
    }

    private void rotateComponents(List<String> ids, int quarterTurns) throws McpRpcException {
      if (ids.isEmpty()) throw rpc(-32602, "componentIds must not be empty");
      for (final var id : ids) {
        final var original = resolve(id);
        if (original instanceof Wire || !original.getAttributeSet().containsAttribute(StdAttr.FACING)) {
          throw rpc(-32602, "Component does not support rotation: " + id);
        }
        replace(original, rotatedComponent(original, quarterTurns));
      }
    }

    private void setComponentAttributes(JsonObject edit) throws McpRpcException {
      final var original = resolve(required(edit, "componentId"));
      if (original instanceof Wire) throw rpc(-32002, "Unknown componentId");
      final var values = object(edit, "attributes");
      if (values.isEmpty()) throw rpc(-32602, "attributes must not be empty");
      final var attrs = (AttributeSet) original.getAttributeSet().clone();
      for (final var entry : parseAttributes(attrs, values).entrySet()) {
        setParsedAttribute(attrs, entry.getKey(), entry.getValue());
      }
      replace(original, original.getFactory().createComponent(original.getLocation(), attrs));
    }

    private void setCircuitAttributes(JsonObject values) throws McpRpcException {
      if (values.isEmpty()) throw rpc(-32602, "attributes must not be empty");
      for (final var entry : parseAttributes(circuit.getStaticAttributes(), values).entrySet()) {
        mutation.setForCircuit(entry.getKey(), entry.getValue());
      }
    }

    private void ensureCircuit(JsonObject edit) throws McpRpcException {
      final var circuitId = optional(edit, "circuitId");
      if (circuitId != null && registry.resolveCircuit(project, circuitId) != circuit) {
        throw rpc(-32602, "All batch operations must target the batch circuit");
      }
    }

    private Component resolve(String id) throws McpRpcException {
      final var byId = objects.get(id);
      if (byId != null) return byId;
      final var reference = id.startsWith("@") ? id.substring(1) : id;
      final var byReference = references.get(reference);
      if (byReference != null) return byReference;
      throw rpc(-32002, "Unknown objectId or batch ref: " + id);
    }

    private void addReference(JsonObject edit, Component component) throws McpRpcException {
      final var reference = optional(edit, "ref");
      if (reference == null) return;
      if (reference.isBlank()) throw rpc(-32602, "ref must not be empty");
      if (references.putIfAbsent(reference, component) != null) {
        throw rpc(-32602, "Duplicate batch ref: " + reference);
      }
    }

    private void replace(Component original, Component replacement) {
      mutation.replace(original, replacement);
      replacements.add(new Component[] {original, replacement});
      for (final var entry : objects.entrySet()) {
        if (entry.getValue() == original) entry.setValue(replacement);
      }
      for (final var entry : references.entrySet()) {
        if (entry.getValue() == original) entry.setValue(replacement);
      }
      for (var i = 0; i < created.size(); i++) {
        final var value = created.get(i);
        if (value.component == original) {
          created.set(i, new CreatedObject(value.reference, replacement, value.wire));
        }
      }
    }

    private void forget(Component component) {
      objects.values().removeIf(value -> value == component);
      references.values().removeIf(value -> value == component);
      created.removeIf(value -> value.component == component);
    }

    private void ensureNotCreated(Component component, String id) throws McpRpcException {
      for (final var value : created) {
        if (value.component == component) {
          throw rpc(-32602, "Cannot remove an object created earlier in the same batch: " + id);
        }
      }
    }

    private void linkStableIds() {
      for (final var replacement : replacements) {
        registry.linkComponentId(replacement[0], replacement[1]);
      }
    }

    private JsonArray createdResult() {
      final var result = new JsonArray();
      for (final var value : created) {
        final var item = new JsonObject();
        if (value.reference != null) item.addProperty("ref", value.reference);
        final var id = registry.componentId(value.component);
        item.addProperty(value.wire ? "wireId" : "componentId", id);
        item.add("object", McpSnapshot.component(id, value.component, true));
        result.add(item);
      }
      return result;
    }
  }

  private static final class CreatedObject {
    private final String reference;
    private final Component component;
    private final boolean wire;

    private CreatedObject(String reference, Component component, boolean wire) {
      this.reference = reference;
      this.component = component;
      this.wire = wire;
    }
  }

  private JsonElement undo(JsonObject args) throws Exception {
    return write(
        "undo",
        args,
        project -> {
          requireOwnedStackHead(project, project.getLastAction(), required(args, "targetOperationId"), "undo");
          project.undoAction();
          return operation(project, project.getCurrentCircuit());
        });
  }

  private JsonElement redo(JsonObject args) throws Exception {
    return write(
        "redo",
        args,
        project -> {
          requireOwnedStackHead(project, project.getLastRedoAction(), required(args, "targetOperationId"), "redo");
          project.redoAction();
          return operation(project, project.getCurrentCircuit());
        });
  }
  private JsonElement saveProject(JsonObject args) throws Exception {
    return write("save_project", args, this::save);
  }

  private JsonElement save(Project project) throws Exception {
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
    return write("save_project_as", args, project -> {
      final var rawPath = required(args, "path");
      final var destination = pathPolicy.requireAllowed(project, rawPath, "save_project_as");
      validateSaveTarget(project, destination, true);
      final var existed = Files.exists(destination.toPath());
      if (existed && !booleanValue(args, "confirm", false)) {
        final var data = new JsonObject();
        data.addProperty("path", destination.getPath());
        data.addProperty("requiresConfirmation", true);
        throw new McpRpcException(-32012, "Refusing to overwrite an existing file without confirm=true", data);
      }
      mcpDoSave(project, destination);
      return savedProject(project, destination, existed);
    });
  }

  private void validateSaveTarget(Project project, File target, boolean requireExtension)
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

  private JsonObject savedProject(Project project, File target, boolean overwrote) {
    final var result = getProject(project);
    result.addProperty("savedPath", target.getPath());
    result.addProperty("overwrote", overwrote);
    return result;
  }

  private static File canonicalPath(File path) throws McpRpcException {
    try {
      return path.getCanonicalFile();
    } catch (IOException e) {
      throw rpc(-32602, "path cannot be resolved");
    }
  }

  private JsonElement createCircuit(JsonObject args) throws Exception {
    return write("create_circuit", args, project -> {
      final var name = required(args, "name").trim();
      validateCircuitName(project, name, null);
      final var circuit = new Circuit(name, project.getLogisimFile(), project);
      project.doAction(LogisimFileActions.addCircuit(circuit));
      project.setCurrentCircuit(circuit);
      final var result = operation(project, circuit);
      result.addProperty("circuitId", registry.circuitId(circuit));
      result.addProperty("name", circuit.getName());
      return result;
    });
  }

  private JsonElement removeCircuit(JsonObject args) throws Exception {
    return write("remove_circuit", args, project -> {
      final var circuit = circuit(project, required(args, "circuitId"));
      if (!booleanValue(args, "confirm", false)) {
        final var data = new JsonObject();
        data.addProperty("circuitId", registry.circuitId(circuit));
        data.addProperty("name", circuit.getName());
        data.addProperty("requiresConfirmation", true);
        throw new McpRpcException(-32012, "Removing a circuit requires confirm=true", data);
      }
      if (project.getLogisimFile().getCircuitCount() <= 1) throw rpc(-32013, "Cannot remove the last circuit");
      if (!project.getDependencies().canRemove(circuit)) throw rpc(-32014, "Circuit is still referenced by another circuit");
      final var wasCurrent = project.getCurrentCircuit() == circuit;
      project.doAction(LogisimFileActions.removeCircuit(circuit));
      if (wasCurrent) project.setCurrentCircuit(project.getLogisimFile().getMainCircuit());
      return operation(project, project.getCurrentCircuit());
    });
  }

  private JsonElement renameCircuit(JsonObject args) throws Exception {
    return write("rename_circuit", args, project -> {
      final var circuit = circuit(project, required(args, "circuitId"));
      final var name = required(args, "name").trim();
      validateCircuitName(project, name, circuit);
      final var mutation = new CircuitMutation(circuit);
      mutation.setForCircuit(CircuitAttributes.NAME_ATTR, name);
      project.doAction(mutation.toAction(com.cburch.logisim.util.StringUtil.constantGetter("MCP rename circuit")));
      final var result = operation(project, circuit);
      result.addProperty("circuitId", registry.circuitId(circuit));
      result.addProperty("name", circuit.getName());
      return result;
    });
  }

  private JsonElement setMainCircuit(JsonObject args) throws Exception {
    return write("set_main_circuit", args, project -> {
      final var circuit = circuit(project, required(args, "circuitId"));
      if (project.getLogisimFile().getMainCircuit() == circuit) {
        final var result = operation(project, project.getCurrentCircuit());
        result.addProperty("mainCircuitId", registry.circuitId(circuit));
        return result;
      }
      project.doAction(LogisimFileActions.setMainCircuit(circuit));
      final var result = operation(project, project.getCurrentCircuit());
      result.addProperty("mainCircuitId", registry.circuitId(circuit));
      return result;
    });
  }

  private JsonElement switchCircuit(JsonObject args) throws Exception {
    return write("switch_circuit", args, project -> {
      final var circuit = circuit(project, required(args, "circuitId"));
      if (project.getCurrentCircuit() != circuit) project.setCurrentCircuit(circuit);
      return operation(project, circuit);
    });
  }

  private JsonElement simulatorReset(JsonObject args) throws Exception {
    return onModel(
        () -> {
          final var p = requireProject(args);
          p.getSimulator().reset();
          return simulatorState(p);
        });
  }

  private JsonElement simulatorStep(JsonObject args) throws Exception {
    return onModel(
        () -> {
          final var p = requireProject(args);
          p.getSimulator().step();
          return simulatorState(p);
        });
  }

  private JsonElement simulatorTick(JsonObject args) throws Exception {
    return onModel(() -> {
      final var p = requireProject(args);
      final int count = args.has("count") ? requiredInt(args, "count") : 1;
      if (count < 1 || count > MAX_MANUAL_TICKS) throw rpc(-32602, "count must be between 1 and " + MAX_MANUAL_TICKS);
      requireClockSource(p);
      p.getSimulator().tick(count);
      return simulatorState(p);
    });
  }

  private JsonElement setSimulatorMode(JsonObject args) throws Exception {
    return configureSimulatorNoRevision("set_simulator_mode", args);
  }

  private JsonElement configureSimulator(JsonObject args) throws Exception {
    return configureSimulatorNoRevision("configure_simulator", args);
  }

  private JsonElement configureSimulatorNoRevision(String toolName, JsonObject args) throws Exception {
    return onModel(() -> {
      final var project = requireProject(args);
      final var simulator = project.getSimulator();
      final var hasAutoPropagation = present(args, "autoPropagation");
      final var hasAutoTicking = present(args, "autoTicking");
      final var hasTickFrequency = present(args, "tickFrequency");
      if (!hasAutoPropagation && !hasAutoTicking && !hasTickFrequency) {
        throw rpc(-32602, "At least one simulator setting is required");
      }
      final var autoPropagation = hasAutoPropagation ? booleanValue(args, "autoPropagation", false) : null;
      final var autoTicking = hasAutoTicking ? booleanValue(args, "autoTicking", false) : null;
      final var tickFrequency = hasTickFrequency ? simulatorFrequency(args) : null;
      if (Boolean.TRUE.equals(autoTicking)) requireClockSource(project);
      if (autoPropagation != null) simulator.setAutoPropagation(autoPropagation);
      if (tickFrequency != null) simulator.setTickFrequency(tickFrequency);
      if (autoTicking != null) simulator.setAutoTicking(autoTicking);
      return simulatorState(project);
    });
  }

  private JsonElement runTestVector(JsonObject args) throws Exception {
    return onModel(() -> {
      final var project = requireProject(args);
      final var circuit = circuit(project, optional(args, "circuitId"));
      final var rawPath = required(args, "vectorPath");
      final var vectorFile = pathPolicy.requireAllowed(project, rawPath, "run_test_vector");
      final var rev = revision(project);
      return vectorJobService.start(project, circuit, registry, rev, vectorFile);
    });
  }

  private JsonElement getJob(JsonObject args) throws Exception {
    final var jobId = required(args, "jobId");
    final var result = vectorJobService.get(jobId);
    if (result == null) throw rpc(-32007, "Unknown jobId: " + jobId);
    return result;
  }

  private JsonElement listJobs(JsonObject args) throws Exception {
    return vectorJobService.list(optional(args, "projectId"));
  }

  private JsonElement cancelJob(JsonObject args) throws Exception {
    final var jobId = required(args, "jobId");
    final var result = new JsonObject();
    result.addProperty("jobId", jobId);
    result.addProperty("canceled", vectorJobService.cancel(jobId));
    return result;
  }

  private JsonElement removeJob(JsonObject args) throws Exception {
    final var jobId = required(args, "jobId");
    if (!vectorJobService.remove(jobId)) throw rpc(-32007, "Unknown or still-running jobId: " + jobId);
    final var result = new JsonObject();
    result.addProperty("jobId", jobId);
    result.addProperty("removed", true);
    return result;
  }

  private JsonElement analyzeCircuit(JsonObject args) throws Exception {
    return onModel(() -> {
      final var project = requireProject(args);
      final var circuit = circuit(project, optional(args, "circuitId"));
      final var pinLabels = Analyze.getPinLabels(circuit);
      var inputBits = 0;
      var outputBits = 0;
      for (final var entry : pinLabels.entrySet()) {
        final var width = entry.getKey().getAttributeValue(StdAttr.WIDTH).getWidth();
        if (Pin.FACTORY.isInputPin(entry.getKey())) inputBits += width;
        else outputBits += width;
      }
      if (inputBits > AnalyzerModel.MAX_INPUTS)
        throw rpc(-32013, "Circuit has too many input bits (" + inputBits + "); max is " + AnalyzerModel.MAX_INPUTS);
      if (outputBits > AnalyzerModel.MAX_OUTPUTS)
        throw rpc(-32013, "Circuit has too many output bits (" + outputBits + "); max is " + AnalyzerModel.MAX_OUTPUTS);
      final var model = new AnalyzerModel();
      model.setCurrentCircuit(project, circuit);
      Analyze.computeTable(model, project, circuit, pinLabels);
      final var result = new JsonObject();
      result.addProperty("projectId", registry.projectId(project));
      result.addProperty("circuitId", registry.circuitId(circuit));
      result.addProperty("revision", revision(project));
      final var inputNames = new JsonArray();
      for (final var v : model.getInputs().vars)
        inputNames.add(v.name + (v.width > 1 ? "[" + v.width + "]" : ""));
      result.add("inputs", inputNames);
      final var outputNames = new JsonArray();
      for (final var v : model.getOutputs().vars)
        outputNames.add(v.name + (v.width > 1 ? "[" + v.width + "]" : ""));
      result.add("outputs", outputNames);
      final var table = model.getTruthTable();
      final var rows = new JsonArray();
      final var maxRows = Math.min(table.getVisibleRowCount(), 256);
      final var numInputCols = table.getInputColumnCount();
      final var numOutputCols = table.getOutputColumnCount();
      for (var r = 0; r < maxRows; r++) {
        final var row = new JsonObject();
        final var ins = new StringBuilder();
        for (var c = 0; c < numInputCols; c++) ins.append(table.getVisibleInputEntry(r, c).getDescription());
        row.addProperty("inputs", ins.toString());
        final var outs = new StringBuilder();
        for (var c = 0; c < numOutputCols; c++) outs.append(table.getVisibleOutputEntry(r, c).getDescription());
        row.addProperty("outputs", outs.toString());
        rows.add(row);
      }
      result.add("truthTable", rows);
      result.addProperty("rowCount", table.getVisibleRowCount());
      result.addProperty("truncated", table.getVisibleRowCount() > 256);
      try {
        Analyze.computeExpression(model, circuit, pinLabels);
        final var exprs = new JsonObject();
        final var outputExpressions = model.getOutputExpressions();
        for (final var bitName : model.getOutputs().bits) {
          final var exprStr = outputExpressions.getExpressionString(bitName);
          if (exprStr != null && !exprStr.isEmpty()) exprs.addProperty(bitName, exprStr);
        }
        result.add("expressions", exprs);
      } catch (AnalyzeException e) {
        result.addProperty("expressionsError", e.getMessage() != null ? e.getMessage() : "Circuit is not purely combinational or has unsupported features");
      } catch (Exception e) {
        result.addProperty("expressionsError", "Expression analysis unavailable: " + safeMessage(e));
      }
      return result;
    });
  }

  private JsonElement exportHtml(JsonObject args) throws Exception {
    return onModel(() -> {
      final var project = requireProject(args);
      final var circuit = circuit(project, optional(args, "circuitId"));
      final var rawPath = required(args, "path");
      final var dest = pathPolicy.requireAllowed(project, rawPath, "export_html");
      if (!dest.getName().toLowerCase(Locale.ROOT).endsWith(".html")
          && !dest.getName().toLowerCase(Locale.ROOT).endsWith(".htm"))
        throw rpc(-32602, "path must end with .html or .htm");
      final var existed = dest.exists();
      if (existed && !booleanValue(args, "confirm", false)) {
        final var data = new JsonObject();
        data.addProperty("path", dest.getPath());
        data.addProperty("requiresConfirmation", true);
        throw new McpRpcException(-32012, "Refusing to overwrite an existing file without confirm=true", data);
      }
      try {
        new HtmlExporter(project, circuit).writeTo(dest);
      } catch (IOException e) {
        throw rpc(-32006, "HTML export failed: " + e.getMessage());
      }
      final var result = new JsonObject();
      result.addProperty("projectId", registry.projectId(project));
      result.addProperty("circuitId", registry.circuitId(circuit));
      result.addProperty("savedPath", dest.getPath());
      result.addProperty("overwrote", existed);
      return result;
    });
  }

  private JsonObject listVhdlEntities(Project project) {
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    final var entities = new JsonArray();
    for (final var vhdl : project.getLogisimFile().getVhdlContents()) {
      final var item = new JsonObject();
      item.addProperty("vhdlId", vhdl.getName());
      item.addProperty("name", vhdl.getName());
      entities.add(item);
    }
    result.add("entities", entities);
    result.addProperty("count", entities.size());
    return result;
  }

  private JsonObject getVhdlContent(Project project, String vhdlId) throws McpRpcException {
    final var vhdl = findVhdlContent(project, vhdlId);
    if (vhdl == null) throw rpc(-32007, "Unknown vhdlId: " + vhdlId);
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    result.addProperty("vhdlId", vhdl.getName());
    result.addProperty("name", vhdl.getName());
    result.addProperty("content", vhdl.getContent());
    return result;
  }

  private JsonElement setVhdlContent(JsonObject args) throws Exception {
    return write("set_vhdl_content", args, project -> {
      final var name = required(args, "name").trim();
      final var content = required(args, "content");
      final var existing = findVhdlContent(project, name);
      if (existing != null && !booleanValue(args, "confirm", false)) {
        final var data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("requiresConfirmation", true);
        throw new McpRpcException(-32012, "Replacing a VHDL entity requires confirm=true", data);
      }
      final var wasHeadless = com.cburch.logisim.Main.headless;
      com.cburch.logisim.Main.headless = true;
      final VhdlContent parsed;
      try {
        parsed = VhdlContent.parse(null, content, project.getLogisimFile());
      } catch (RuntimeException e) {
        throw rpc(-32602, "Failed to parse VHDL content: " + safeMessage(e));
      } finally {
        com.cburch.logisim.Main.headless = wasHeadless;
      }
      if (!parsed.isValid() || parsed.getName() == null)
        throw rpc(-32602, "Failed to parse VHDL content: no valid entity declaration found");
      if (!name.equalsIgnoreCase(parsed.getName()))
        throw rpc(-32602, "VHDL entity name in content (" + parsed.getName() + ") must match name parameter (" + name + ")");
      if (existing != null) project.doAction(LogisimFileActions.removeVhdl(existing));
      project.doAction(LogisimFileActions.addVhdl(parsed));
      final var result = operation(project, project.getCurrentCircuit());
      result.addProperty("vhdlId", parsed.getName());
      result.addProperty("name", parsed.getName());
      result.addProperty("action", existing != null ? "replaced" : "created");
      return result;
    });
  }

  private static VhdlContent findVhdlContent(Project project, String id) {
    for (final var vhdl : project.getLogisimFile().getVhdlContents())
      if (id.equalsIgnoreCase(vhdl.getName())) return vhdl;
    return null;
  }

  private static double simulatorFrequency(JsonObject args) throws McpRpcException {
    final double frequency;
    try {
      frequency = args.get("tickFrequency").getAsDouble();
    } catch (RuntimeException e) {
      throw rpc(-32602, "tickFrequency must be a number");
    }
    if (!Double.isFinite(frequency) || frequency <= 0 || frequency > 1_000_000) {
      throw rpc(-32602, "tickFrequency must be greater than 0 and at most 1000000");
    }
    return frequency;
  }

  private static void requireClockSource(Project project) throws McpRpcException {
    final var state = project.getSimulator().getCircuitState();
    final var clocks = state == null ? null : ComponentSelector.findClocks(state.getCircuit());
    if (state == null || clocks == null || clocks.isEmpty()) {
      throw rpc(-32015, "The active circuit has no clock source");
    }
    state.markKnownClocks();
  }

  private JsonObject simulatorState(Project project) {
    final var simulator = project.getSimulator();
    final var result = operation(project, project.getCurrentCircuit());
    result.addProperty("autoPropagation", simulator.isAutoPropagating());
    result.addProperty("autoTicking", simulator.isAutoTicking());
    result.addProperty("tickFrequency", simulator.getTickFrequency());
    result.addProperty("oscillating", simulator.isOscillating());
    result.addProperty("exceptionEncountered", simulator.isExceptionEncountered());
    final var state = simulator.getCircuitState();
    if (state != null) {
      result.addProperty("simulationCircuitId", registry.circuitId(state.getCircuit()));
      result.addProperty("tickCount", state.getPropagator().getTickCount());
    }
    return result;
  }

  private static void validateCircuitName(Project project, String name, Circuit changed)
      throws McpRpcException {
    if (name.isEmpty()) throw rpc(-32602, "name must not be empty");
    if (!SyntaxChecker.isVariableNameAcceptable(name, false)
        || CorrectLabel.isKeyword(name, false)) {
      throw rpc(-32602, "name is not a valid circuit identifier");
    }
    if (changed != null) {
      for (final var component : changed.getNonWires()) {
        if (component.getFactory() instanceof Pin
            && component.getAttributeSet().containsAttribute(StdAttr.LABEL)
            && name.equalsIgnoreCase(component.getAttributeSet().getValue(StdAttr.LABEL))) {
          throw rpc(-32602, "name conflicts with a pin label in the circuit");
        }
      }
    }
    for (final var circuit : project.getLogisimFile().getCircuits()) {
      if (circuit != changed && name.equalsIgnoreCase(circuit.getName())) {
        throw rpc(-32602, "circuit name is already in use");
      }
    }
    for (final var tool : project.getLogisimFile().getTools()) {
      if (name.equalsIgnoreCase(tool.getName())
          && !(tool.getFactory() instanceof SubcircuitFactory sub
              && changed == sub.getSubcircuit())) {
        throw rpc(-32602, "name conflicts with an available tool");
      }
    }
    for (final var library : project.getLogisimFile().getLibraries()) {
      if (libraryContainsToolName(library, name)) {
        throw rpc(-32602, "name conflicts with an available tool");
      }
    }
  }

  private static boolean libraryContainsToolName(Library library, String name) {
    for (final var tool : library.getTools()) {
      if (name.equalsIgnoreCase(tool.getName())) return true;
    }
    for (final var nested : library.getLibraries()) {
      if (libraryContainsToolName(nested, name)) return true;
    }
    return false;
  }

  private JsonObject operation(Project project, Circuit circuit) {
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    result.addProperty("revision", revision(project));
    result.addProperty("dirty", project.isFileDirty());
    if (circuit != null) result.addProperty("circuitId", registry.circuitId(circuit));
    return result;
  }

  private JsonElement write(String toolName, JsonObject args, ProjectWrite operation)
      throws Exception {
    return onModel(
        () -> {
          final var project = requireProject(args);
          return operationLedger.execute(
              project,
              toolName,
              args,
              () -> {
                checkRevision(project, args);
                final var operationId = McpOperationLedger.operationId(args);
                if (operationId != null) activeOperationIds.put(project, operationId);
                try {
                  return operation.execute(project);
                } finally {
                  activeOperationIds.remove(project);
                }
              });
        });
  }

  private Project requireProject(JsonObject args) throws McpJsonRpcDispatcher.McpRpcException {
    refresh();
    final var project = registry.resolve(optional(args, "projectId"));
    if (project == null) throw rpc(-32001, "No open Logisim project");
    return project;
  }
  private void pruneOwnedActions(Project project) {
    final var actions = ownedActions.get(project);
    if (actions == null) return;
    final var retained =
        java.util.Collections.newSetFromMap(new IdentityHashMap<Action, Boolean>());
    retained.addAll(project.getUndoActions());
    retained.addAll(project.getRedoActions());
    actions.keySet().removeIf(action -> !retained.contains(action));
    if (actions.isEmpty()) ownedActions.remove(project);
  }
  private void requireOwnedStackHead(
      Project project, Action action, String targetOperationId, String verb)
      throws McpRpcException {
    final var owned =
        action == null
            ? null
            : ownedActions.getOrDefault(project, new IdentityHashMap<>()).get(action);
    if (targetOperationId.equals(owned)) return;
    final var data = new JsonObject();
    data.addProperty("targetOperationId", targetOperationId);
    data.addProperty("stack", verb);
    data.addProperty("stackHeadOwnedByMcp", owned != null);
    if (owned != null) data.addProperty("actualOperationId", owned);
    throw new McpRpcException(
        -32011, "Refusing to " + verb + ": target MCP action is not the current stack head", data);
  }
  private Circuit circuit(Project project, String id)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var result =
        id == null ? project.getCurrentCircuit() : registry.resolveCircuit(project, id);
    if (result == null) throw rpc(-32007, "Unknown circuitId");
    return result;
  }
  private Component findComponent(Project project, String id) {
    for (final var circuit : project.getLogisimFile().getCircuits()) {
      for (final var component : circuit.getNonWires()) {
        if (id.equals(registry.componentId(component))) return component;
      }
      for (final var wire : circuit.getWires()) {
        if (id.equals(registry.componentId(wire))) return wire;
      }
    }
    return null;
  }
  private Circuit circuitFor(Project project, Component component) throws McpRpcException {
    for (final var circuit : project.getLogisimFile().getCircuits()) {
      if (circuit.contains(component)) return circuit;
    }
    throw rpc(-32002, "Object is no longer in the project");
  }
  private ComponentFactory findFactory(Library library, String name) {
    for (final var tool : library.getTools()) {
      if (tool instanceof AddTool add
          && (name.equalsIgnoreCase(add.getName())
              || name.equalsIgnoreCase(add.getFactory().getName())
              || name.equalsIgnoreCase(add.getFactory().getDisplayName()))) {
        return add.getFactory();
      }
    }
    for (final var nested : library.getLibraries()) {
      final var result = findFactory(nested, name);
      if (result != null) return result;
    }
    return null;
  }

  private void checkRevision(Project project, JsonObject args) throws McpRpcException {
    if (!args.has("expectedRevision") || args.get("expectedRevision").isJsonNull()) {
      throw rpc(-32602, "expectedRevision is required for mutating tools");
    }
    final long expected;
    try {
      expected = args.get("expectedRevision").getAsLong();
    } catch (RuntimeException e) {
      throw rpc(-32602, "expectedRevision must be an integer");
    }
    final var actual = revision(project);
    if (expected == actual) return;
    final var data = new JsonObject();
    data.addProperty("expectedRevision", expected);
    data.addProperty("actualRevision", actual);
    throw new McpRpcException(-32009, "Revision conflict", data);
  }
  private long revision(Project project) {
    return revisions.getOrDefault(project, 0L);
  }
  private static String required(JsonObject object, String name) throws McpRpcException {
    final var value = object.get(name);
    if (value == null
        || value.isJsonNull()
        || !value.isJsonPrimitive()
        || value.getAsString().isBlank()) {
      throw rpc(-32602, "Missing parameter: " + name);
    }
    return value.getAsString();
  }
  private static int requiredInt(JsonObject object, String name) throws McpRpcException {
    try {
      return object.get(name).getAsInt();
    } catch (RuntimeException e) {
      throw rpc(-32602, "Missing integer parameter: " + name);
    }
  }
  private static String optional(JsonObject object, String name) {
    final var value = object.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }
  private static boolean present(JsonObject object, String name) {
    final var value = object.get(name);
    return value != null && !value.isJsonNull();
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
  private static List<String> strings(JsonObject object, String name) throws McpRpcException {
    final var array = object.get(name);
    if (array == null || !array.isJsonArray()) {
      throw rpc(-32602, name + " must be an array");
    }
    final var result = new ArrayList<String>();
    for (final var value : array.getAsJsonArray()) {
      if (!value.isJsonPrimitive()) throw rpc(-32602, name + " entries must be strings");
      result.add(value.getAsString());
    }
    return result;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object parse(Attribute attribute, JsonElement value) throws McpRpcException {
    try {
      if (value == null || value.isJsonNull()) return null;
      return attribute.parse(value.isJsonPrimitive() ? value.getAsString() : value.toString());
    } catch (RuntimeException e) {
      throw rpc(-32602, "Invalid attribute value: " + value);
    }
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void applyAttributes(AttributeSet set, JsonObject values)
      throws McpRpcException {
    if (values == null) return;
    for (final var entry : values.entrySet()) {
      final var attribute = set.getAttribute(entry.getKey());
      if (attribute == null) throw rpc(-32602, "Unknown attribute: " + entry.getKey());
      set.setValue((Attribute) attribute, parse(attribute, entry.getValue()));
    }
  }
  private static JsonObject writeSchema(Object... values) {
    return schemaWithRevision(values);
  }
  private static JsonObject lifecycleSchema(Object... values) {
    return schemaWithRevision(values);
  }

  /** The shared tail of {@link #writeSchema} and {@link #lifecycleSchema}, which were identical. */
  private static JsonObject schemaWithRevision(Object... values) {
    final var expanded = new Object[values.length + 6];
    System.arraycopy(values, 0, expanded, 0, values.length);
    expanded[values.length] = "expectedRevision";
    expanded[values.length + 1] = "integer";
    expanded[values.length + 2] = true;
    expanded[values.length + 3] = "operationId";
    expanded[values.length + 4] = "string";
    expanded[values.length + 5] = false;
    return schema(expanded);
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
  private static final class McpRpcException extends McpJsonRpcDispatcher.McpRpcException {
    private final int errorCode;

    McpRpcException(int code, String message) {
      super(code, message);
      errorCode = code;
    }

    McpRpcException(int code, String message, JsonElement data) {
      super(code, message, data);
      errorCode = code;
    }

    int code() {
      return errorCode;
    }
  }
  @FunctionalInterface
  private interface ProjectWrite { JsonElement execute(Project project) throws Exception; }
  @FunctionalInterface
  private interface LifecycleWrite { JsonElement execute() throws Exception; }
}
