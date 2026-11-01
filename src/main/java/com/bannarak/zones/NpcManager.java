package com.bannarak.zones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Villagers (or any mob) standing at spawn that a player right-clicks to open a menu.
 * Nothing is typed - which is what phone players need.
 *
 * Everything an NPC does is just a command run as that player, so any command the
 * server has works here without touching this class.
 */
public class NpcManager implements CommandExecutor, TabCompleter, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    private final NamespacedKey idKey;
    private final Map<String, NpcDef> defs = new LinkedHashMap<>();
    /** npc id -> the entity currently standing there */
    private final Map<String, UUID> live = new HashMap<>();
    /** player -> last click, so a double tap does not open the menu twice */
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private boolean dirty = false;

    public NpcManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
        this.idKey = new NamespacedKey(plugin, "bz_npc");
    }

    public static class NpcDef {
        public String id;
        public EntityType type = EntityType.VILLAGER;
        public String name = "<yellow>ทีมงาน";
        public final List<String> lines = new ArrayList<>();
        public final List<String> commands = new ArrayList<>();
        public Location loc;
        public boolean lookAtPlayer = true;
    }

    // ---------------- lifecycle ----------------

    public void load() {
        defs.clear();
        if (!file.exists()) { plugin.saveResource("npcs.yml", false); }
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("npcs");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            NpcDef d = new NpcDef();
            d.id = id.toLowerCase(Locale.ROOT);
            try {
                d.type = EntityType.valueOf(s.getString("type", "VILLAGER").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("NPC " + id + ": unknown entity type, using VILLAGER");
                d.type = EntityType.VILLAGER;
            }
            d.name = s.getString("name", "<yellow>ทีมงาน");
            d.lines.addAll(s.getStringList("lines"));
            d.commands.addAll(s.getStringList("commands"));
            d.lookAtPlayer = s.getBoolean("look-at-player", true);
            String world = s.getString("world", "");
            World w = Bukkit.getWorld(world);
            if (w == null) {
                plugin.getLogger().warning("NPC " + id + ": world '" + world + "' is not loaded, skipped");
                continue;
            }
            d.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
            defs.put(d.id, d);
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (NpcDef d : defs.values()) {
            String p = "npcs." + d.id;
            cfg.set(p + ".type", d.type.name());
            cfg.set(p + ".name", d.name);
            cfg.set(p + ".lines", d.lines);
            cfg.set(p + ".commands", d.commands);
            cfg.set(p + ".look-at-player", d.lookAtPlayer);
            if (d.loc != null && d.loc.getWorld() != null) {
                cfg.set(p + ".world", d.loc.getWorld().getName());
                cfg.set(p + ".x", d.loc.getX());
                cfg.set(p + ".y", d.loc.getY());
                cfg.set(p + ".z", d.loc.getZ());
                cfg.set(p + ".yaw", d.loc.getYaw());
                cfg.set(p + ".pitch", d.loc.getPitch());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save npcs.yml", ex);
        }
    }

    /** Clears out every NPC entity we own, then puts them back where the file says. */
    public void respawnAll() {
        clearStrays();
        live.clear();
        for (NpcDef d : defs.values()) spawn(d);
    }

    /** Any entity of ours still in a loaded chunk gets removed - stops duplicates piling up. */
    private void clearStrays() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (tagOf(e) != null) e.remove();
    }

    private String tagOf(Entity e) {
        return e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    private void spawn(NpcDef d) {
        if (d.loc == null || d.loc.getWorld() == null) return;
        Entity e;
        try {
            e = d.loc.getWorld().spawnEntity(d.loc, d.type);
        } catch (Throwable t) {
            plugin.getLogger().warning("NPC " + d.id + " could not be spawned: " + t.getMessage());
            return;
        }
        e.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, d.id);
        e.setPersistent(true);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setGravity(false);
        e.customName(MM.deserialize(d.name));
        e.setCustomNameVisible(true);
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
            try { le.setCanPickupItems(false); } catch (Throwable ignored) { }
        }
        live.put(d.id, e.getUniqueId());
    }

    /** Called on a timer: puts back any NPC that got unloaded or killed off. */
    public void heal() {
        for (NpcDef d : defs.values()) {
            if (d.loc == null || d.loc.getWorld() == null) continue;
            UUID u = live.get(d.id);
            Entity e = u == null ? null : Bukkit.getEntity(u);
            if (e == null || e.isDead() || !e.isValid()) {
                spawn(d);
                continue;
            }
            // drifted (pushed by water, a piston, whatever) - put it back
            if (e.getLocation().distanceSquared(d.loc) > 0.25) e.teleport(d.loc);
        }
    }

    public void shutdown() {
        clearStrays();
        save(true);
    }

    public Collection<NpcDef> all() { return defs.values(); }

    // ---------------- events ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent e) {
        String id = tagOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();

        long now = System.currentTimeMillis();
        Long prev = lastClick.get(p.getUniqueId());
        if (prev != null && now - prev < 400) return;
        lastClick.put(p.getUniqueId(), now);

        NpcDef d = defs.get(id);
        if (d == null) return;
        if (d.commands.isEmpty()) {
            Notify.chat(p, "<gray>ยังไม่ได้ตั้งว่าให้ทำอะไร");
            return;
        }
        for (String raw : d.commands) {
            String cmd = raw.replace("%player%", p.getName());
            if (cmd.startsWith("console:")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.substring(8).trim());
            } else {
                p.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (tagOf(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (tagOf(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        // a chunk coming back can bring a copy of an NPC with it
        for (Entity ent : e.getChunk().getEntities()) {
            String id = tagOf(ent);
            if (id == null) continue;
            UUID keep = live.get(id);
            if (keep == null || !keep.equals(ent.getUniqueId())) ent.remove();
        }
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("zones.admin.npc")) {
            Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "create", "add", "สร้าง" -> create(p, args);
            case "delete", "remove", "del", "ลบ" -> delete(p, args);
            case "list", "ดู" -> list(p);
            case "tp", "goto", "ไป" -> tp(p, args);
            case "move", "here", "ย้าย" -> move(p, args);
            case "name", "ชื่อ" -> rename(p, args);
            case "cmd", "command", "action", "สั่ง" -> setCmd(p, args);
            case "type", "ชนิด" -> setType(p, args);
            case "reload", "โหลด" -> {
                load();
                respawnAll();
                Notify.chat(p, "<green>โหลด NPC ใหม่แล้ว <gray>(" + defs.size() + " ตัว)");
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        Notify.chat(p, "<gold>คำสั่ง NPC");
        Notify.chat(p, "<yellow>/npc create <ชื่อย่อ> [ชนิด] <gray>- สร้างตรงที่ยืน");
        Notify.chat(p, "<yellow>/npc cmd <ชื่อย่อ> <คำสั่ง> <gray>- คลิกแล้วให้รันอะไร");
        Notify.chat(p, "<yellow>/npc name <ชื่อย่อ> <ข้อความ> <gray>- ป้ายเหนือหัว");
        Notify.chat(p, "<yellow>/npc type <ชื่อย่อ> <ชนิด> <gray>- เปลี่ยนตัวละคร");
        Notify.chat(p, "<yellow>/npc move <ชื่อย่อ> <gray>- ย้ายมาตรงที่ยืน");
        Notify.chat(p, "<yellow>/npc tp|list|delete|reload");
    }

    private void create(Player p, String[] args) {
        if (args.length < 2) { Notify.chat(p, "<red>/npc create <ชื่อย่อ> [ชนิด]"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (defs.containsKey(id)) { Notify.chat(p, "<red>มี <yellow>" + id + " <red>อยู่แล้ว"); return; }
        NpcDef d = new NpcDef();
        d.id = id;
        d.loc = p.getLocation();
        d.name = "<yellow>" + id;
        if (args.length > 2) {
            try {
                d.type = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                Notify.chat(p, "<red>ไม่รู้จักชนิด <yellow>" + args[2] + " <gray>ใช้ VILLAGER แทน");
            }
        }
        defs.put(id, d);
        dirty = true;
        save(true);
        spawn(d);
        Notify.chat(p, "<green>สร้าง <yellow>" + id + " <green>แล้ว");
        Notify.chat(p, "<gray>ต่อไป: <white>/npc cmd " + id + " shop <gray>(คลิกแล้วเปิดร้าน)");
    }

    private void delete(Player p, String[] args) {
        if (args.length < 2) { Notify.chat(p, "<red>/npc delete <ชื่อย่อ>"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (defs.remove(id) == null) { Notify.chat(p, "<red>ไม่เจอ <yellow>" + id); return; }
        UUID u = live.remove(id);
        if (u != null) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
        dirty = true;
        save(true);
        Notify.chat(p, "<yellow>ลบ " + id + " แล้ว");
    }

    private void list(Player p) {
        if (defs.isEmpty()) { Notify.chat(p, "<gray>ยังไม่มี NPC เลย <white>/npc create <ชื่อย่อ>"); return; }
        Notify.chat(p, "<gold>NPC ทั้งหมด <gray>(" + defs.size() + ")");
        for (NpcDef d : defs.values()) {
            String where = d.loc == null || d.loc.getWorld() == null ? "-"
                    : d.loc.getWorld().getName() + " " + (int) d.loc.getX() + "," + (int) d.loc.getY() + "," + (int) d.loc.getZ();
            Notify.chat(p, "<yellow>" + d.id + " <gray>" + d.type.name() + " <dark_gray>| <gray>" + where
                    + " <dark_gray>| <white>" + (d.commands.isEmpty() ? "<dark_gray>ยังไม่ตั้งคำสั่ง" : String.join(", ", d.commands)));
        }
    }

    private void tp(Player p, String[] args) {
        NpcDef d = need(p, args);
        if (d == null) return;
        if (d.loc != null) p.teleport(d.loc);
    }

    private void move(Player p, String[] args) {
        NpcDef d = need(p, args);
        if (d == null) return;
        d.loc = p.getLocation();
        dirty = true;
        save(true);
        UUID u = live.get(d.id);
        Entity e = u == null ? null : Bukkit.getEntity(u);
        if (e != null) e.teleport(d.loc);
        else spawn(d);
        Notify.chat(p, "<green>ย้าย <yellow>" + d.id + " <green>มาตรงที่คุณยืนแล้ว");
    }

    private void rename(Player p, String[] args) {
        NpcDef d = need(p, args);
        if (d == null) return;
        if (args.length < 3) { Notify.chat(p, "<red>/npc name <ชื่อย่อ> <ข้อความ>"); return; }
        d.name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        dirty = true;
        save(true);
        UUID u = live.get(d.id);
        Entity e = u == null ? null : Bukkit.getEntity(u);
        if (e != null) { e.customName(MM.deserialize(d.name)); e.setCustomNameVisible(true); }
        Notify.chat(p, "<green>ตั้งป้ายแล้ว");
    }

    private void setCmd(Player p, String[] args) {
        NpcDef d = need(p, args);
        if (d == null) return;
        if (args.length < 3) {
            Notify.chat(p, "<red>/npc cmd <ชื่อย่อ> <คำสั่ง>");
            Notify.chat(p, "<gray>เช่น <white>/npc cmd ร้าน shop");
            Notify.chat(p, "<gray>นำหน้าด้วย <white>console: <gray>ถ้าอยากให้เซิร์ฟเป็นคนสั่ง");
            return;
        }
        d.commands.clear();
        d.commands.add(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
        dirty = true;
        save(true);
        Notify.chat(p, "<green>คลิก <yellow>" + d.id + " <green>แล้วจะรัน <white>" + d.commands.get(0));
    }

    private void setType(Player p, String[] args) {
        NpcDef d = need(p, args);
        if (d == null) return;
        if (args.length < 3) { Notify.chat(p, "<red>/npc type <ชื่อย่อ> <ชนิด>"); return; }
        EntityType t;
        try {
            t = EntityType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            Notify.chat(p, "<red>ไม่รู้จักชนิด <yellow>" + args[2]);
            return;
        }
        d.type = t;
        dirty = true;
        save(true);
        UUID u = live.remove(d.id);
        if (u != null) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        spawn(d);
        Notify.chat(p, "<green>เปลี่ยนเป็น <yellow>" + t.name());
    }

    private NpcDef need(Player p, String[] args) {
        if (args.length < 2) { Notify.chat(p, "<red>ต้องบอกชื่อย่อของ NPC ด้วย"); return null; }
        NpcDef d = defs.get(args[1].toLowerCase(Locale.ROOT));
        if (d == null) Notify.chat(p, "<red>ไม่เจอ <yellow>" + args[1]);
        return d;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p) || !p.hasPermission("zones.admin.npc")) return out;
        if (args.length == 1) {
            for (String s : List.of("create", "delete", "list", "tp", "move", "name", "cmd", "type", "reload"))
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            for (String s : defs.keySet())
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("type") || args[0].equalsIgnoreCase("create"))) {
            String pre = args[2].toUpperCase(Locale.ROOT);
            for (String s : List.of("VILLAGER", "WANDERING_TRADER", "ARMOR_STAND", "SNOW_GOLEM", "IRON_GOLEM", "ALLAY"))
                if (s.startsWith(pre)) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("cmd")) {
            for (String s : List.of("shop", "crate", "daily", "mission", "market", "menu", "dungeon", "warp", "rank"))
                if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
