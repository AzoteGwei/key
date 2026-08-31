/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.key_project.server.rpc.JsonRpcDispatcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single JSON-RPC endpoint, served by the JDK's own HTTP server.
 *
 * <p>
 * The protocol needs one {@code POST /rpc} for calls and one {@code GET /rpc} for the event
 * stream, which is well within what {@code com.sun.net.httpserver} does. Pulling in a web framework
 * for two routes would add a dependency tree out of proportion to the job.
 *
 * <p>
 * The listener binds to the loopback address only. There is no option to bind elsewhere, because
 * there is no authentication yet; reach a remote instance through an SSH tunnel.
 */
public final class HttpTransport implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpTransport.class);
    private static final String ENDPOINT = "/rpc";
    private static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;

    private final JsonRpcDispatcher dispatcher;
    private final SseHub events;
    private final HttpServer server;
    private final ExecutorService connections;
    private final Runnable requestObserver;

    /**
     * Binds the endpoint.
     *
     * @param dispatcher the dispatcher requests are routed to
     * @param events the hub that serves the event stream
     * @param port the port to bind, {@code 0} to let the OS choose
     * @param requestObserver notified on every request, used to reset the idle timer
     * @throws IOException when the port cannot be bound
     */
    public HttpTransport(JsonRpcDispatcher dispatcher, SseHub events, int port,
            Runnable requestObserver) throws IOException {
        this.dispatcher = dispatcher;
        this.events = events;
        this.requestObserver = requestObserver;
        this.connections = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "key-http");
            thread.setDaemon(true);
            return thread;
        });
        this.server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.createContext(ENDPOINT, this::handle);
        this.server.setExecutor(connections);
    }

    /** Starts accepting requests. */
    public void start() {
        server.start();
        LOGGER.info("Listening on http://127.0.0.1:{}{}", port(), ENDPOINT);
    }

    /**
     * The port actually bound.
     *
     * @return the local port, resolved even when {@code 0} was requested
     */
    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            // The event stream. It takes ownership of the exchange and holds it open, so this
            // must not fall through to the close in the finally below.
            requestObserver.run();
            events.subscribe(exchange);
            return;
        }
        try {
            requestObserver.run();
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain; charset=utf-8",
                    "Only POST for calls and GET for the event stream are supported on "
                        + ENDPOINT);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES);
            String response = dispatcher.handle(new String(body, StandardCharsets.UTF_8));
            if (response == null) {
                // A notification: the protocol says there is no response at all.
                respond(exchange, 204, null, null);
            } else {
                respond(exchange, 200, "application/json; charset=utf-8", response);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Failed to handle a request", e);
            respond(exchange, 500, "text/plain; charset=utf-8", "Internal error");
        } finally {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, @Nullable String contentType,
            @Nullable String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        connections.shutdownNow();
    }
}
