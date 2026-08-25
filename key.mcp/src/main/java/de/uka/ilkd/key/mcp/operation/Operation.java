/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

/**
 * Represents a long-running operation that an Agent can wait for or cancel.
 */
public class Operation {
    public enum State {
        RUNNING, COMPLETED, CANCELLED, TIMEOUT, ERROR
    }

    private final String id;
    private final String proofId;
    private final String operationType;
    private final long createdAt;
    private final List<Map<String, Object>> events = new ArrayList<>();
    private final Object lock = new Object();

    private volatile State state = State.RUNNING;
    private volatile String errorMessage;
    private Thread workerThread;

    public Operation(String id, String proofId, String operationType) {
        this.id = id;
        this.proofId = proofId;
        this.operationType = operationType;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getProofId() {
        return proofId;
    }

    public State getState() {
        return state;
    }

    public void setWorkerThread(Thread workerThread) {
        this.workerThread = workerThread;
    }

    public Thread getWorkerThread() {
        return workerThread;
    }

    public void addProgressEvent(int steps, int openGoals) {
        Map<String, Object> event = Json.object();
        event.put("type", "progress");
        event.put("steps", steps);
        event.put("openGoals", openGoals);
        event.put("timestamp", isoTimestamp());
        addEvent(event);
    }

    public void addCompletedEvent(boolean closed, int openGoals, int usedSteps, long durationMs) {
        if (!transitionTo(State.COMPLETED)) {
            return;
        }
        Map<String, Object> event = Json.object();
        event.put("type", "completed");
        event.put("closed", closed);
        event.put("openGoals", openGoals);
        event.put("usedSteps", usedSteps);
        event.put("durationMs", durationMs);
        event.put("timestamp", isoTimestamp());
        addEvent(event);
    }

    public void addCancelledEvent() {
        if (!transitionTo(State.CANCELLED)) {
            return;
        }
        Map<String, Object> event = Json.object();
        event.put("type", "cancelled");
        event.put("timestamp", isoTimestamp());
        addEvent(event);
    }

    public void addTimeoutEvent() {
        if (!transitionTo(State.TIMEOUT)) {
            return;
        }
        Map<String, Object> event = Json.object();
        event.put("type", "timeout");
        event.put("timestamp", isoTimestamp());
        addEvent(event);
    }

    public void addErrorEvent(String message) {
        if (!transitionTo(State.ERROR)) {
            return;
        }
        this.errorMessage = message;
        Map<String, Object> event = Json.object();
        event.put("type", "error");
        event.put("message", message);
        event.put("timestamp", isoTimestamp());
        addEvent(event);
    }

    /**
     * Transitions from {@link State#RUNNING} to the given terminal state.
     *
     * @return {@code true} if the transition happened; {@code false} if the operation
     *         already reached a terminal state
     */
    private boolean transitionTo(State newState) {
        synchronized (lock) {
            if (this.state == State.RUNNING) {
                this.state = newState;
                return true;
            }
            return false;
        }
    }

    private void addEvent(Map<String, Object> event) {
        synchronized (events) {
            events.add(event);
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    /**
     * Returns a snapshot of all events recorded so far.
     */
    public List<Map<String, Object>> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Waits until the operation is no longer running or the poll timeout expires.
     *
     * @param pollTimeoutMs maximum time to wait for a state change
     * @return {@code true} if the operation finished while waiting
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean await(long pollTimeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + pollTimeoutMs;
        synchronized (lock) {
            while (state == State.RUNNING && System.currentTimeMillis() < deadline) {
                lock.wait(Math.max(1, deadline - System.currentTimeMillis()));
            }
            return state != State.RUNNING;
        }
    }

    private static String isoTimestamp() {
        return java.time.Instant.now().toString();
    }
}
