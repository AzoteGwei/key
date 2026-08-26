/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.protocol;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ResourceContentsTest {

    @Test
    void textContentCarriesRequiredFields() {
        Map<String, Object> content =
            ResourceContents.text("session:///info", "application/json", "{}");
        assertThat(content.get("uri")).isEqualTo("session:///info");
        assertThat(content.get("mimeType")).isEqualTo("application/json");
        assertThat(content.get("text")).isEqualTo("{}");
        assertThat(content).doesNotContainKey("blob");
    }

    @Test
    void nullMimeTypeIsOmitted() {
        Map<String, Object> content = ResourceContents.text("proof://p/status", null, "{}");
        assertThat(content).doesNotContainKey("mimeType");
    }

    @Test
    void requiredFieldsMustNotBeNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> ResourceContents.text(null, "application/json", "{}"));
        assertThatNullPointerException()
                .isThrownBy(
                    () -> ResourceContents.text("session:///info", "application/json", null));
        assertThatNullPointerException()
                .isThrownBy(() -> ResourceContents.blob("proof://p/export", "text/plain", null));
    }
}
