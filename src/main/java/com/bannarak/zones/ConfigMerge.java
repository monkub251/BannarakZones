package com.bannarak.zones;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries new settings into a config file somebody is already using.
 *
 * <p>The plugin has never overwritten a live config, which is right — it holds
 * the owner's own choices. But it also meant that every section added in a new
 * version was simply absent on an existing server, and the code that read it
 * found nothing and did nothing, quietly. A starter kit that never arrives and
 * says nothing about why is worse than one that was never written.
 *
 * <p>So: whole sections that the shipped config has and the live one does not
 * are appended, with the comments that explain them. Nothing already in the
 * file is read, moved or rewritten, so a setting the owner changed stays
 * changed, and a section they deleted on purpose comes back only once.
 */
final class ConfigMerge {

    private ConfigMerge() { }

    /** Returns the names of the sections it added, in the order it added them. */
    static List<String> run(BannarakZones plugin, File live, String resource) {
        List<String> added = new ArrayList<>();
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null || !live.exists()) return added;

            String shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String current = Files.readString(live.toPath(), StandardCharsets.UTF_8);

            // Which top-level keys are already there. Parsed, not pattern
            // matched, so a key inside a quoted string cannot be mistaken for one.
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(live);
            var have = cfg.getKeys(false);

            Map<String, String> shippedBlocks = split(shipped);
            Map<String, String> liveBlocks = split(current);
            YamlConfiguration ship = new YamlConfiguration();
            ship.loadFromString(shipped);

            // Settings added inside a section that already exists — the easy
            // ones to miss, because the section itself looks present.
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> e : liveBlocks.entrySet()) {
                String body = e.getValue().stripTrailing();
                var shipChild = ship.getConfigurationSection(e.getKey());
                var liveChild = cfg.getConfigurationSection(e.getKey());
                if (shipChild != null && liveChild != null && shippedBlocks.containsKey(e.getKey())) {
                    for (String child : shipChild.getKeys(false)) {
                        if (liveChild.contains(child)) continue;
                        String block = childBlock(shippedBlocks.get(e.getKey()), child);
                        if (block == null) continue;
                        body = body + "\n" + block;
                        added.add(e.getKey() + "." + child);
                    }
                }
                parts.add(body);
            }

            StringBuilder tail = new StringBuilder();
            for (Map.Entry<String, String> e : shippedBlocks.entrySet()) {
                if (have.contains(e.getKey())) continue;
                tail.append('\n').append(e.getValue().stripTrailing()).append('\n');
                added.add(e.getKey());
            }
            if (added.isEmpty()) return added;

            String merged = String.join("\n\n", parts);
            if (tail.length() > 0)
                merged = merged + "\n\n# ===== เพิ่มให้อัตโนมัติตอนอัปเดตปลั๊กอิน =====\n" + tail;

            // Prove it still parses before it replaces anything.
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(merged);

            Files.writeString(live.toPath(), merged, StandardCharsets.UTF_8);
            plugin.getLogger().info("เพิ่มส่วนใหม่ใน " + live.getName() + ": " + String.join(", ", added));
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            plugin.getLogger().warning("รวมค่าใหม่เข้า " + resource + " ไม่สำเร็จ: " + ex.getMessage());
            return List.of();
        }
        return added;
    }

    /** Pulls one nested setting out of a section's text, with the comments above it. */
    private static String childBlock(String sectionText, String child) {
        if (sectionText == null) return null;
        String[] lines = sectionText.split("\n", -1);

        int indent = -1, key = -1;
        for (int i = 0; i < lines.length && key < 0; i++) {
            String l = lines[i];
            if (l.isBlank()) continue;
            int lead = l.length() - l.stripLeading().length();
            if (lead == 0) continue;                       // the section's own line
            String t = l.strip();
            if (t.startsWith("#")) continue;
            if (indent < 0) indent = lead;
            if (lead != indent) continue;
            String name = t.contains(":") ? t.substring(0, t.indexOf(':')).trim() : t;
            if (name.equals(child)) key = i;
        }
        if (key < 0) return null;

        // Take the comment lines written directly above it, at the same depth.
        int start = key;
        while (start > 0) {
            String prev = lines[start - 1];
            if (prev.isBlank()) break;
            int lead = prev.length() - prev.stripLeading().length();
            if (!prev.strip().startsWith("#") || lead != indent) break;
            start--;
        }

        // It ends where the next thing at this depth begins — a key or the
        // comment introducing one.
        int end = lines.length;
        for (int j = key + 1; j < lines.length; j++) {
            String l = lines[j];
            if (l.isBlank()) continue;
            int lead = l.length() - l.stripLeading().length();
            if (lead <= indent) { end = j; break; }
        }
        // Give back any trailing comment run: it belongs to whatever comes next.
        while (end - 1 > key && lines[end - 1].strip().startsWith("#")) end--;

        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)).stripTrailing();
    }

    /**
     * Cuts the shipped file into one chunk per top-level key, each carrying the
     * comment lines written above it.
     */
    private static Map<String, String> split(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        String key = null;
        StringBuilder body = new StringBuilder();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                boolean topLevel = !line.isEmpty()
                        && !Character.isWhitespace(line.charAt(0))
                        && line.contains(":")
                        && !line.startsWith("#");
                if (topLevel) {
                    if (key != null) out.put(key, body.toString());
                    key = line.substring(0, line.indexOf(':')).trim();
                    body = new StringBuilder();
                    for (String c : pending) body.append(c).append('\n');
                    pending.clear();
                    body.append(line).append('\n');
                } else if (key == null) {
                    // Header comments and blank lines belong to whatever comes next.
                    if (line.startsWith("#")) pending.add(line);
                    else pending.clear();
                } else if (line.startsWith("#") && line.length() > 1 && !line.startsWith("# ")) {
                    body.append(line).append('\n');
                } else if (line.isBlank()) {
                    body.append('\n');
                } else if (line.startsWith("#")) {
                    // A comment at column 0 may introduce the next section rather
                    // than close this one; hold it until we know.
                    pending.add(line);
                } else {
                    for (String c : pending) body.append(c).append('\n');
                    pending.clear();
                    body.append(line).append('\n');
                }
            }
            if (key != null) out.put(key, body.toString());
        } catch (IOException ignored) { }
        return out;
    }
}
