/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.protocol;

import java.util.Map;
import java.util.Objects;

import de.uka.ilkd.key.mcp.json.Json;

/**
 * Factory for MCP resource content objects.
 *
 * <p>
 * The MCP schema ({@code ReadResourceResult.contents}) requires every content item to be
 * either a {@code TextResourceContents} ({@code uri} + {@code text}) or a
 * {@code BlobResourceContents} ({@code uri} + {@code blob}). Clients such as Claude Code
 * validate responses strictly and reject content items missing these fields, so all
 * resource contents emitted by this server must be constructed through this factory
 * instead of assembling ad-hoc maps.
 * </p>
 */
public final class ResourceContents {
    private ResourceContents() {
    }

    /**
     * Creates a text resource content item.
     *
     * @param uri the resource URI, must not be {@code null}
     * @param mimeType the MIME type, may be {@code null} (the field is then omitted)
     * @param text the textual payload, must not be {@code null}
     * @return a schema-compliant {@code TextResourceContents} map
     */
    public static Map<String, Object> text(String uri, String mimeType, String text) {
        Map<String, Object> content = base(uri, mimeType);
        content.put("text", Objects.requireNonNull(text, "text must not be null"));
        return content;
    }

    /**
     * Creates a binary (base64) resource content item.
     *
     * @param uri the resource URI, must not be {@code null}
     * @param mimeType the MIME type, may be {@code null} (the field is then omitted)
     * @param blob the base64-encoded payload, must not be {@code null}
     * @return a schema-compliant {@code BlobResourceContents} map
     */
    public static Map<String, Object> blob(String uri, String mimeType, String blob) {
        Map<String, Object> content = base(uri, mimeType);
        content.put("blob", Objects.requireNonNull(blob, "blob must not be null"));
        return content;
    }

    private static Map<String, Object> base(String uri, String mimeType) {
        Map<String, Object> content = Json.object();
        content.put("uri", Objects.requireNonNull(uri, "uri must not be null"));
        if (mimeType != null) {
            content.put("mimeType", mimeType);
        }
        return content;
    }
}
