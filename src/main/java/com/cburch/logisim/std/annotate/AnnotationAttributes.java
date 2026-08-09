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

import com.cburch.logisim.data.AbstractAttributeSet;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.std.base.Text;
import java.awt.Color;
import java.awt.Font;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Peler Edition Feature 5. Mirrors {@code com.cburch.logisim.std.base.TextAttributes} closely
 * (same text/font/color/alignment fields, reusing {@link Text}'s own {@code Attribute} objects
 * directly rather than duplicating them), plus one addition: {@link #ANCHOR_LOC}, a hidden
 * attribute holding the last-known location of whatever this annotation is anchored to.
 *
 * <p>{@code ANCHOR_LOC} is not user-editable ({@code hidden = true}, so the Attribute Table
 * skips it -- see {@code Attribute.isHidden()} / {@code AttributeSetTableModel}) but it IS saved
 * to the .circ file like any other attribute (hidden only suppresses UI display, not
 * persistence -- confirmed by reading {@code XmlWriter.addAttributeSetContent}, whose hidden-skip
 * only applies to library/tool default-attribute diffing, not per-component saving). It exists so
 * that (a) {@link AnnotationAnchorTracker} can compute how far to translate this annotation when
 * its anchor moves, and (b) a reloaded .circ file has something to best-effort re-match against
 * -- live anchor object references don't survive save/load, only this location does. See
 * docs/peler-edition/ROADMAP.md, Feature 5, "Persistence".
 */
public class AnnotationAttributes extends AbstractAttributeSet {
  /**
   * The annotation's text. Deliberately NOT {@link Text#ATTR_TEXT}, even though everything else
   * here reuses {@link Text}'s attributes: the base {@code Attribute.toStandardString} strips
   * every character in the C0 range, newline included, so a multi-line note collapsed into a
   * single line the moment it was saved. Upstream's Text component is single-line, so that strip
   * never cost it anything. Found by round-tripping a two-line note through save/reload.
   *
   * <p>Keeps the same attribute NAME ("text") -- that is what the file records, so files written
   * before this fix still load -- and keeps stripping the other control characters, which really
   * are illegal in XML. Carriage return goes too, so the value is always {@code \n}-separated
   * regardless of platform.
   *
   * <p>The multi-line value survives the round trip because upstream already handles it at both
   * ends: {@code XmlWriter.addAttributeSetContent} writes a value containing a newline as a child
   * text node rather than a {@code val="..."} attribute, and {@code XmlReader.initAttributeSet}
   * falls back to {@code getTextContent()} when {@code val} is absent. That path was simply
   * unreachable while the newline was being deleted before it ever got there.
   */
  public static final Attribute<String> ATTR_TEXT =
      new Attribute<>("text", S.getter("textTextAttr")) {
        @Override
        public String parse(String value) {
          return value;
        }

        @Override
        public String toStandardString(String value) {
          // Same as the base implementation minus 0x0A, the newline we want to keep. Written with
          // regex-level hex escapes rather than Java's own backslash-u form: those are expanded by
          // the compiler's lexer, so they would put raw control characters into this source file
          // (and are a compile error even inside a comment).
          return value.replaceAll("[\\x00-\\x09\\x0B-\\x1F]", "").replaceAll("&#.*?;", "");
        }
      };

  public static final Attribute<Location> ANCHOR_LOC =
      new Attribute<>("annotationAnchorLoc", null, true) {
        @Override
        public Location parse(String value) {
          return Location.parse(value);
        }
      };

  /** {@link #ANCHOR_KIND} value: anchored to a component's body, measured from its bounding-box top-centre. */
  public static final String KIND_BODY = "body";

  /** {@link #ANCHOR_KIND} value: anchored to one specific point -- a wire endpoint, or a component's pin. */
  public static final String KIND_POINT = "point";

  /**
   * Which sort of thing {@link #ANCHOR_LOC} names, so the tracker knows how to recompute it after
   * the anchor moves: {@link #KIND_BODY} recomputes as the anchor's bounding-box top-centre,
   * {@link #KIND_POINT} as whichever of its pins/endpoints is nearest the last known location.
   * Hidden and saved, exactly like {@link #ANCHOR_LOC}.
   */
  public static final Attribute<String> ANCHOR_KIND =
      new Attribute<>("annotationAnchorKind", null, true) {
        @Override
        public String parse(String value) {
          return value;
        }
      };

  private static final List<Attribute<?>> ATTRIBUTES =
      Arrays.asList(
          ATTR_TEXT, Text.ATTR_FONT, Text.ATTR_COLOR, Text.ATTR_HALIGN, Text.ATTR_VALIGN,
          ANCHOR_LOC, ANCHOR_KIND);

  /**
   * Annotations are commentary, not part of the drawing, and should read that way. Deliberately
   * NOT {@code StdAttr.DEFAULT_LABEL_FONT} (SansSerif BOLD 16), which a label wants because a
   * label names a real signal -- at that weight and size a note shouts over the circuit it is
   * annotating. Plain and smaller instead.
   */
  private static final Font DEFAULT_ANNOTATION_FONT = new Font("SansSerif", Font.PLAIN, 12);

  /**
   * A muted slate grey rather than black, for the same reason: black is what the schematic itself
   * is drawn in, so a black note reads as part of the circuit instead of a remark about it. Dark
   * enough to stay legible against the canvas, light enough to recede behind the gates and wires.
   */
  private static final Color DEFAULT_ANNOTATION_COLOR = new Color(90, 100, 115);

  private String text;
  private Font font;
  private Color color;
  private AttributeOption halign;
  private AttributeOption valign;
  private Location anchorLoc;
  private String anchorKind;
  private Bounds offsetBounds;

  public AnnotationAttributes() {
    text = "";
    font = DEFAULT_ANNOTATION_FONT;
    color = DEFAULT_ANNOTATION_COLOR;
    halign = Text.ATTR_HALIGN.parse("center");
    valign = Text.ATTR_VALIGN.parse("base");
    anchorLoc = null;
    anchorKind = KIND_BODY;
    offsetBounds = null;
  }

  @Override
  protected void copyInto(AbstractAttributeSet destObj) {
    // nothing extra to do -- getValue/setValue below already cover every field via clone()'s
    // generic copy-by-attribute mechanism (see AbstractAttributeSet.clone()).
  }

  @Override
  public List<Attribute<?>> getAttributes() {
    return ATTRIBUTES;
  }

  Font getFont() {
    return font;
  }

  int getHorizontalAlign() {
    return (Integer) halign.getValue();
  }

  Bounds getOffsetBounds() {
    return offsetBounds;
  }

  String getText() {
    return text;
  }

  Location getAnchorLoc() {
    return anchorLoc;
  }

  String getAnchorKind() {
    return anchorKind;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <V> V getValue(Attribute<V> attr) {
    if (attr == ATTR_TEXT) return (V) text;
    if (attr == Text.ATTR_FONT) return (V) font;
    if (attr == Text.ATTR_HALIGN) return (V) halign;
    if (attr == Text.ATTR_VALIGN) return (V) valign;
    if (attr == Text.ATTR_COLOR) return (V) color;
    if (attr == ANCHOR_LOC) return (V) anchorLoc;
    if (attr == ANCHOR_KIND) return (V) anchorKind;
    return null;
  }

  int getVerticalAlign() {
    return (Integer) valign.getValue();
  }

  boolean setOffsetBounds(Bounds value) {
    final var old = offsetBounds;
    final var same = Objects.equals(old, value);
    if (!same) offsetBounds = value;
    return !same;
  }

  @Override
  public <V> void setValue(Attribute<V> attr, V value) {
    if (attr == ATTR_TEXT) {
      text = (String) value;
    } else if (attr == Text.ATTR_FONT) {
      font = (Font) value;
    } else if (attr == Text.ATTR_HALIGN) {
      halign = (AttributeOption) value;
    } else if (attr == Text.ATTR_VALIGN) {
      valign = (AttributeOption) value;
    } else if (attr == Text.ATTR_COLOR) {
      color = (Color) value;
    } else if (attr == ANCHOR_LOC) {
      anchorLoc = (Location) value;
      fireAttributeValueChanged(attr, value, null);
      return;
    } else if (attr == ANCHOR_KIND) {
      anchorKind = (String) value;
      fireAttributeValueChanged(attr, value, null);
      return;
    } else {
      throw new IllegalArgumentException("unknown attribute");
    }
    offsetBounds = null;
    fireAttributeValueChanged(attr, value, null);
  }
}
