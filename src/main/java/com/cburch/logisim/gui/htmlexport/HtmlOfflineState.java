/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceData;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.proj.Project;

/**
 * Peler Edition. An {@link InstanceState} that is not backed by a running simulation, so a
 * component's own {@code propagate} can be run against port values the caller picks.
 *
 * <p>This is how the exporter finds out what a display component looks like at a given input
 * without restating that component's behaviour. The alternative -- deciding in the exporter that a
 * seven-segment display lights segment three for input bit three -- would be a second copy of the
 * component's semantics, which is exactly the kind of duplication this feature already has to pay
 * for once in JavaScript and should not pay for twice.
 *
 * <p>What a live simulation would provide is deliberately absent: there is no project, no tick
 * count and no substate. A component that reaches for one throws, and the caller reports it as
 * having no exportable appearance rather than shipping a guess.
 */
final class HtmlOfflineState implements InstanceState {

  private final Component component;
  private final Instance instance;
  private final Value[] ports;
  private InstanceData data;

  HtmlOfflineState(Component component, Value[] ports) {
    this.component = component;
    this.instance = Instance.getInstanceFor(component);
    this.ports = ports;
  }

  /** The data the component left behind, which is what its painter will read. */
  InstanceData data() {
    return data;
  }

  @Override
  public void fireInvalidated() {
    // Nothing is watching: there is no canvas behind this state.
  }

  @Override
  public AttributeSet getAttributeSet() {
    return component.getAttributeSet();
  }

  @Override
  public <E> E getAttributeValue(Attribute<E> attr) {
    return component.getAttributeSet().getValue(attr);
  }

  @Override
  public InstanceData getData() {
    return data;
  }

  @Override
  public InstanceFactory getFactory() {
    return (InstanceFactory) component.getFactory();
  }

  @Override
  public Instance getInstance() {
    return instance;
  }

  @Override
  public int getPortIndex(Port port) {
    return instance.getPorts().indexOf(port);
  }

  @Override
  public Value getPortValue(int portIndex) {
    return portIndex >= 0 && portIndex < ports.length ? ports[portIndex] : Value.NIL;
  }

  @Override
  public Project getProject() {
    return null;
  }

  @Override
  public int getTickCount() {
    return 0;
  }

  @Override
  public boolean isCircuitRoot() {
    return true;
  }

  @Override
  public boolean isPortConnected(int portIndex) {
    return true;
  }

  @Override
  public CircuitState createCircuitSubstateFor(Circuit circ) {
    throw new UnsupportedOperationException("no simulation behind an offline instance state");
  }

  @Override
  public void setData(InstanceData value) {
    data = value;
  }

  @Override
  public void setPort(int portIndex, Value value, int delay) {
    if (portIndex >= 0 && portIndex < ports.length) ports[portIndex] = value;
  }
}
