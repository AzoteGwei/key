/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The {@code params} member of a request, ready to be read as a typed object.
 *
 * <p>
 * Only named parameters are accepted. Positional arrays are rejected: named objects are markedly
 * easier for an agent to generate correctly, and the protocol says so.
 */
public final class Params {

    private final ObjectMapper mapper;
    private final @Nullable JsonNode node;

    Params(ObjectMapper mapper, @Nullable JsonNode node) {
        this.mapper = mapper;
        this.node = node;
    }

    /**
     * Reads the parameters as the given type.
     *
     * @param type the expected shape
     * @param <T> the expected shape
     * @return the deserialised parameters
     * @throws RpcException with {@link RpcErrorCode#INVALID_PARAMS} when they do not fit
     */
    public <T> T as(Class<T> type) {
        if (node == null || node.isNull()) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "Method requires parameters but none were given");
        }
        if (!node.isObject()) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "Parameters must be a named object, not " + node.getNodeType());
        }
        try {
            return mapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "Parameters do not fit this method: " + e.getMessage(), null, e);
        }
    }
}
