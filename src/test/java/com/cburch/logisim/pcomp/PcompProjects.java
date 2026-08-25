/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Peler Edition. Small project files for the component tests to work on.
 *
 * <p>Written out as XML and read back through the real reader rather than assembled in memory: the
 * component writer works on what a project file actually contains, and a hand-built {@code
 * LogisimFile} would let a mistake about that pass unnoticed.
 *
 * <p>Every wire here is horizontal or vertical. A diagonal one is not merely drawn oddly -- {@code
 * WireRepair} runs on load and fills the heap on one.
 */
final class PcompProjects {
  private PcompProjects() {}

  /**
   * A project with three circuits: {@code Top}, which uses {@code Half}, and {@code Unrelated},
   * which nothing uses.
   */
  static final String THREE_CIRCUITS =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <project source="4.1.0" version="1.0">
        <lib desc="#Wiring" name="0"/>
        <lib desc="#Gates" name="1"/>
        <main name="Top"/>
        <circuit name="Top">
          <comp lib="0" loc="(100,110)" name="Pin">
            <a name="label" val="A"/>
          </comp>
          <comp lib="0" loc="(100,130)" name="Pin">
            <a name="label" val="B"/>
          </comp>
          <comp lib="0" loc="(400,110)" name="Pin">
            <a name="type" val="output"/>
            <a name="label" val="S"/>
          </comp>
          <comp lib="0" loc="(400,150)" name="Pin">
            <a name="type" val="output"/>
            <a name="label" val="C"/>
          </comp>
          <comp loc="(300,110)" name="Half"/>
          <wire from="(100,110)" to="(240,110)"/>
          <wire from="(100,130)" to="(240,130)"/>
          <wire from="(300,110)" to="(400,110)"/>
        </circuit>
        <circuit name="Half">
          <comp lib="0" loc="(100,110)" name="Pin">
            <a name="label" val="X"/>
          </comp>
          <comp lib="0" loc="(100,130)" name="Pin">
            <a name="label" val="Y"/>
          </comp>
          <comp lib="0" loc="(300,120)" name="Pin">
            <a name="type" val="output"/>
            <a name="label" val="Z"/>
          </comp>
          <comp lib="1" loc="(220,120)" name="AND Gate">
            <a name="inputs" val="2"/>
          </comp>
          <wire from="(100,110)" to="(170,110)"/>
          <wire from="(100,130)" to="(170,130)"/>
          <wire from="(220,120)" to="(300,120)"/>
        </circuit>
        <circuit name="Unrelated">
          <comp lib="0" loc="(100,110)" name="Pin">
            <a name="label" val="Q"/>
          </comp>
        </circuit>
      </project>
      """;

  /** One circuit whose pins carry no labels at all. */
  static final String UNNAMED_PINS =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <project source="4.1.0" version="1.0">
        <lib desc="#Wiring" name="0"/>
        <main name="Bare"/>
        <circuit name="Bare">
          <comp lib="0" loc="(100,110)" name="Pin"/>
          <comp lib="0" loc="(300,110)" name="Pin">
            <a name="type" val="output"/>
          </comp>
        </circuit>
      </project>
      """;

  static LogisimFile read(String xml) throws IOException {
    final var project =
        LogisimFile.load(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), new Loader(null));
    if (project == null) throw new IOException("the fixture did not load");
    return project;
  }
}
