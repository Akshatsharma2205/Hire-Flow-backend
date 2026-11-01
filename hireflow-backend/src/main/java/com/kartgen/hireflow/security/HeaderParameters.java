package com.kartgen.hireflow.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Strict parser for RFC 7230-style header values of the form
 * {@code token; name=value; name="quoted value"}.
 *
 * <p>Used for both Content-Type and Content-Disposition. Quoted values may
 * contain backslash escapes; duplicate parameter names are rejected because
 * they make the signed/interpreted value ambiguous.</p>
 */
final class HeaderParameters {

    private HeaderParameters() {}

    record Value(String token, Map<String, String> parameters) {

        String parameter(String name) {
            return parameters.get(name.toLowerCase(Locale.ROOT));
        }
    }

    static Value parse(String headerValue) {
        if (headerValue == null) {
            throw new IllegalArgumentException("Missing header value");
        }
        String value = headerValue.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty header value");
        }

        int semicolon = indexOfUnquoted(value, ';');
        String token = (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
        if (!isTokenOrSlash(token)) {
            throw new IllegalArgumentException("Malformed header token");
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        int i = semicolon < 0 ? value.length() : semicolon + 1;
        while (i < value.length()) {
            while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
                i++;
            }
            if (i < value.length() && value.charAt(i) == ';') {
                i++;
                continue;
            }
            if (i >= value.length()) {
                break;
            }

            int nameStart = i;
            while (i < value.length() && value.charAt(i) != '=' && value.charAt(i) != ';') {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n' || c == 0) {
                    throw new IllegalArgumentException("Control character in header parameter name");
                }
                i++;
            }
            if (i >= value.length() || value.charAt(i) != '=') {
                throw new IllegalArgumentException("Header parameter is missing '='");
            }
            String name = value.substring(nameStart, i).trim();
            if (name.isEmpty() || !isToken(name)) {
                throw new IllegalArgumentException("Malformed header parameter name");
            }
            i++;

            while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
                i++;
            }

            String parameterValue;
            if (i < value.length() && value.charAt(i) == '"') {
                StringBuilder quoted = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < value.length()) {
                    char c = value.charAt(i++);
                    if (c == '"') {
                        closed = true;
                        break;
                    }
                    if (c == '\\') {
                        if (i >= value.length()) {
                            throw new IllegalArgumentException("Dangling escape in quoted header value");
                        }
                        char escaped = value.charAt(i++);
                        if (escaped == '\r' || escaped == '\n' || escaped == 0) {
                            throw new IllegalArgumentException("Control character in quoted header value");
                        }
                        quoted.append(escaped);
                    } else {
                        if (c == '\r' || c == '\n' || c == 0) {
                            throw new IllegalArgumentException("Control character in quoted header value");
                        }
                        quoted.append(c);
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unterminated quoted header value");
                }
                parameterValue = quoted.toString();
                while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
                    i++;
                }
                if (i < value.length() && value.charAt(i) != ';') {
                    throw new IllegalArgumentException("Unexpected character after quoted header value");
                }
            } else {
                int valueStart = i;
                while (i < value.length() && value.charAt(i) != ';') {
                    char c = value.charAt(i);
                    if (c == '\r' || c == '\n' || c == 0 || c == '"') {
                        throw new IllegalArgumentException("Malformed unquoted header value");
                    }
                    i++;
                }
                parameterValue = value.substring(valueStart, i).trim();
                if (parameterValue.isEmpty()) {
                    throw new IllegalArgumentException("Empty header parameter value");
                }
            }

            String key = name.toLowerCase(Locale.ROOT);
            if (parameters.putIfAbsent(key, parameterValue) != null) {
                throw new IllegalArgumentException("Duplicate header parameter: " + key);
            }
            if (i < value.length() && value.charAt(i) == ';') {
                i++;
            }
        }
        return new Value(token, Collections.unmodifiableMap(parameters));
    }

    static boolean isToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isTokenChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenOrSlash(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '/' && !isTokenChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenChar(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= 'a' && c <= 'z') return true;
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static int indexOfUnquoted(String value, char needle) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quoted && c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && c == needle) {
                return i;
            }
        }
        return -1;
    }
}
