package com.agentplatform.identity;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class TemporaryPasswordGenerator {
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGIT = "23456789".toCharArray();
    private static final char[] SYMBOL = "!@#$%*-_+".toCharArray();
    private static final char[] ALL = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%*-_+".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] value = new char[18];
        value[0] = pick(UPPER); value[1] = pick(LOWER); value[2] = pick(DIGIT); value[3] = pick(SYMBOL);
        for (int i = 4; i < value.length; i++) value[i] = pick(ALL);
        for (int i = value.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1); char c = value[i]; value[i] = value[j]; value[j] = c;
        }
        return new String(value);
    }

    private char pick(char[] values) { return values[random.nextInt(values.length)]; }
}
