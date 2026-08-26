/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

/**
 * Tools for inspecting and resetting the MCP session.
 */
public class SessionToolHandler extends ToolHandler {

    public SessionToolHandler(ToolContext ctx) {
        super(ctx);
    }

    @Override
    public void registerTools(Map<String, ToolDefinition> tools) {
        register(tools, "key_session_info", "Get information about the current MCP session.",
            Map.of(), List.of(),
            objectSchema(List.of("sessionId", "environmentLoaded", "contractCount", "proofCount"),
                props(
                    "sessionId", stringSchema(),
                    "environmentLoaded", booleanSchema(),
                    "contractCount", integerSchema(),
                    "proofCount", integerSchema())),
            annotations(true, false, true),
            this::handleSessionInfo);
        register(tools, "key_session_reset",
            "Reset the session, disposing all proofs and the current KeY environment.",
            Map.of(), List.of(),
            objectSchema(List.of("success"),
                props("success", booleanSchema())),
            annotations(false, true, true),
            this::handleSessionReset);
        register(tools, "key_session_dispose", "Dispose the session and release all resources.",
            Map.of(), List.of(),
            objectSchema(List.of("success", "disposed"),
                props(
                    "success", booleanSchema(),
                    "disposed", booleanSchema())),
            annotations(false, true, true),
            this::handleSessionDispose);
    }

    private Map<String, Object> handleSessionInfo(Map<String, Object> params) {
        Map<String, Object> result = Json.object();
        result.put("sessionId", ctx.session().getId());
        result.put("environmentLoaded", ctx.session().getEnvironment() != null);
        result.put("contractCount", ctx.session().getContracts().size());
        result.put("proofCount", ctx.session().getProofs().size());
        return result;
    }

    private Map<String, Object> handleSessionReset(Map<String, Object> params) {
        ctx.session().dispose();
        return Map.of("success", true);
    }

    private Map<String, Object> handleSessionDispose(Map<String, Object> params) {
        ctx.session().dispose();
        return Map.of("success", true, "disposed", true);
    }
}
