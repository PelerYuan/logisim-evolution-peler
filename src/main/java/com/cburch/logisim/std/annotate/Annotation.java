/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.annotate;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.util.GraphicsUtil;
import com.cburch.logisim.util.StringUtil;
import java.awt.Graphics2D;

/**
 * Peler Edition Feature 5: a free-text schematic annotation, anchored to a component or wire
 * endpoint by {@link com.cburch.logisim.tools.AnnotateTool} and kept in sync by {@link
 * AnnotationAnchorTracker}. Deliberately a *sibling* of {@link Text}, not a subclass -- reuses
 * its text/font/color/alignment {@code Attribute} objects directly (so no new i18n keys, no
 * duplicated attribute-table UI), but is otherwise fully independent: has no relationship to
 * {@code StdAttr.LABEL} and doesn't touch anything {@link Text} or {@code Circuit.annotate()}
 * (the unrelated FPGA auto-labeling engine, despite the similar name) already does.
 *
 * <p>See docs/peler-edition/ROADMAP.md, Feature 5, for the full design writeup.
 */
public class Annotation extends InstanceFactory {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Annotation";

  public static final Annotation FACTORY = new Annotation();

  private Annotation() {
    super(_ID, S.getter("annotationComponent"));
    setShouldSnap(false);
  }

  private void configureLabel(Instance instance) {
    final var attrs = (AnnotationAttributes) instance.getAttributeSet();
    final var loc = instance.getLocation();
    instance.setTextField(
        Text.ATTR_TEXT,
        Text.ATTR_FONT,
        loc.getX(),
        loc.getY(),
        attrs.getHorizontalAlign(),
        attrs.getVerticalAlign());
  }

  @Override
  protected void configureNewInstance(Instance instance) {
    configureLabel(instance);
    instance.addAttributeListener();
  }

  @Override
  public AttributeSet createAttributeSet() {
    return new AnnotationAttributes();
  }

  @Override
  public Bounds getOffsetBounds(AttributeSet attrsBase) {
    final var attrs = (AnnotationAttributes) attrsBase;
    final var text = attrs.getText();
    if (text == null || text.isEmpty()) {
      return Bounds.EMPTY_BOUNDS;
    }
    var bds = attrs.getOffsetBounds();
    if (bds == null) {
      bds =
          StringUtil.estimateBounds(
              attrs.getText(), attrs.getFont(), attrs.getHorizontalAlign(), attrs.getVerticalAlign());
      attrs.setOffsetBounds(bds);
    }
    return bds == null ? Bounds.EMPTY_BOUNDS : bds;
  }

  @Override
  public boolean isHDLSupportedComponent(AttributeSet attrs) {
    // Purely a schematic-documentation aid, same stance Text.java takes -- nothing to generate.
    return true;
  }

  //
  // graphics methods -- deliberately identical rendering approach to Text.java (same estimate-
  // then-paint two-pass so bounds settle after the first real paint), just without that class's
  // horizontal/vertical alignment configureLabel() re-trigger since Annotation doesn't expose
  // those as separately mutable post-creation.
  //
  @Override
  public void paintGhost(InstancePainter painter) {
    final var attrs = (AnnotationAttributes) painter.getAttributeSet();
    final var text = attrs.getText();
    if (text == null || text.isEmpty()) return;

    final var halign = attrs.getHorizontalAlign();
    final var valign = attrs.getVerticalAlign();
    final var g = painter.getGraphics();
    final var old = g.getFont();
    g.setFont(attrs.getFont());
    GraphicsUtil.drawText(g, text, 0, 0, halign, valign);

    final var textTrim = text.endsWith(" ") ? text.substring(0, text.length() - 1) : text;
    Bounds newBds;
    if (textTrim.isEmpty()) {
      newBds = Bounds.EMPTY_BOUNDS;
    } else {
      final var bdsOut = GraphicsUtil.getTextBounds(g, textTrim, 0, 0, halign, valign);
      newBds = Bounds.create(bdsOut).expand(4);
    }
    if (attrs.setOffsetBounds(newBds)) {
      final var instance = painter.getInstance();
      if (instance != null) instance.recomputeBounds();
    }

    g.setFont(old);
  }

  @Override
  public void paintInstance(InstancePainter painter) {
    final var loc = painter.getLocation();
    final var x = loc.getX();
    final var y = loc.getY();
    final var gfx = painter.getGraphics();
    gfx.translate(x, y);
    gfx.setColor(painter.getAttributeValue(Text.ATTR_COLOR));
    paintGhost(painter);
    gfx.translate(-x, -y);
  }

  @Override
  public void paintIcon(InstancePainter painter) {
    // Minimal placeholder icon: a little sticky-note shape with two text lines, in the style of
    // QuickRotateTool.paintIcon (plain line primitives, no image resources needed).
    final var g2 = (Graphics2D) painter.getGraphics().create();
    g2.drawRect(2, 2, 12, 12);
    g2.fillPolygon(new int[] {10, 14, 14}, new int[] {2, 2, 6}, 3);
    g2.drawLine(4, 7, 10, 7);
    g2.drawLine(4, 10, 10, 10);
    g2.dispose();
  }

  @Override
  public void propagate(InstanceState state) {
    // Documentation-only component, never part of simulation.
  }
}
