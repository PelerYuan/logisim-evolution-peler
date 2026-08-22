/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.symbol.SymbolGate;
import com.cburch.logisim.std.symbol.SymbolLayout;
import com.cburch.logisim.std.ttl.AbstractTtlGate;
import java.util.regex.Pattern;

/**
 * Peler Edition. One 74xx chip drawn as a logic symbol instead of a DIP package.
 *
 * <p>Everything about the picture lives in {@link SymbolGate}; what is here is the half that is
 * about TTL. The chip's own logic is not restated: {@code propagate} hands straight to the DIP
 * factory, which is possible because {@code AbstractTtlGate} decides a port's index from its pin
 * number alone and never from where the port sits.
 */
public class TtlSymbolGate extends SymbolGate {

  /**
   * How upstream spells an active-low pin: an {@code n} before the symbol, after a leading group
   * number if there is one. Splitting it in two groups is what lets the {@code n} be dropped --
   * {@code n1Y4} becomes {@code 1Y4} and {@code 1nY0} becomes {@code 1Y0}.
   */
  private static final Pattern ACTIVE_LOW_PREFIX = Pattern.compile("(\\d*)n([A-Z0-9].*)");

  /** The other way upstream marks one, in pin names that are descriptions. */
  private static final String ACTIVE_LOW_WORDS = "active LOW";

  /** A chip's symbol never changes shape, so one layout serves every attribute set. */
  private static final Object ONE_SHAPE = new Object();

  private final TtlSymbolSpec spec;
  private final AbstractTtlGate delegate;

  public TtlSymbolGate(TtlSymbolSpec spec) {
    this(spec, spec.delegate().get());
  }

  private TtlSymbolGate(TtlSymbolSpec spec, AbstractTtlGate delegate) {
    super("Sym" + delegate.getName(), S.getter("TTL" + delegate.getName()));
    this.spec = spec;
    this.delegate = delegate;
    setIconName("ttl.gif");
    setAttributes(
        new Attribute[] {StdAttr.FACING, StdAttr.LABEL, StdAttr.LABEL_FONT},
        new Object[] {Direction.EAST, "", StdAttr.DEFAULT_LABEL_FONT});
    setFacingAttribute(StdAttr.FACING);
  }

  public TtlSymbolSpec getSpec() {
    return spec;
  }

  /** The DIP factory this symbol is a second face of. Used by the tests to compare the two. */
  public AbstractTtlGate getDelegate() {
    return delegate;
  }

  @Override
  protected Object layoutKey(AttributeSet attrs) {
    return ONE_SHAPE;
  }

  @Override
  protected SymbolLayout buildLayout(AttributeSet attrs) {
    final var kinds = portKinds(delegate);
    final var labels = new String[kinds.length];
    final var bubbles = new boolean[kinds.length];
    resolveLabels(spec, delegate, labels, bubbles);
    return new SymbolLayout(delegate.getName(), spec.left(), spec.right(), labels, bubbles, kinds);
  }

  /** What is written beside port {@code index}. */
  String label(int index) {
    return layoutFor(createAttributeSet()).label(index);
  }

  /** Whether port {@code index} is drawn with an inversion circle. */
  boolean isInverted(int index) {
    return layoutFor(createAttributeSet()).isInverted(index);
  }

  int getSymbolWidth() {
    return layoutFor(createAttributeSet()).width();
  }

  /**
   * Which of the delegate's ports are inputs, outputs or both, taken from the DIP factory rather
   * than restated here: one throwaway DIP component is built and its ends are read. Restating it
   * would be a second copy of the pinout to keep in step with upstream's, and the count matters as
   * much as the kinds -- it is what the port placement checks the spec against.
   */
  private static int[] portKinds(AbstractTtlGate delegate) {
    final var probe =
        delegate.createComponent(Location.create(0, 0, true), delegate.createAttributeSet());
    final var ends = probe.getEnds();
    final var kinds = new int[ends.size()];
    for (var i = 0; i < kinds.length; i++) {
      final var end = ends.get(i);
      kinds[i] =
          end.isInput() && end.isOutput()
              ? SymbolLayout.INOUT
              : end.isOutput() ? SymbolLayout.OUTPUT : SymbolLayout.INPUT;
    }
    return kinds;
  }

  /**
   * Works out what is written beside every port and which ports get an inversion circle. A row that
   * gives no label of its own takes the DIP factory's, which is what makes an index typed wrong in
   * the layout table show up as a wrong name.
   *
   * <p>Polarity is read out of upstream's own pin name rather than restated per chip. Upstream
   * writes an active-low pin as an {@code n} before the symbol -- {@code nCLR}, {@code nOE1},
   * {@code n1Y4}, and {@code 1nY0} where a group number comes first -- or spells out {@code
   * "active LOW"} in the description. Either spelling means a circle, and the {@code n} comes off
   * the name because the circle already says it. Deriving it here rather than ticking a box in
   * sixty-one layout tables means the symbol cannot end up claiming a polarity the chip it
   * delegates to does not have. A chip whose factory declares no names, or that upstream never
   * marked, still says so in its layout table.
   */
  private static void resolveLabels(
      TtlSymbolSpec spec, AbstractTtlGate delegate, String[] labels, boolean[] bubbles) {
    final var pinNames = delegate.getPortNames();
    for (final var row : spec.ports()) {
      if (row.index() >= labels.length) {
        throw new IllegalStateException(
            "Sym" + delegate.getName() + " places port index " + row.index()
                + ", but the chip has only " + labels.length + " ports");
      }
      final var upstream =
          pinNames != null && row.index() < pinNames.length ? pinNames[row.index()] : null;
      if (row.label() == null && upstream == null) {
        throw new IllegalStateException(
            "Sym" + delegate.getName() + " port " + row.index() + " has no name: the chip declares"
                + " none, so the layout table has to give one");
      }
      final var stripped = upstream == null ? null : ACTIVE_LOW_PREFIX.matcher(upstream);
      final var marked = stripped != null && stripped.matches();
      labels[row.index()] =
          row.label() != null ? row.label() : marked ? stripped.group(1) + stripped.group(2) : upstream;
      bubbles[row.index()] =
          row.bubble() || marked || (upstream != null && upstream.contains(ACTIVE_LOW_WORDS));
    }
  }

  @Override
  public void propagate(InstanceState state) {
    delegate.propagateTtl(state);
  }
}
