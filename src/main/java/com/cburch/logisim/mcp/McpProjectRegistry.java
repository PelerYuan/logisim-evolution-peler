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
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.awt.Window;

/** Keeps session-stable identifiers for projects, circuits, and components. */
public final class McpProjectRegistry {
  private final Map<String, Project> projects = new LinkedHashMap<>();
  private final IdentityHashMap<Project, String> projectIds = new IdentityHashMap<>();
  private final IdentityHashMap<Circuit, String> circuitIds = new IdentityHashMap<>();
  private final IdentityHashMap<Component, String> componentIds = new IdentityHashMap<>();
  private final IdentityHashMap<Project, Boolean> explicitlyRegistered = new IdentityHashMap<>();

  /** Synchronizes the registry with the windows known by Logisim. Must run on the EDT. */
  public synchronized void refresh() {
    final var open = Projects.getOpenProjects();
    final var present = java.util.Collections.newSetFromMap(new IdentityHashMap<Project, Boolean>());
    for (final var project : open) {
      present.add(project);
      projects.put(ensureProjectId(project), project);
    }
    // During startup a Frame is posted to the EDT after ProjectActions returns;
    // the Projects window listener may not have observed it yet. Inspect the
    // actual Swing windows as a fallback so the visible editor is discoverable.
    for (final var window : Window.getWindows()) {
      if (window instanceof com.cburch.logisim.gui.main.Frame frame
          && frame.getProject() != null
          && (frame.isVisible() || frame.isDisplayable())) {
        final var project = frame.getProject();
        present.add(project);
        projects.put(ensureProjectId(project), project);
      }
    }
    present.addAll(explicitlyRegistered.keySet());
    projects.entrySet().removeIf(entry -> !present.contains(entry.getValue()));
  }

  public synchronized String register(Project project) {
    if (project == null) return null;
    final var id = ensureProjectId(project);
    projects.put(id, project);
    explicitlyRegistered.put(project, Boolean.TRUE);
    return id;
  }

  public synchronized void unregister(Project project) {
    if (project == null) return;
    final var id = projectIds.get(project);
    if (id != null) projects.remove(id);
    explicitlyRegistered.remove(project);
  }

  public synchronized Project resolve(String projectId) {
    refresh();
    if (projectId == null || projectId.isBlank()) return activeProject();
    return projects.get(projectId);
  }

  public synchronized Project activeProject() {
    try {
      final var frame = Projects.getTopFrame();
      if (frame != null && frame.getProject() != null) return frame.getProject();
    } catch (RuntimeException ignored) {
      // A headless/model-only test may not have a top frame.
    }
    return projects.values().stream().findFirst().orElse(null);
  }

  public synchronized List<Project> projects() {
    refresh();
    return new ArrayList<>(projects.values());
  }

  public synchronized String projectId(Project project) {
    if (project == null) return null;
    final var id = ensureProjectId(project);
    projects.put(id, project);
    return id;
  }

  public synchronized String circuitId(Circuit circuit) {
    if (circuit == null) return null;
    return circuitIds.computeIfAbsent(circuit, ignored -> UUID.randomUUID().toString());
  }

  public synchronized String componentId(Component component) {
    if (component == null) return null;
    return componentIds.computeIfAbsent(component, ignored -> UUID.randomUUID().toString());
  }

  /** Keeps an object's MCP identity stable when an undoable action replaces its Java instance. */
  public synchronized String linkComponentId(Component original, Component replacement) {
    if (original == null || replacement == null) return null;
    final var id = componentId(original);
    componentIds.put(replacement, id);
    return id;
  }

  public synchronized Circuit resolveCircuit(Project project, String circuitId) {
    if (project == null) return null;
    final var file = project.getLogisimFile();
    if (circuitId == null || circuitId.isBlank()) return project.getCurrentCircuit();
    for (final var circuit : file.getCircuits()) {
      if (circuitId.equals(circuitId(circuit))) return circuit;
    }
    return null;
  }

  private String ensureProjectId(Project project) {
    var id = projectIds.get(project);
    if (id == null) {
      id = UUID.randomUUID().toString();
      projectIds.put(project, id);
    }
    return id;
  }
}
