/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.key_project.server.dto.TaskHandle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes task events to clients that asked to be told rather than to ask.
 *
 * <p>
 * Polling {@code task.get} works and stays supported, but it forces a client to choose between
 * asking too often and learning too late, and a proof search gives no hint about which. Here the
 * server says when something happened.
 *
 * <p>
 * Progress is coalesced. KeY reports progress on every rule application, which is thousands of
 * events a second on a real proof — far more than any client wants and more than a socket should
 * be asked to carry. Only the most recent progress per task survives each flush interval.
 * Completion is never coalesced and never dropped: it is the event a client is actually waiting
 * for, and losing it would leave a caller waiting forever for something that already happened.
 */
public final class SseHub implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SseHub.class);

    /** How often coalesced progress is flushed. */
    private static final long FLUSH_MILLIS = 100;

    /**
     * How often a comment line is sent to a stream that has nothing to say.
     *
     * <p>
     * Idle connections are dropped by all sorts of things in between — proxies, container
     * networks, NAT tables — and a stream that dies quietly is worse than one that was never
     * opened, because the client goes on believing it will be told.
     */
    private static final long HEARTBEAT_SECONDS = 15;

    private final ObjectMapper mapper;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** The latest progress of each task that has not been flushed yet. */
    private final Map<String, TaskHandle> pendingProgress = new LinkedHashMap<>();

    private final ScheduledExecutorService dispatch =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "key-sse");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Creates the hub and starts its flush and heartbeat timers.
     *
     * @param mapper the JSON mapper used to write notifications
     */
    public SseHub(ObjectMapper mapper) {
        this.mapper = mapper;
        dispatch.scheduleWithFixedDelay(this::flushProgress, FLUSH_MILLIS, FLUSH_MILLIS,
            TimeUnit.MILLISECONDS);
        dispatch.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
            TimeUnit.SECONDS);
    }

    /**
     * Takes over an exchange and holds it open as an event stream.
     *
     * <p>
     * The exchange must not be closed by the caller afterwards; it belongs to the hub until the
     * client goes away or the instance shuts down.
     *
     * @param exchange the {@code GET} exchange to convert into a stream
     * @throws IOException when the response headers cannot be written
     */
    public void subscribe(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        // Understood by nginx and several other reverse proxies: without it a proxy may buffer
        // the stream and deliver nothing until it closes, which defeats the point entirely.
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        Subscriber subscriber = new Subscriber(exchange);
        subscribers.add(subscriber);
        LOGGER.debug("Event stream opened; {} now listening", subscribers.size());
        // A first byte immediately, so the client knows the stream is live rather than waiting on
        // a connection that may or may not have been accepted.
        dispatch.execute(() -> subscriber.writeComment("open"));
    }

    /**
     * Records that a task moved on.
     *
     * @param handle the task as it now stands
     */
    public void progressed(TaskHandle handle) {
        synchronized (pendingProgress) {
            pendingProgress.put(handle.taskId(), handle);
        }
    }

    /**
     * Announces that a task reached a terminal state.
     *
     * @param handle the finished task, carrying its result or its error
     */
    public void finished(TaskHandle handle) {
        synchronized (pendingProgress) {
            // The finished handle supersedes anything still queued for this task.
            pendingProgress.remove(handle.taskId());
        }
        dispatch.execute(() -> broadcast("task.finished", handle));
    }

    /**
     * How many clients are listening.
     *
     * @return the number of open streams
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    private void flushProgress() {
        List<TaskHandle> due;
        synchronized (pendingProgress) {
            if (pendingProgress.isEmpty()) {
                return;
            }
            due = List.copyOf(pendingProgress.values());
            pendingProgress.clear();
        }
        for (TaskHandle handle : due) {
            broadcast("task.progress", handle);
        }
    }

    private void heartbeat() {
        for (Subscriber subscriber : subscribers) {
            subscriber.writeComment("keepalive");
        }
    }

    private void broadcast(String method, Object params) {
        if (subscribers.isEmpty()) {
            return;
        }
        String payload;
        try {
            ObjectNode notification = mapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            notification.set("params", mapper.valueToTree(params));
            // No "id": a notification is not a request and expects no answer.
            payload = mapper.writeValueAsString(notification);
        } catch (Exception e) {
            LOGGER.error("Failed to serialise a {} notification", method, e);
            return;
        }
        for (Subscriber subscriber : subscribers) {
            subscriber.write("data: " + payload + "\n\n");
        }
    }

    @Override
    public void close() {
        dispatch.shutdownNow();
        for (Subscriber subscriber : subscribers) {
            subscriber.discard();
        }
        subscribers.clear();
    }

    /** One client holding a stream open. */
    private final class Subscriber {

        private final HttpExchange exchange;
        private final OutputStream out;

        Subscriber(HttpExchange exchange) {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        void writeComment(String text) {
            write(": " + text + "\n\n");
        }

        void write(String frame) {
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                // The client went away. That is ordinary, not an error worth a stack trace.
                LOGGER.debug("Event stream closed by the client", e);
                subscribers.remove(this);
                discard();
            }
        }

        void discard() {
            try {
                exchange.close();
            } catch (RuntimeException e) {
                LOGGER.debug("Failed to close an event stream", e);
            }
        }
    }
}
