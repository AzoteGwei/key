/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.json;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void parseObject() {
        Map<String, Object> parsed = Json.parseObject("{\"name\":\"key\",\"count\":42,\"ok\":true,\"list\":[1,2,3]}");
        assertThat(parsed.get("name")).isEqualTo("key");
        assertThat(parsed.get("count")).isEqualTo(42);
        assertThat(parsed.get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(parsed.get("list")).isInstanceOf(List.class);
    }

    @Test
    void stringify() {
        Map<String, Object> map = Json.object();
        map.put("name", "key");
        map.put("count", 42);
        map.put("ok", true);
        map.put("list", List.of(1, 2, 3));
        String json = Json.stringify(map);
        assertThat(json).contains("\"name\":\"key\"");
        assertThat(json).contains("\"count\":42");
    }

    @Test
    void parseInvalidObjectThrows() {
        assertThatThrownBy(() -> Json.parseObject("[1,2,3]"))
            .isInstanceOf(JsonParseException.class);
    }

    @Test
    void roundTripSpecialCharacters() {
        Map<String, Object> map = Json.object();
        map.put("text", "Hello\nWorld\\\"Quote\"");
        String json = Json.stringify(map);
        Map<String, Object> parsed = Json.parseObject(json);
        assertThat(parsed.get("text")).isEqualTo("Hello\nWorld\\\"Quote\"");
    }
}
