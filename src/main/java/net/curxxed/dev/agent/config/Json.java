package net.curxxed.dev.agent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small strict JSON reader; paths must be decoded, not split at braces/quotes. */
public final class Json {
    private final String source;
    private int position;
    private Json(String source) { this.source = source; }
    public static Object parse(String source) {
        Json parser = new Json(source);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != source.length()) throw parser.error("Trailing content");
        return result;
    }
    private Object value(int depth) {
        if (depth > 64) throw error("Excessive JSON nesting");
        space();
        if (position == source.length()) throw error("Unexpected end");
        char next = source.charAt(position);
        if (next == '"') return string();
        if (next == '{') {
            position++;
            Map<String, Object> object = new LinkedHashMap<>();
            if (take('}')) return object;
            do {
                space();
                if (position == source.length() || source.charAt(position) != '"') throw error("Expected object key");
                String key = string();
                if (!take(':')) throw error("Expected colon");
                if (object.containsKey(key)) throw error("Duplicate key: " + key);
                object.put(key, value(depth + 1));
            } while (take(','));
            if (!take('}')) throw error("Expected closing brace");
            return object;
        }
        if (next == '[') {
            position++;
            java.util.List<Object> array = new ArrayList<>();
            if (take(']')) return array;
            do { array.add(value(depth + 1)); } while (take(','));
            if (!take(']')) throw error("Expected closing bracket");
            return array;
        }
        for (String literal : new String[]{"true", "false", "null"}) {
            if (source.startsWith(literal, position)) {
                position += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = position;
        while (position < source.length() && "-+0123456789.eE".indexOf(source.charAt(position)) >= 0) position++;
        String number = source.substring(start, position);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error("Invalid value");
        return Double.valueOf(number);
    }
    private String string() {
        position++;
        StringBuilder result = new StringBuilder();
        while (position < source.length()) {
            char next = source.charAt(position++);
            if (next == '"') return result.toString();
            if (next < 32) throw error("Control character in string");
            if (next != '\\') { result.append(next); continue; }
            if (position == source.length()) throw error("Unfinished escape");
            char escape = source.charAt(position++);
            switch (escape) {
                case '"': case '\\': case '/': result.append(escape); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (position + 4 > source.length()) throw error("Unfinished Unicode escape");
                    String hex = source.substring(position, position + 4);
                    if (!hex.matches("[0-9a-fA-F]{4}")) throw error("Invalid Unicode escape");
                    result.append((char) Integer.parseInt(hex, 16)); position += 4; break;
                default: throw error("Invalid string escape");
            }
        }
        throw error("Unterminated string");
    }
    private void space() {
        while (position < source.length() && " \t\r\n".indexOf(source.charAt(position)) >= 0) position++;
    }
    private boolean take(char token) {
        space();
        if (position == source.length() || source.charAt(position) != token) return false;
        position++; return true;
    }
    private IllegalArgumentException error(String message) {
        int line = 1;
        int column = 1;

        for (int i = 0; i < position && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++; column = 1;
            } else {
                column++;
            }
        }
        return new IllegalArgumentException(message + " at line " + line + ", column " + column);
    }
}
