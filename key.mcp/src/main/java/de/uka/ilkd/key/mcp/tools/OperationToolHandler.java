/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.McpToolException;
import de.uka.ilkd.key.mcp.operation.Operation;

/**
 * Tools for polling and cancelling long-running operations.
 */
public class OperationToolHandler extends ToolHandler {

    public OperationToolHandler(ToolContext ctx) {
        super(ctx);
    }

    @Override
    public void registerTools(Map<String, ToolDefinition> tools) {
        register(tools, "key_operation_wait", "Poll events for a long-running operation.",
            props(
                "operationId", Map.of("type", "string"),
                "timeoutMs", Map.of("type", "integer", "default", 30000)),
            List.of("operationId"), this::handleOperationWait);

        register(tools, "key_operation_cancel", "Cancel a long-running operation.",
            props("operationId", Map.of("type", "string")), List.of("operationId"),
            this::handleOperationCancel);
    }

    private Map<String, Object> handleOperationWait(Map<String, Object> params) {
        String operationId = (String) params.get("operationId");
        long waitTimeoutMs = ToolContext.longValue(params.get("timeoutMs"), 30000L);
        Operation operation = ctx.session().getOperationTracker().get(operationId);
        if (operation == null) {
            throw new McpToolException(-32003, "Operation not found: " + operationId, null);
        }
        try {
            operation.await(waitTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ctx.statusOf(operation);
    }

    private Map<String, Object> handleOperationCancel(Map<String, Object> params) {
        String operationId = (String) params.get("operationId");
        Operation operation = ctx.session().getOperationTracker().get(operationId);
        if (operation == null) {
            throw new McpToolException(-32003, "Operation not found: " + operationId, null);
        }
        Thread worker = operation.getWorkerThread();
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
        }
        operation.addCancelledEvent();
        return Map.of("cancelled", true);
    }
}
