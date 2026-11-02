package com.bannarak.zones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Transformation;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Floating text: signs hanging in the air, and the big title letters over a
 * build. Both are TextDisplay entities, which Paper draws as real text rather
 * than a named armour stand, so they stay sharp at any size.
 *
 * Scale is what makes a hologram a "3D title": size 1 is chat-sized, size 6 or
 * 8 reads as a sign over a building. Note this is still text, not letters built
 * out of blocks - a block-built title is a build job, not a plugin one.
 */
public class Holograms implements CommandExecutor, TabCompleter, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    private final NamespacedKey idKey;
    private final Map<String, Holo> defs = new LinkedHashMap<>();
    private final Map<String, UUID> live = new HashMap<>();
    private boolean dirty = false;

    public Holograms(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holograms.yml");
        this.idKey = new NamespacedKey(plugin, "bz_holo");
    }

    public static class Holo {
        public String id;
        public final List<String> lines = new ArrayList<>();
        public Location loc;
        public float size = 1f;
        /** null means no background plate behind the text */
        public String background = null;
        public boolean shadow = true;
        public boolean seeThrough = false;
        public String billboard = "CENTER";
        public float range = 64f;
    }

    public Collection<Holo> all() { return defs.values(); }

    // ---------------- spawning ----------------

    private String tagOf(Entity e) {
        return e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    private void clearStrays() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (tagOf(e) != null) e.remove();
    }

    public void respawnAll() {
        clearStrays();
        live.clear();
        for (Holo h : defs.values()) spawn(h);
    }

    private void spawn(Holo h) {
        if (h.loc == null || h.loc.getWorld() == null || h.lines.isEmpty()) return;
        TextDisplay d;
        try {
            d = h.loc.getWorld().spawn(h.loc, TextDisplay.class);
        } catch (Throwable t) {
            plugin.getLogger().warning("Hologram " + h.id + " could not be spawned: " + t.getMessage());
            return;
        }
        apply(d, h);
        d.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, h.id);
        live.put(h.id, d.getUniqueId());
    }

    /** Pushes every setting onto a live display, so an edit needs no respawn. */
    private void apply(TextDisplay d, Holo h) {
        d.text(MM.deserialize(String.join("\n", h.lines)));
        d.setPersistent(true);
        d.setShadowed(h.shadow);
        d.setSeeThrough(h.seeThrough);
        d.setViewRange(h.range);
        d.setAlignment(TextDisplay.TextAlignment.CENTER);

        try {
            d.setBillboard(org.bukkit.entity.Display.Billboard.valueOf(h.billboard));
        } catch (IllegalArgumentException ex) {
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        }

        if (h.background == null) d.setBackgroundColor(null);
        else {
            Color c = parseColour(h.background);
            if (c != null) d.setBackgroundColor(c);
        }

        // scale is the whole trick behind a big title
        d.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(h.size, h.size, h.size),
                new AxisAngle4f(0, 0, 0, 1)));
    }

    /** #rrggbb or #aarrggbb. Returns null when it is not a colour. */
    private Color parseColour(String raw) {
        String s = raw.startsWith("#") ? raw.substring(1) : raw;
        try {
            if (s.length() == 6) {
                int v = Integer.parseInt(s, 16);
                return Color.fromARGB(0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
            }
            if (s.length() == 8) {
                long v = Long.parseLong(s, 16);
                return Color.fromARGB((int) ((v >> 24) & 0xFF), (int) ((v >> 16) & 0xFF),
                        (int) ((v >> 8) & 0xFF), (int) (v & 0xFF));
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    /** Edits in place when the display is loaded, otherwise just remembers it. */
    private void refresh(Holo h) {
        UUID u = live.get(h.id);
        Entity e = u == null ? null : Bukkit.getEntity(u);
        if (e instanceof TextDisplay d) apply(d, h);
        else spawn(h);
    }

    /** Timer: brings back anything that got unloaded or removed. */
    public void heal() {
        for (Holo h : defs.values()) {
            if (h.loc == null || h.loc.getWorld() == null) continue;
            UUID u = live.get(h.id);
            Entity e = u == null ? null : Bukkit.getEntity(u);
            if (e == null || e.isDead() || !e.isValid()) spawn(h);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (Entity ent : e.getChunk().getEntities()) {
            String id = tagOf(ent);
            if (id == null) continue;
            UUID keep = live.get(id);
            if (keep == null || !keep.equals(ent.getUniqueId())) ent.remove();
        }
    }

    public void shutdown() { clearStrays(); save(true); }

    // ---------------- storage ----------------

    public void load() {
        defs.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Holo h = new Holo();
            h.id = id.toLowerCase(Locale.ROOT);
            h.lines.addAll(s.getStringList("lines"));
            h.size = (float) s.getDouble("size", 1);
            h.background = s.getString("background", null);
            h.shadow = s.getBoolean("shadow", true);
            h.seeThrough = s.getBoolean("see-through", false);
            h.billboard = s.getString("billboard", "CENTER");
            h.range = (float) s.getDouble("range", 64);
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) {
                plugin.getLogger().warning("Hologram " + id + ": world not loaded, skipped");
                continue;
            }
            h.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw", 0), 0);
            defs.put(h.id, h);
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Holo h : defs.values()) {
            String p = "holograms." + h.id;
            cfg.set(p + ".lines", h.lines);
            cfg.set(p + ".size", h.size);
            cfg.set(p + ".background", h.background);
            cfg.set(p + ".shadow", h.shadow);
            cfg.set(p + ".see-through", h.seeThrough);
            cfg.set(p + ".billboard", h.billboard);
            cfg.set(p + ".range", h.range);
            if (h.loc != null && h.loc.getWorld() != null) {
                cfg.set(p + ".world", h.loc.getWorld().getName());
                cfg.set(p + ".x", h.loc.getX());
                cfg.set(p + ".y", h.loc.getY());
                cfg.set(p + ".z", h.loc.getZ());
                cfg.set(p + ".yaw", h.loc.getYaw());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save holograms.yml", ex);
        }
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("zones.admin.holo")) {
            Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "create", "add", "สร้าง" -> create(p, args);
            case "title", "ใหญ่" -> title(p, args);
            case "addline", "เพิ่มบรรทัด" -> addLine(p, args);
            case "setline", "แก้บรรทัด" -> setLine(p, args);
            case "delline", "ลบบรรทัด" -> delLine(p, args);
            case "size", "ขนาด" -> size(p, args);
            case "bg", "background", "พื้นหลัง" -> background(p, args);
            case "billboard", "หัน" -> billboard(p, args);
            case "move", "ย้าย" -> move(p, args);
            case "tp", "ไป" -> tp(p, args);
            case "list", "ดู" -> list(p);
            case "delete", "remove", "del", "ลบ" -> delete(p, args);
            case "reload", "โหลด" -> { load(); respawnAll();
                Notify.chat(p, "<green>โหลดป้ายใหม่แล้ว <gray>(" + defs.size() + " ป้าย)"); }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        Notify.chat(p, "<gold>คำสั่งป้ายลอย");
        Notify.chat(p, "<yellow>/holo create <ชื่อย่อ> <ข้อความ> <gray>- ป้ายธรรมดา");
        Notify.chat(p, "<yellow>/holo title <ชื่อย่อ> <ข้อความ> <gray>- ตัวใหญ่แบบป้ายหน้าร้าน");
        Notify.chat(p, "<yellow>/holo addline|setline|delline <gray>- แก้บรรทัด");
        Notify.chat(p, "<yellow>/holo size <ชื่อย่อ> <1-20> <gray>- ขนาด");
        Notify.chat(p, "<yellow>/holo bg <ชื่อย่อ> <#รหัสสี|off> <gray>- พื้นหลัง");
        Notify.chat(p, "<yellow>/holo billboard <ชื่อย่อ> <CENTER|VERTICAL|FIXED>");
        Notify.chat(p, "<yellow>/holo move|tp|list|delete|reload");
        Notify.chat(p, "<dark_gray>ข้อความใช้ MiniMessage ได้ เช่น <gold>ข้อความ");
    }

    /** Places it at eye height so it hangs where you are looking, not at your feet. */
    private Location place(Player p) {
        return p.getLocation().clone().add(0, 1.8, 0);
    }

    private void create(Player p, String[] args) {
        if (args.length < 3) { Notify.chat(p, "<red>/holo create <ชื่อย่อ> <ข้อความ>"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (defs.containsKey(id)) { Notify.chat(p, "<red>มี <yellow>" + id + " <red>อยู่แล้ว"); return; }
        Holo h = new Holo();
        h.id = id;
        h.loc = place(p);
        h.lines.add(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
        defs.put(id, h);
        dirty = true;
        save(true);
        spawn(h);
        Notify.chat(p, "<green>สร้างป้าย <yellow>" + id + " <green>แล้ว");
    }

    private void title(Player p, String[] args) {
        if (args.length < 3) { Notify.chat(p, "<red>/holo title <ชื่อย่อ> <ข้อความ>"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (defs.containsKey(id)) { Notify.chat(p, "<red>มี <yellow>" + id + " <red>อยู่แล้ว"); return; }
        Holo h = new Holo();
        h.id = id;
        h.loc = place(p).add(0, 2, 0);
        h.lines.add(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
        h.size = 6f;
        h.background = null;
        h.shadow = true;
        h.range = 128f;
        defs.put(id, h);
        dirty = true;
        save(true);
        spawn(h);
        Notify.chat(p, "<green>สร้างป้ายตัวใหญ่ <yellow>" + id + " <green>แล้ว <gray>ขนาด 6");
        Notify.chat(p, "<gray>ปรับด้วย <white>/holo size " + id + " <ตัวเลข>");
    }

    private Holo need(Player p, String[] args) {
        if (args.length < 2) { Notify.chat(p, "<red>ต้องบอกชื่อย่อของป้ายด้วย"); return null; }
        Holo h = defs.get(args[1].toLowerCase(Locale.ROOT));
        if (h == null) Notify.chat(p, "<red>ไม่เจอ <yellow>" + args[1]);
        return h;
    }

    private void addLine(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        if (args.length < 3) { Notify.chat(p, "<red>/holo addline <ชื่อย่อ> <ข้อความ>"); return; }
        h.lines.add(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
        dirty = true; save(true); refresh(h);
        Notify.chat(p, "<green>เพิ่มบรรทัดแล้ว <gray>(ตอนนี้ " + h.lines.size() + " บรรทัด)");
    }

    private void setLine(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        if (args.length < 4) { Notify.chat(p, "<red>/holo setline <ชื่อย่อ> <บรรทัดที่> <ข้อความ>"); return; }
        int n;
        try { n = Integer.parseInt(args[2]); }
        catch (NumberFormatException ex) { Notify.chat(p, "<red>บรรทัดต้องเป็นตัวเลข"); return; }
        if (n < 1 || n > h.lines.size()) {
            Notify.chat(p, "<red>ป้ายนี้มี <yellow>" + h.lines.size() + " <red>บรรทัด");
            return;
        }
        h.lines.set(n - 1, String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
        dirty = true; save(true); refresh(h);
        Notify.chat(p, "<green>แก้บรรทัด " + n + " แล้ว");
    }

    private void delLine(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        if (args.length < 3) { Notify.chat(p, "<red>/holo delline <ชื่อย่อ> <บรรทัดที่>"); return; }
        int n;
        try { n = Integer.parseInt(args[2]); }
        catch (NumberFormatException ex) { Notify.chat(p, "<red>บรรทัดต้องเป็นตัวเลข"); return; }
        if (n < 1 || n > h.lines.size()) { Notify.chat(p, "<red>ไม่มีบรรทัดนั้น"); return; }
        if (h.lines.size() == 1) { Notify.chat(p, "<red>เหลือบรรทัดเดียว ลบป้ายไปเลยดีกว่า"); return; }
        h.lines.remove(n - 1);
        dirty = true; save(true); refresh(h);
        Notify.chat(p, "<green>ลบบรรทัด " + n + " แล้ว");
    }

    private void size(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        if (args.length < 3) { Notify.chat(p, "<red>/holo size <ชื่อย่อ> <1-20>"); return; }
        float v;
        try { v = Float.parseFloat(args[2]); }
        catch (NumberFormatException ex) { Notify.chat(p, "<red>ขนาดต้องเป็นตัวเลข"); return; }
        h.size = Math.max(0.2f, Math.min(20f, v));
        dirty = true; save(true); refresh(h);
        Notify.chat(p, "<green>ตั้งขนาดเป็น <yellow>" + h.size);
    }

    private void background(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        if (args.length < 3) { Notify.chat(p, "<red>/holo bg <ชื่อย่อ> <#รหัสสี|off>"); return; }
        if (args[2].equalsIgnoreCase("off") || args[2].equalsIgnoreCase("ปิด")) {
            h.background = null;
            Notify.chat(p, "<green>เอาพื้นหลังออกแล้ว");
        } else {
            if (parseColour(args[2]) == null) {
                Notify.chat(p, "<red>ใส่เป็น <white>#rrggbb <red>หรือ <white>#aarrggbb");
                return;
            }
            h.background = args[2];
            Notify.chat(p, "<green>ตั้งพื้นหลังแล้ว");
        }
        dirty = true; save(true); refresh(h);
    }

    private void billboard(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        if (args.length < 3) {
            Notify.chat(p, "<red>/holo billboard <ชื่อย่อ> <CENTER|VERTICAL|HORIZONTAL|FIXED>");
            Notify.chat(p, "<gray>CENTER หันตามผู้เล่นตลอด · FIXED อยู่นิ่งเหมือนป้ายติดผนัง");
            return;
        }
        String want = args[2].toUpperCase(Locale.ROOT);
        try { org.bukkit.entity.Display.Billboard.valueOf(want); }
        catch (IllegalArgumentException ex) { Notify.chat(p, "<red>ไม่รู้จัก <yellow>" + want); return; }
        h.billboard = want;
        dirty = true; save(true); refresh(h);
        Notify.chat(p, "<green>ตั้งเป็น <yellow>" + want);
    }

    private void move(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        h.loc = place(p);
        dirty = true; save(true);
        UUID u = live.remove(h.id);
        if (u != null) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        spawn(h);
        Notify.chat(p, "<green>ย้าย <yellow>" + h.id + " <green>มาตรงที่คุณยืนแล้ว");
    }

    private void tp(Player p, String[] args) {
        Holo h = need(p, args);
        if (h != null && h.loc != null) p.teleport(h.loc);
    }

    private void list(Player p) {
        if (defs.isEmpty()) { Notify.chat(p, "<gray>ยังไม่มีป้ายเลย <white>/holo create <ชื่อย่อ> <ข้อความ>"); return; }
        Notify.chat(p, "<gold>ป้ายทั้งหมด <gray>(" + defs.size() + ")");
        for (Holo h : defs.values()) {
            String where = h.loc == null || h.loc.getWorld() == null ? "-"
                    : h.loc.getWorld().getName() + " " + (int) h.loc.getX() + ","
                      + (int) h.loc.getY() + "," + (int) h.loc.getZ();
            Notify.chat(p, "<yellow>" + h.id + " <dark_gray>| <gray>ขนาด <white>" + h.size
                    + " <dark_gray>| <gray>" + h.lines.size() + " บรรทัด <dark_gray>| <gray>" + where);
        }
    }

    private void delete(Player p, String[] args) {
        Holo h = need(p, args);
        if (h == null) return;
        defs.remove(h.id);
        UUID u = live.remove(h.id);
        if (u != null) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        dirty = true; save(true);
        Notify.chat(p, "<yellow>ลบป้าย " + h.id + " แล้ว");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p) || !p.hasPermission("zones.admin.holo")) return out;
        if (args.length == 1) {
            for (String s : List.of("create", "title", "addline", "setline", "delline", "size",
                    "bg", "billboard", "move", "tp", "list", "delete", "reload"))
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && !List.of("create", "title", "list", "reload")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            for (String s : defs.keySet())
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("billboard")) {
            for (String s : List.of("CENTER", "VERTICAL", "HORIZONTAL", "FIXED"))
                if (s.startsWith(args[2].toUpperCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("size")) {
            for (String s : List.of("1", "2", "4", "6", "10")) if (s.startsWith(args[2])) out.add(s);
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("bg")
                || args[0].equalsIgnoreCase("background"))) {
            for (String s : List.of("off", "#80000000", "#a0000000")) if (s.startsWith(args[2])) out.add(s);
        }
        return out;
    }
}
