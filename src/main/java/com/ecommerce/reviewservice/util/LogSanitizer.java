package com.ecommerce.reviewservice.util;

/** Makes client-supplied text safe to put in a log line: no control characters, at most 256 characters. */
public final class LogSanitizer {

    private static final int MAX_LENGTH = 256;

    private LogSanitizer() {
    }

    public static String clean(String value) {
        if (value == null) {
            return "null";
        }
        String capped = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
        StringBuilder sb = new StringBuilder(capped.length());
        capped.codePoints().forEach(cp -> sb.appendCodePoint(Character.isISOControl(cp) ? '_' : cp));
        return sb.toString();
    }
}
