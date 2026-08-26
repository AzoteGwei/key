/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.protocol;

import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

/**
 * Protocol-level constants and result-envelope helpers for the MCP revisions this server
 * speaks.
 *
 * <p>
 * The server is <em>dual-era</em> (see
 * {@code docs/specification/2026-07-28/basic/versioning.mdx}): it answers the legacy
 * {@code initialize} handshake (revision {@value #VERSION_2025_11_25}) used by current
 * clients such as Claude Code, and it serves modern, stateless requests
 * (revision {@value #VERSION_2026_07_28}) that carry their protocol version in
 * {@code _meta} and are answered with {@code resultType}-annotated results.
 * </p>
 */
public final class McpProtocol {
    /** Legacy revision: session established via the {@code initialize} handshake. */
    public static final String VERSION_2025_11_25 = "2025-11-25";

    /** Modern revision: per-request {@code _meta} metadata, no handshake. */
    public static final String VERSION_2026_07_28 = "2026-07-28";

    /** All protocol revisions this server supports, newest first. */
    public static final List<String> SUPPORTED_VERSIONS =
        List.of(VERSION_2026_07_28, VERSION_2025_11_25);

    /** Request {@code _meta} key carrying the protocol version of a modern request. */
    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /** Request {@code _meta} key carrying the client identity (optional). */
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    /** Request {@code _meta} key carrying the client capabilities (required, modern). */
    public static final String META_CLIENT_CAPABILITIES =
        "io.modelcontextprotocol/clientCapabilities";

    /** Result {@code _meta} key carrying the server identity. */
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    /** MCP error code: the requested protocol version is not supported. */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /** JSON-RPC error code: invalid params. */
    public static final int INVALID_PARAMS = -32602;

    /** JSON-RPC error code: method not found. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** JSON-RPC error code: invalid request. */
    public static final int INVALID_REQUEST = -32600;

    /** Result type for ordinary, fully processed results. */
    public static final String RESULT_TYPE_COMPLETE = "complete";

    /** Cache TTL (ms) for results that never change during the process lifetime. */
    public static final long TTL_STATIC_MS = 3_600_000L;

    /**
     * Cache TTL (ms) for results that may change at any time (e.g. as proofs are created
     * or advanced): immediately stale.
     */
    public static final long TTL_DYNAMIC_MS = 0L;

    private McpProtocol() {
    }

    /**
     * Decorates a result map with the fields required by the modern protocol revision:
     * {@code resultType} and {@code _meta} carrying the server identity. Must be applied
     * to every result sent for a modern (per-request {@code _meta}) request, and must not
     * be applied to legacy results.
     *
     * @param result the result map to decorate in place
     * @param serverInfo the server identity (name/version)
     * @return the same map, for chaining
     */
    public static Map<String, Object> modernResult(Map<String, Object> result,
            Map<String, Object> serverInfo) {
        result.put("resultType", RESULT_TYPE_COMPLETE);
        Object meta = result.get("_meta");
        Map<String, Object> metaMap;
        if (meta instanceof Map) {
            metaMap = castMap(meta);
        } else {
            metaMap = Json.object();
            result.put("_meta", metaMap);
        }
        metaMap.put(META_SERVER_INFO, serverInfo);
        return result;
    }

    /**
     * Adds the {@code ttlMs}/{@code cacheScope} fields required by {@code CacheableResult}
     * (modern revision) to a result map.
     *
     * @param result the result map to decorate in place
     * @param ttlMs freshness hint in milliseconds ({@code 0} = immediately stale)
     * @param cacheScope {@code "public"} or {@code "private"}
     * @return the same map, for chaining
     */
    public static Map<String, Object> cacheable(Map<String, Object> result, long ttlMs,
            String cacheScope) {
        result.put("ttlMs", ttlMs);
        result.put("cacheScope", cacheScope);
        return result;
    }

    /**
     * Translates error codes for the modern revision: codes from the legacy
     * implementation-defined sub-range {@code -32000..-32019} (which new implementations
     * should not emit) are mapped to {@code -32602} (Invalid params), as mandated by the
     * 2026-07-28 revision for e.g. unknown resources.
     *
     * @param code the code a handler raised
     * @param modern whether the request is served under the modern revision
     * @return the code to put on the wire
     */
    public static int wireErrorCode(int code, boolean modern) {
        if (modern && code <= -32000 && code >= -32019) {
            return INVALID_PARAMS;
        }
        return code;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
