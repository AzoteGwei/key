/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.operation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks long-running operations by id.
 */
public class OperationTracker {
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public Operation start(String proofId, String operationType) {
        String id = "op_" + counter.incrementAndGet() + "_" + operationType;
        Operation operation = new Operation(id, proofId, operationType);
        operations.put(id, operation);
        return operation;
    }

    public Operation get(String id) {
        return operations.get(id);
    }

    /**
     * Returns all currently tracked operations.
     */
    public java.util.Collection<Operation> getAll() {
        return operations.values();
    }

    public Operation remove(String id) {
        return operations.remove(id);
    }
}
