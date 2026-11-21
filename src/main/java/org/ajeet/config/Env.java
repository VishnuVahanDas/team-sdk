package org.ajeet.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Config lookup shared by the web backend and the sync agent: process
 * environment first, then a KEY=VALUE line in a {@code .env} file in the
 * working directory, then the default.
 */
public final class Env {

    private static Map<String, String> dotenv;

    private Env() {}

    public static String get(String key, String def) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = dotenv().get(key);
        }
        return (value == null || value.isBlank()) ? def : value;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    private static synchronized Map<String, String> dotenv() {
        if (dotenv == null) {
            dotenv = new HashMap<>();
            try {
                Path envFile = Path.of(".env");
                if (Files.exists(envFile)) {
                    for (String line : Files.readAllLines(envFile)) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) continue;
                        int eq = s.indexOf('=');
                        if (eq > 0) {
                            dotenv.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                        }
                    }
                    System.out.println("Loaded config from .env");
                }
            } catch (IOException e) {
                System.err.println("Failed to read .env: " + e.getMessage());
            }
        }
        return dotenv;
    }
}
