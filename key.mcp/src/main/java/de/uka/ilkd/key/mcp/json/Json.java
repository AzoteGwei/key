/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.json;

import java.util.*;

/**
 * A tiny JSON parser and serializer used by the KeY MCP server.
 *
 * <p>This implementation intentionally avoids third-party JSON libraries to keep the
 * {@code key.mcp} module free of additional runtime dependencies and potential license
 * conflicts with KeY's GPL-2.0-only licensing.</p>
 *
 * <p>Supported value types:</p>
 * <ul>
 *   <li>{@link Map} (JSON object, iteration order preserved via {@link LinkedHashMap})</li>
 *   <li>{@link List} (JSON array)</li>
 *   <li>{@link String}</li>
 *   <li>{@link Number} ({@link Long} for integral values, {@link Double} otherwise)</li>
 *   <li>{@link Boolean}</li>
 *   <li>{@code null} for JSON {@code null}</li>
 * </ul>
 */
public final class Json {
    private Json() {
    }

    /**
     * Parses a JSON string into a Java object.
     *
     * @param input the JSON text
     * @return the parsed value
     * @throws JsonParseException if the input is not valid JSON
     */
    public static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < parser.length) {
            throw new JsonParseException("Unexpected trailing content at position " + parser.pos);
        }
        return value;
    }

    /**
     * Parses a JSON string into a JSON object ({@link Map}).
     *
     * @param input the JSON text
     * @return the parsed object
     * @throws JsonParseException if the input is not a JSON object or is invalid
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String input) {
        Object value = parse(input);
        if (!(value instanceof Map)) {
            throw new JsonParseException("Expected JSON object, got " + value.getClass().getSimpleName());
        }
        return (Map<String, Object>) value;
    }

    /**
     * Serializes a Java object to a JSON string.
     *
     * @param value the value to serialize
     * @return the JSON text
     * @throws IllegalArgumentException if the value type is not supported
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(entry.getKey(), sb);
                sb.append(':');
                write(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            sb.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(element, sb);
            }
            sb.append(']');
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            sb.append(value);
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '"':
                sb.append("\\\"");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            default:
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /**
     * Creates a mutable JSON object map.
     */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /**
     * Creates a mutable JSON array list.
     */
    public static List<Object> array() {
        return new ArrayList<>();
    }

    private static final class Parser {
        private final String input;
        private final int length;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.length = input.length();
            this.pos = 0;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= length) {
                throw new JsonParseException("Unexpected end of input");
            }
            char c = input.charAt(pos);
            return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield parseNumber();
                }
                throw new JsonParseException("Unexpected character '" + c + "' at position " + pos);
            }
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = input.charAt(pos++);
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw new JsonParseException("Expected ',' or '}' at position " + (pos - 1));
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = input.charAt(pos++);
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw new JsonParseException("Expected ',' or ']' at position " + (pos - 1));
                }
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < length) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= length) {
                        throw new JsonParseException("Invalid escape at end of input");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > length) {
                            throw new JsonParseException("Invalid unicode escape");
                        }
                        String hex = input.substring(pos, pos + 4);
                        try {
                            int code = Integer.parseInt(hex, 16);
                            sb.append((char) code);
                            pos += 4;
                        } catch (NumberFormatException e) {
                            throw new JsonParseException("Invalid unicode escape: " + hex);
                        }
                    }
                    default -> throw new JsonParseException("Invalid escape character: \\" + esc);
                    }
                } else if (c < 0x20) {
                    throw new JsonParseException("Unescaped control character at position " + (pos - 1));
                } else {
                    sb.append(c);
                }
            }
            throw new JsonParseException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (match("true")) {
                return Boolean.TRUE;
            }
            if (match("false")) {
                return Boolean.FALSE;
            }
            throw new JsonParseException("Expected boolean at position " + pos);
        }

        private Object parseNull() {
            if (match("null")) {
                return null;
            }
            throw new JsonParseException("Expected null at position " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean hasDigits = false;
            while (pos < length && isDigit(input.charAt(pos))) {
                pos++;
                hasDigits = true;
            }
            boolean isDouble = false;
            if (pos < length && input.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < length && isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < length && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < length && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < length && isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (!hasDigits) {
                throw new JsonParseException("Invalid number at position " + start);
            }
            String text = input.substring(start, pos);
            try {
                if (isDouble) {
                    return Double.parseDouble(text);
                }
                long value = Long.parseLong(text);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException e) {
                throw new JsonParseException("Invalid number: " + text);
            }
        }

        private void skipWhitespace() {
            while (pos < length) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return pos < length ? input.charAt(pos) : '\0';
        }

        private void expect(char expected) {
            if (pos >= length || input.charAt(pos) != expected) {
                throw new JsonParseException("Expected '" + expected + "' at position " + pos);
            }
            pos++;
        }

        private boolean match(String text) {
            if (input.regionMatches(pos, text, 0, text.length())) {
                pos += text.length();
                return true;
            }
            return false;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
