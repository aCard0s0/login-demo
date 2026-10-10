package com.demo.mcp.server;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArgsTests {

    @Test
    void aNumberIsExactOrRefused() {
        assertEquals(5L, Args.number(Map.of("id", 5), "id"));
        assertEquals(5L, Args.number(Map.of("id", 5.0), "id"));
        assertEquals(5L, Args.number(Map.of("id", "5"), "id"));
        for (Object bad : new Object[]{5.7, "5.7", "five", BigInteger.TWO.pow(64).add(BigInteger.valueOf(5))}) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> Args.number(Map.of("id", bad), "id"), String.valueOf(bad));
            assertEquals("id must be a whole number", e.getReason());
        }
        assertThrows(ResponseStatusException.class, () -> Args.number(Map.of(), "id"), "a missing number is not zero");
    }
}
