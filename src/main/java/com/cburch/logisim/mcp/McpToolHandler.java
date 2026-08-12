/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

@FunctionalInterface
interface McpToolHandler {
  JsonElement handle(JsonObject arguments) throws Exception;
}
