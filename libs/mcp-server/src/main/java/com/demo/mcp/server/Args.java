package com.demo.mcp.server;

import com.demo.web.errors.Bad;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Reading a tool call's arguments, which arrive as whatever JSON the model sent. A malformed one is a 400
 * {@link ResponseStatusException}: the model's mistake, rendered as an error result, never a refusal.
 */
public final class Args {

    private Args() {}

    /**
     * A whole number that fits a long. 40.9 cents is refused rather than silently becoming 40, 5.7 is not
     * record 5, and 2^64+5 is not record 5 either: exact, or an error.
     */
    public static Long number(Map<String, Object> args, String key) {
        try {
            return new BigDecimal(String.valueOf(args.get(key))).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw bad(key + " must be a whole number");
        }
    }

    /** The value as text, or null when it was left out. */
    public static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** The value as a flag, or null when it was left out. */
    public static Boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    /** A list of strings, or null when it was left out. */
    public static List<String> strings(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw bad(key + " must be a list");
    }

    /** The same 400 as {@link Bad#request}, kept here so a tool's argument checks read in one voice. */
    public static ResponseStatusException bad(String why) {
        return Bad.request(why);
    }
}
