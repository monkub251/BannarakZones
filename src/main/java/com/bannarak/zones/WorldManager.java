package com.bannarak.zones;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates the extra worlds the server needs, keeps their borders, and
 * drops players somewhere random when they ask for it.
 */
public class WorldManager implements CommandExecutor, TabCompleter {

    /** Blocks nobody should land on top of. */
    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.FIRE, Material.CACTUS,
            Material.MAGMA_BLOCK, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.VOID_AIR);

    private final BannarakZones plugin;
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    public WorldManager(BannarakZones plugin) {
        this.plugin = plugin;
    }

    // ---------------- world creation ----------------

    /** Called from onEnable, before anything reads the spawn. */
    public void prepareWorlds() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("worlds.create");
        if (section == null) return;

        for (String name : section.getKeys(false)) {
            if (Bukkit.getWorld(name) != null) continue;

            String kind = section.getString(name + ".type", "void").toLowerCase(Locale.ROOT);
            WorldCreator creator = new WorldCreator(name);
            switch (kind) {
                case "void" -> creator.generator(new VoidGenerator()).type(WorldType.FLAT);
                case "flat" -> creator.type(WorldType.FLAT);
                case "nether" -> creator.environment(World.Environment.NETHER);
                case "end" -> creator.environment(World.Environment.THE_END);
                default -> creator.type(WorldType.NORMAL);
            }
            String seed = section.getString(name + ".seed", "");
            if (!seed.isBlank()) {
                try { creator.seed(Long.parseLong(seed)); }
                catch (NumberFormatException ex) { creator.seed(seed.hashCode()); }
            }

            plugin.getLogger().info("กำลังสร้างโลก " + name + " (" + kind + ") …");
            World world = creator.createWorld();
            if (world == null) {
                plugin.getLogger().warning("สร้างโลก " + name + " ไม่สำเร็จ");
                continue;
            }

            for (String rule : section.getStringList(name + ".gamerules")) {
                String[] parts = rule.split("=", 2);
                if (parts.length != 2) continue;
                applyNamedRule(world, name, parts[0].trim(), parts[1].trim());
            }

            // a small pad so whoever arrives first does not fall through the sky
            if (kind.equals("void") && section.getBoolean(name + ".starter-platform", true)) {
                int cx = section.getInt(name + ".platform-x", 0);
                int cy = section.getInt(name + ".platform-y", 99);
                int cz = section.getInt(name + ".platform-z", 0);
                Material mat = Material.matchMaterial(
                        section.getString(name + ".platform-block", "SMOOTH_STONE"));
                if (mat == null) mat = Material.SMOOTH_STONE;
                for (int x = cx - 4; x <= cx + 4; x++)
                    for (int z = cz - 4; z <= cz + 4; z++)
                        world.getBlockAt(x, cy, z).setType(mat, false);
                world.setSpawnLocation(cx, cy + 1, cz);
                plugin.getLogger().info("วางแท่นชั่วคราวที่ " + name + " " + cx + "," + cy + "," + cz);
            }
        }
    }

    /**
     * Minecraft 26.x renamed most game rules: doDaylightCycle became ADVANCE_TIME,
     * doMobSpawning became SPAWN_MOBS, and so on. Configs written before that still
     * use the old spellings, so both are accepted here.
     *
     * The lookup walks the GameRules class with reflection instead of naming its
     * fields directly, so a server on an older Paper - where that class does not
     * exist yet - still works through the legacy path.
     */
    private static final Map<String, String> RENAMED = Map.of(
            "domobspawning",    "SPAWN_MOBS",
            "dodaylightcycle",  "ADVANCE_TIME",
            "doweathercycle",   "ADVANCE_WEATHER",
            "announceadvancements", "SHOW_ADVANCEMENT_MESSAGES",
            "showdeathmessages",    "SHOW_DEATH_MESSAGES",
            "domobloot",        "MOB_DROPS",
            "dotiledrops",      "BLOCK_DROPS",
            "doentitydrops",    "ENTITY_DROPS",
            "naturalregeneration",  "NATURAL_HEALTH_REGENERATION");

    private GameRule<?> resolveRule(String wanted) {
        // 1. the name exactly as the server knows it today
        GameRule<?> direct = fromGameRulesClass(wanted);
        if (direct != null) return direct;

        // 2. the old spelling, if this server still answers to it
        try {
            @SuppressWarnings("deprecation")
            GameRule<?> legacy = GameRule.getByName(wanted);
            if (legacy != null) return legacy;
        } catch (Throwable ignored) { }

        // 3. the old spelling, translated to the new constant
        String mapped = RENAMED.get(wanted.toLowerCase(Locale.ROOT));
        return mapped == null ? null : fromGameRulesClass(mapped);
    }

    /** Reads a constant off org.bukkit.GameRules by name, matching loosely. */
    private GameRule<?> fromGameRulesClass(String wanted) {
        try {
            Class<?> rules = Class.forName("org.bukkit.GameRules");
            String flat = wanted.replace("_", "").toLowerCase(Locale.ROOT);
            for (java.lang.reflect.Field f : rules.getFields()) {
                if (!GameRule.class.isAssignableFrom(f.getType())) continue;
                if (f.getName().replace("_", "").toLowerCase(Locale.ROOT).equals(flat))
                    return (GameRule<?>) f.get(null);
            }
        } catch (ClassNotFoundException ignored) {
            // older Paper: the legacy lookup above is the only route
        } catch (Throwable t) {
            plugin.getLogger().warning("อ่านรายชื่อ gamerule ไม่ได้: " + t.getMessage());
        }
        return null;
    }

    private void applyNamedRule(World world, String worldName, String ruleName, String value) {
        // doFireTick was removed outright; the nearest modern control is the radius
        // in which fire spreads around a player, so "false" becomes a radius of 0.
        if (ruleName.equalsIgnoreCase("doFireTick")) {
            GameRule<?> radius = fromGameRulesClass("FIRE_SPREAD_RADIUS_AROUND_PLAYER");
            if (radius != null && !Boolean.parseBoolean(value)) {
                applyRule(world, radius, "0");
                plugin.getLogger().info("doFireTick ไม่มีแล้วใน 26.x - ใช้ FIRE_SPREAD_RADIUS_AROUND_PLAYER=0 แทน ในโลก " + worldName);
                return;
            }
            if (radius != null) return;   // true is the default, nothing to do
        }

        GameRule<?> gr = resolveRule(ruleName);
        if (gr == null) {
            plugin.getLogger().warning("ไม่รู้จัก gamerule " + ruleName + " ในโลก " + worldName);
            return;
        }
        applyRule(world, gr, value);
    }

    @SuppressWarnings("unchecked")
    private void applyRule(World world, GameRule<?> rule, String value) {
        if (rule.getType() == Boolean.class) {
            world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
        } else if (rule.getType() == Integer.class) {
            try { world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value)); }
            catch (NumberFormatException ex) {
                plugin.getLogger().warning("ค่า gamerule " + rule.getName() + " ต้องเป็นตัวเลข");
            }
        }
    }

    /** Pushes the configured border onto every world that has one set. */
    public void applyBorders() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("worlds.border");
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            World world = Bukkit.getWorld(name);
            if (world == null) continue;
            int radius = section.getInt(name + ".radius", 0);
            if (radius <= 0) continue;
            WorldBorder border = world.getWorldBorder();
            border.setCenter(section.getDouble(name + ".x", 0), section.getDouble(name + ".z", 0));
            border.setSize(radius * 2.0);
            border.setWarningDistance(16);
            border.setDamageAmount(0.2);
            plugin.getLogger().info("ขอบโลก " + name + " = รัศมี " + radius + " บล็อก");
        }
    }

    // ---------------- random teleport ----------------

    private World rtpWorld() {
        String name = plugin.getConfig().getString("worlds.rtp.world", "world");
        World world = Bukkit.getWorld(name);
        return world != null ? world : Bukkit.getWorlds().get(0);
    }

    private boolean safeAt(Block ground) {
        if (UNSAFE.contains(ground.getType()) || !ground.getType().isSolid()) return false;
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        return feet.getType().isAir() && head.getType().isAir()
                && !UNSAFE.contains(feet.getType());
    }

    /** Looks for a landing spot one chunk at a time so nothing stalls the server. */
    private void findSpot(Player p, World world, int min, int max, int tries) {
        if (!p.isOnline()) { searching.remove(p.getUniqueId()); return; }
        if (tries <= 0) {
            searching.remove(p.getUniqueId());
            Notify.chat(p, "<red>หาที่ลงไม่ได้ ลองอีกครั้งนะ");
            return;
        }

        var rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble() * Math.PI * 2;
        int distance = rng.nextInt(min, Math.max(min + 1, max));
        int x = (int) (Math.cos(angle) * distance);
        int z = (int) (Math.sin(angle) * distance);

        WorldBorder border = world.getWorldBorder();
        if (!border.isInside(new Location(world, x, 64, z))) {
            findSpot(p, world, min, max, tries - 1);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Block ground = world.getHighestBlockAt(x, z);
            if (!safeAt(ground)) {
                findSpot(p, world, min, max, tries - 1);
                return;
            }
            Location dest = ground.getLocation().add(0.5, 1.0, 0.5);
            dest.setYaw(p.getLocation().getYaw());
            dest.setPitch(0);
            p.teleportAsync(dest).thenAccept(done -> {
                searching.remove(p.getUniqueId());
                if (!done) { Notify.chat(p, "<red>ย้ายตัวไม่สำเร็จ"); return; }
                rtpCooldown.put(p.getUniqueId(), System.currentTimeMillis());
                Notify.popup(p, "<gold>ออกสำรวจ",
                        "<gray>" + dest.getBlockX() + ", " + dest.getBlockZ(),
                        "entity.enderman.teleport", 1.1f);
                world.spawnParticle(Particle.END_ROD, dest, 40, 0.4, 0.8, 0.4, 0.02);
            });
        });
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("world")) return worldCommand(sender, args);

        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }

        if (searching.contains(p.getUniqueId())) {
            Notify.chat(p, "<yellow>กำลังหาที่ลงอยู่ รอแป๊บ");
            return true;
        }

        long wait = plugin.getConfig().getLong("worlds.rtp.cooldown-seconds", 300) * 1000L;
        Long last = rtpCooldown.get(p.getUniqueId());
        if (last != null && !p.hasPermission("zones.admin")) {
            long left = wait - (System.currentTimeMillis() - last);
            if (left > 0) {
                Notify.chat(p, "<red>รออีก <yellow>" + (left / 1000 + 1) + " <red>วินาที");
                return true;
            }
        }

        double price = plugin.getConfig().getDouble("worlds.rtp.price", 0);
        if (price > 0 && !p.hasPermission("zones.admin")) {
            if (plugin.money().get(p.getUniqueId()) < price) {
                Notify.chat(p, "<red>ต้องมี <yellow>" + Math.round(price) + " <red>เหรียญ");
                return true;
            }
            plugin.money().add(p.getUniqueId(), -price);
        }

        World world = rtpWorld();
        int min = plugin.getConfig().getInt("worlds.rtp.min-distance", 300);
        int max = plugin.getConfig().getInt("worlds.rtp.max-distance", 4000);
        searching.add(p.getUniqueId());
        Notify.bar(p, "<gray>กำลังหาที่ลง…", "block.note_block.hat", 1.2f);
        findSpot(p, world, min, max, plugin.getConfig().getInt("worlds.rtp.attempts", 40));
        return true;
    }

    private boolean worldCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zones.admin.world")) {
            sender.sendMessage("§cคำสั่งนี้สำหรับทีมงานเท่านั้น"); return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7โลกทั้งหมด:");
            for (World w : Bukkit.getWorlds())
                sender.sendMessage("§8- §f" + w.getName() + " §7(" + w.getPlayers().size()
                        + " คน, " + w.getEnvironment().name().toLowerCase(Locale.ROOT) + ")");
            sender.sendMessage("§7ใช้ §f/world <ชื่อ> §7เพื่อย้ายไป · §f/world border <ชื่อ> <รัศมี>");
            return true;
        }

        if (args[0].equalsIgnoreCase("border")) {
            if (args.length < 3) { sender.sendMessage("§7/world border <ชื่อ> <รัศมี>"); return true; }
            World world = Bukkit.getWorld(args[1]);
            if (world == null) { sender.sendMessage("§cไม่เจอโลกนี้"); return true; }
            int radius;
            try { radius = Integer.parseInt(args[2]); }
            catch (NumberFormatException ex) { sender.sendMessage("§cรัศมีต้องเป็นตัวเลข"); return true; }
            plugin.getConfig().set("worlds.border." + args[1] + ".radius", radius);
            plugin.saveConfig();
            applyBorders();
            sender.sendMessage("§aตั้งขอบโลก " + args[1] + " เป็นรัศมี " + radius + " แล้ว");
            return true;
        }

        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        World world = Bukkit.getWorld(args[0]);
        if (world == null) { sender.sendMessage("§cไม่เจอโลกนี้"); return true; }
        p.teleportAsync(world.getSpawnLocation().add(0.5, 0, 0.5));
        Notify.chat(p, "<green>ย้ายไปโลก <yellow>" + world.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("world")) return List.of();
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("border"));
            for (World w : Bukkit.getWorlds()) out.add(w.getName());
            out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)));
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("border")) {
            List<String> out = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) out.add(w.getName());
            return out;
        }
        return List.of();
    }
}
