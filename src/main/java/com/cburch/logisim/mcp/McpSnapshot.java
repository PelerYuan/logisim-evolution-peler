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
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Converts the mutable Logisim model into stable JSON DTOs. */
final class McpSnapshot {
  private McpSnapshot() {}

  static JsonObject project(Project project, McpProjectRegistry registry, long revision) {
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    result.addProperty("name", project.getLogisimFile().getName());
    result.addProperty("dirty", project.isFileDirty());
    result.addProperty("revision", revision);
    final var current = project.getCurrentCircuit();
    if (current != null) result.addProperty("currentCircuitId", registry.circuitId(current));
    final var circuits = new JsonArray();
    for (final var circuit : project.getLogisimFile().getCircuits()) {
      circuits.add(circuit(project, circuit, registry, revision, false));
    }
    result.add("circuits", circuits);
    return result;
  }

  static JsonObject circuit(
      Project project, Circuit circuit, McpProjectRegistry registry, long revision, boolean detail) {
    return circuit(project, circuit, registry, revision, detail, true);
  }

  private static JsonObject circuit(
      Project project,
      Circuit circuit,
      McpProjectRegistry registry,
      long revision,
      boolean detail,
      boolean includeObjects) {
    final var result = new JsonObject();
    result.addProperty("projectId", registry.projectId(project));
    result.addProperty("circuitId", registry.circuitId(circuit));
    result.addProperty("name", circuit.getName());
    result.addProperty("revision", revision);
    result.add("bounds", bounds(circuit.getBounds()));
    result.add("attributes", attributes(circuit.getStaticAttributes()));
    final var components = new JsonArray();
    final var wires = new JsonArray();
    if (includeObjects) {
      for (final var component : circuit.getNonWires()) {
        components.add(component(registry.componentId(component), component, detail));
      }
      for (final var wire : circuit.getWires()) {
        wires.add(component(registry.componentId(wire), wire, detail));
      }
    }
    result.add("components", components);
    result.add("wires", wires);
    return result;
  }

  static JsonObject component(String id, Component component, boolean detail) {
    final var result = new JsonObject();
    result.addProperty("componentId", id);
    result.addProperty("factory", component.getFactory().getName());
    result.addProperty("displayName", component.getFactory().getDisplayName());
    final var location = component.getLocation();
    result.add("location", point(location));
    result.add("bounds", bounds(component.getBounds()));
    if (detail) result.add("attributes", attributes(component.getAttributeSet()));
    if (component instanceof Wire wire) {
      result.add("start", point(wire.getEnd0()));
      result.add("end", point(wire.getEnd1()));
    }
    return result;
  }

  static JsonArray availableTools(Project project) {
    final var result = new JsonArray();
    final var visited = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<Library, Boolean>());
    addAvailableTools(project.getLogisimFile(), "Project", result, visited);
    return result;
  }

  private static void addAvailableTools(
      Library library, String path, JsonArray result, java.util.Set<Library> visited) {
    if (library == null || !visited.add(library)) return;
    for (final var tool : library.getTools()) {
      if (!(tool instanceof AddTool addTool)) continue;
      final var item = new JsonObject();
      item.addProperty("name", addTool.getName());
      item.addProperty("displayName", addTool.getDisplayName());
      item.addProperty("factory", addTool.getFactory().getName());
      item.addProperty("library", path);
      result.add(item);
    }
    for (final var nested : library.getLibraries()) {
      final var name = nested.getDisplayName();
      addAvailableTools(
          nested,
          path + "/" + (name == null || name.isBlank() ? nested.getName() : name),
          result,
          visited);
    }
  }

  private static JsonObject attributes(AttributeSet set) {
    final var result = new JsonObject();
    if (set == null || set.getAttributes() == null) return result;
    for (final Attribute<?> attribute : set.getAttributes()) {
      if (attribute == null || !set.containsAttribute(attribute)) continue;
      try {
        final Object value = set.getValue(attribute);
        @SuppressWarnings({"rawtypes", "unchecked"})
        final var standard = value == null ? null : ((Attribute) attribute).toStandardString(value);
        result.add(
            attribute.getName(),
            standard == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(standard));
      } catch (RuntimeException ignored) {
        result.addProperty(attribute.getName(), "<unserializable>");
      }
    }
    return result;
  }

  private static JsonObject point(Location point) {
    final var result = new JsonObject();
    if (point == null) return result;
    result.addProperty("x", point.getX());
    result.addProperty("y", point.getY());
    return result;
  }

  private static JsonObject bounds(Bounds value) {
    final var result = new JsonObject();
    if (value == null) return result;
    result.addProperty("x", value.getX());
    result.addProperty("y", value.getY());
    result.addProperty("width", value.getWidth());
    result.addProperty("height", value.getHeight());
    return result;
  }
}
