package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Giant block letters, written by the server instead of stacked by hand.
 *
 * <p>Every big server has its name spelled out over the spawn, and every one
 * of them was placed a block at a time. This writes it in one command, and
 * takes it back out again with another, so the sign over spawn stops being a
 * weekend of work and becomes something you can change your mind about.
 *
 * <p>Latin letters, digits and a few marks only: Thai stacks vowels above and
 * below the line, which a five-by-seven grid cannot hold.
 */
public class BigText implements CommandExecutor, TabCompleter {

    private static final Map<Character, String[]> FONT = new HashMap<>();
    private static final int W = 5, H = 7;

    static {
        FONT.put('!', new String[]{"00100", "00100", "00100", "00100", "00100", "00000", "00100"});
        FONT.put('\'', new String[]{"00100", "00100", "00000", "00000", "00000", "00000", "00000"});
        FONT.put('+', new String[]{"00000", "00100", "00100", "11111", "00100", "00100", "00000"});
        FONT.put('-', new String[]{"00000", "00000", "00000", "11111", "00000", "00000", "00000"});
        FONT.put('.', new String[]{"00000", "00000", "00000", "00000", "00000", "00000", "00100"});
        FONT.put('0', new String[]{"01110", "10001", "10011", "10101", "11001", "10001", "01110"});
        FONT.put('1', new String[]{"00100", "01100", "00100", "00100", "00100", "00100", "01110"});
        FONT.put('2', new String[]{"01110", "10001", "00001", "00010", "00100", "01000", "11111"});
        FONT.put('3', new String[]{"11110", "00001", "00001", "01110", "00001", "00001", "11110"});
        FONT.put('4', new String[]{"00010", "00110", "01010", "10010", "11111", "00010", "00010"});
        FONT.put('5', new String[]{"11111", "10000", "11110", "00001", "00001", "10001", "01110"});
        FONT.put('6', new String[]{"00110", "01000", "10000", "11110", "10001", "10001", "01110"});
        FONT.put('7', new String[]{"11111", "00001", "00010", "00100", "01000", "01000", "01000"});
        FONT.put('8', new String[]{"01110", "10001", "10001", "01110", "10001", "10001", "01110"});
        FONT.put('9', new String[]{"01110", "10001", "10001", "01111", "00001", "00010", "01100"});
        FONT.put('?', new String[]{"01110", "10001", "00001", "00010", "00100", "00000", "00100"});
        FONT.put('A', new String[]{"01110", "10001", "10001", "11111", "10001", "10001", "10001"});
        FONT.put('B', new String[]{"11110", "10001", "10001", "11110", "10001", "10001", "11110"});
        FONT.put('C', new String[]{"01111", "10000", "10000", "10000", "10000", "10000", "01111"});
        FONT.put('D', new String[]{"11110", "10001", "10001", "10001", "10001", "10001", "11110"});
        FONT.put('E', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "11111"});
        FONT.put('F', new String[]{"11111", "10000", "10000", "11110", "10000", "10000", "10000"});
        FONT.put('G', new String[]{"01111", "10000", "10000", "10111", "10001", "10001", "01111"});
        FONT.put('H', new String[]{"10001", "10001", "10001", "11111", "10001", "10001", "10001"});
        FONT.put('I', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "11111"});
        FONT.put('J', new String[]{"00111", "00010", "00010", "00010", "00010", "10010", "01100"});
        FONT.put('K', new String[]{"10001", "10010", "10100", "11000", "10100", "10010", "10001"});
        FONT.put('L', new String[]{"10000", "10000", "10000", "10000", "10000", "10000", "11111"});
        FONT.put('M', new String[]{"10001", "11011", "10101", "10101", "10001", "10001", "10001"});
        FONT.put('N', new String[]{"10001", "11001", "10101", "10011", "10001", "10001", "10001"});
        FONT.put('O', new String[]{"01110", "10001", "10001", "10001", "10001", "10001", "01110"});
        FONT.put('P', new String[]{"11110", "10001", "10001", "11110", "10000", "10000", "10000"});
        FONT.put('Q', new String[]{"01110", "10001", "10001", "10001", "10101", "10010", "01101"});
        FONT.put('R', new String[]{"11110", "10001", "10001", "11110", "10100", "10010", "10001"});
        FONT.put('S', new String[]{"01111", "10000", "10000", "01110", "00001", "00001", "11110"});
        FONT.put('T', new String[]{"11111", "00100", "00100", "00100", "00100", "00100", "00100"});
        FONT.put('U', new String[]{"10001", "10001", "10001", "10001", "10001", "10001", "01110"});
        FONT.put('V', new String[]{"10001", "10001", "10001", "10001", "10001", "01010", "00100"});
        FONT.put('W', new String[]{"10001", "10001", "10001", "10101", "10101", "11011", "10001"});
        FONT.put('X', new String[]{"10001", "10001", "01010", "00100", "01010", "10001", "10001"});
        FONT.put('Y', new String[]{"10001", "10001", "01010", "00100", "00100", "00100", "00100"});
        FONT.put('Z', new String[]{"11111", "00001", "00010", "00100", "01000", "10000", "11111"});
    }

    private final BannarakZones plugin;

    /** The last thing each person built, so they can take it back. */
    private final Map<UUID, Map<Location, BlockData>> undo = new HashMap<>();

    public BigText(BannarakZones plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("ใช้ในเกมเท่านั้น"); return true; }
        if (!p.hasPermission("zones.admin.bigtext")) {
            Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น");
            return true;
        }
        if (a.length == 0) {
            Notify.chat(p, "<gray>ใช้ <white>/bigtext <ข้อความ> <gray>· <white>/bigtext undo");
            Notify.chat(p, "<gray>ตั้งบล็อก <white>/bigtext mat <ชื่อบล็อก> <gray>· ขนาด <white>/bigtext scale <1-4>");
            Notify.chat(p, "<gray>ใช้ได้ A-Z 0-9 และ ! ? . - + เท่านั้น ภาษาไทยก่อไม่ได้เพราะสระอยู่คนละชั้น");
            return true;
        }

        String first = a[0].toLowerCase(Locale.ROOT);
        if (first.equals("undo")) { undo(p); return true; }
        if (first.equals("mat") || first.equals("material")) {
            if (a.length < 2) { Notify.chat(p, "<gray>เช่น <white>/bigtext mat GOLD_BLOCK"); return true; }
            Material m = Material.matchMaterial(a[1]);
            if (m == null || !m.isBlock()) { Notify.chat(p, "<red>ไม่รู้จักบล็อก <white>" + a[1]); return true; }
            material.put(p.getUniqueId(), m);
            Notify.bar(p, "<green>ใช้บล็อก <white>" + m.name(), null, 1.2f);
            return true;
        }
        if (first.equals("scale")) {
            int n;
            try { n = Integer.parseInt(a[1]); } catch (Exception e) { n = 1; }
            n = Math.max(1, Math.min(4, n));
            scale.put(p.getUniqueId(), n);
            Notify.bar(p, "<green>ขนาด <white>" + n + "x", null, 1.2f);
            return true;
        }

        build(p, String.join(" ", a));
        return true;
    }

    private final Map<UUID, Material> material = new HashMap<>();
    private final Map<UUID, Integer> scale = new HashMap<>();

    private void build(Player p, String text) {
        Material mat = material.getOrDefault(p.getUniqueId(), Material.GOLD_BLOCK);
        int s = scale.getOrDefault(p.getUniqueId(), 1);

        List<String> rows = rasterise(text);
        if (rows.isEmpty()) { Notify.chat(p, "<red>ไม่มีตัวอักษรที่ก่อได้ในข้อความนี้"); return; }

        int wide = rows.get(0).length() * s;
        int tall = rows.size() * s;
        int total = 0;
        for (String r : rows) for (char c : r.toCharArray()) if (c == '1') total++;
        total *= s * s;

        int cap = plugin.getConfig().getInt("bigtext.max-blocks", 20000);
        if (total > cap) {
            Notify.chat(p, "<red>ข้อความนี้ใช้ " + total + " บล็อก เกินที่ตั้งไว้ (" + cap + ")");
            Notify.chat(p, "<gray>ลดขนาดด้วย <white>/bigtext scale 1 <gray>หรือพิมพ์สั้นลง");
            return;
        }

        // Stand it up facing whoever asked for it, a few blocks out so they can
        // see the whole thing without walking backwards.
        float yaw = p.getLocation().getYaw();
        int[] right, out;
        switch (facing(yaw)) {
            case 0 -> { right = new int[]{-1, 0, 0}; out = new int[]{0, 0, 1}; }   // looking south
            case 1 -> { right = new int[]{0, 0, -1}; out = new int[]{-1, 0, 0}; }  // west
            case 2 -> { right = new int[]{1, 0, 0};  out = new int[]{0, 0, -1}; }  // north
            default -> { right = new int[]{0, 0, 1}; out = new int[]{1, 0, 0}; }   // east
        }

        Location base = p.getLocation().getBlock().getLocation()
                .add(out[0] * 8, 0, out[2] * 8)
                .subtract(right[0] * (wide / 2.0), 0, right[2] * (wide / 2.0));

        Map<Location, BlockData> before = new LinkedHashMap<>();
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) != '1') continue;
                for (int dy = 0; dy < s; dy++) {
                    for (int dx = 0; dx < s; dx++) {
                        int x = col * s + dx;
                        int y = (tall - 1) - (row * s + dy);
                        Block b = base.clone().add(right[0] * x, y, right[2] * x).getBlock();
                        before.put(b.getLocation(), b.getBlockData());
                        b.setType(mat, false);
                    }
                }
            }
        }
        undo.put(p.getUniqueId(), before);
        Notify.popup(p, "<gold>ก่อเสร็จแล้ว", "<white>" + before.size() + " บล็อก · พิมพ์ /bigtext undo เพื่อลบ",
                "block.note_block.pling", 1.4f);
    }

    private void undo(Player p) {
        Map<Location, BlockData> before = undo.remove(p.getUniqueId());
        if (before == null || before.isEmpty()) {
            Notify.chat(p, "<gray>ไม่มีอะไรให้ย้อน");
            return;
        }
        for (Map.Entry<Location, BlockData> e : before.entrySet()) {
            Block b = e.getKey().getBlock();
            b.setBlockData(e.getValue(), false);
        }
        Notify.bar(p, "<green>ย้อนคืน " + before.size() + " บล็อกแล้ว", "block.note_block.bass", 1f);
    }

    /** 0 south, 1 west, 2 north, 3 east. */
    private int facing(float yaw) {
        int q = (int) Math.floor(((yaw % 360 + 360) % 360 + 45) / 90) % 4;
        return q;
    }

    /** Turns the text into rows of 0 and 1, one row per pixel line. */
    private List<String> rasterise(String text) {
        StringBuilder[] rows = new StringBuilder[H];
        for (int i = 0; i < H; i++) rows[i] = new StringBuilder();
        boolean any = false;
        for (char raw : text.toUpperCase(Locale.ROOT).toCharArray()) {
            String[] glyph = FONT.get(raw);
            if (glyph == null) {
                // Anything we cannot draw becomes a gap, so a stray character
                // spaces the word out instead of stopping the whole build.
                for (int i = 0; i < H; i++) rows[i].append("000");
                continue;
            }
            any = true;
            for (int i = 0; i < H; i++) rows[i].append(glyph[i]).append('0');
        }
        if (!any) return List.of();
        List<String> out = new ArrayList<>();
        for (StringBuilder r : rows) out.add(r.toString());
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (a.length == 1) return List.of("undo", "mat", "scale");
        if (a.length == 2 && a[0].equalsIgnoreCase("mat"))
            return List.of("GOLD_BLOCK", "DIAMOND_BLOCK", "REDSTONE_BLOCK", "LAPIS_BLOCK",
                    "EMERALD_BLOCK", "NETHERITE_BLOCK", "WHITE_CONCRETE", "ORANGE_CONCRETE",
                    "LIME_CONCRETE", "MAGENTA_CONCRETE", "GLOWSTONE", "SEA_LANTERN");
        if (a.length == 2 && a[0].equalsIgnoreCase("scale")) return List.of("1", "2", "3", "4");
        return Collections.emptyList();
    }
}
