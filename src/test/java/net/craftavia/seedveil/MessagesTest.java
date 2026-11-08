package net.craftavia.seedveil;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every key used in the code must exist in both bundled language files. */
class MessagesTest {

    private static final Pattern KEY_USE = Pattern.compile(
            "\"((?:status|alert|scramble|reload)\\.[a-z0-9\\-]+|busy|command-blocked)\"");

    @Test
    void languageFilesHaveTheSameKeys() throws Exception {
        final Properties en = load("messages_en.properties");
        final Properties tr = load("messages_tr.properties");
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(tr.stringPropertyNames()));
    }

    @Test
    void codeKeysExist() throws Exception {
        final Properties en = load("messages_en.properties");
        final Set<String> used = new TreeSet<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            // Settings.java holds config.yml paths (scramble.*), not message keys.
            for (final var file : files.filter(p -> p.toString().endsWith(".java")
                    && !p.getFileName().toString().equals("Settings.java")).toList()) {
                final Matcher m = KEY_USE.matcher(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }
        assertTrue(used.size() > 20, "expected to find the message keys used in the code: " + used);
        for (final String key : used) {
            assertTrue(en.containsKey(key), "missing message key: " + key);
        }
    }

    private static Properties load(final String name) throws Exception {
        try (InputStream in = MessagesTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, name);
            final Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        }
    }
}
