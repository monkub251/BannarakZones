package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Counts what each player digs up and flags the ones whose numbers do not add up.
 *
 * This does NOT see anyone's screen - no server plugin can. What it does is
 * compare how much rock a player moves against how much ore they come out with,
 * and how often an ore they broke was sealed in on every side beforehand.
 * Both numbers are things an x-ray user cannot hide.
 */
public class MiningWatch implements CommandExecutor, TabCompleter, Listener {

    private final BannarakZones plugin;
    private final File file;
    private final Map<UUID, Stats> stats = new HashMap<>();
    /** blocks a player put down this session, so nobody farms their own placed ore */
    private final LinkedHashSet<String> placed = new LinkedHashSet<>();
    private boolean dirty = false;

    private static final Set<Material> STONE = EnumSet.noneOf(Material.class);
    private static final Set<Material> ORES = EnumSet.noneOf(Material.class);

    static {
        for (String n : new String[]{"STONE", "DEEPSLATE", "COBBLESTONE", "COBBLED_DEEPSLATE",
                "DIORITE", "ANDESITE", "GRANITE", "TUFF", "NETHERRACK", "BASALT", "BLACKSTONE",
                "DIRT", "GRAVEL", "CALCITE", "SMOOTH_BASALT", "END_STONE"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) STONE.add(m);
        }
        for (String n : new String[]{"DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS",
                "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "GOLD_ORE", "DEEPSLATE_GOLD_ORE",
                "NETHER_GOLD_ORE", "IRON_ORE", "DEEPSLATE_IRON_ORE", "LAPIS_ORE",
                "DEEPSLATE_LAPIS_ORE", "REDSTONE_ORE", "DEEPSLATE_REDSTONE_ORE",
                "COPPER_ORE", "DEEPSLATE_COPPER_ORE", "COAL_ORE", "DEEPSLATE_COAL_ORE",
                "NETHER_QUARTZ_ORE"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) ORES.add(m);
        }
    }

    public MiningWatch(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mining.yml");
    }

    public static class Stats {
        public long stone = 0;
        /** material name -> how many */
        public final Map<String, Long> ores = new HashMap<>();
        /** ores that had no exposed face until the player broke into them */
        public final Map<String, Long> blind = new HashMap<>();
        public long lastSeen = 0;

        public long ore(String m) { return ores.getOrDefault(m, 0L); }
        public long blindOf(String m) { return blind.getOrDefault(m, 0L); }
    }

    public Stats of(UUID id) { return stats.computeIfAbsent(id, k -> new Stats()); }
    public Map<UUID, Stats> all() { return stats; }

    // ---------------- the two signals ----------------

    /**
     * How many of this block's six faces touched air (or any see-through block)
     * BEFORE the player broke it. The face the player dug in from is already
     * counted, so a value of 1 means the ore was sealed until that very swing.
     */
    private int exposedFaces(Block b) {
        int n = 0;
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block rel = b.getRelative(f);
            if (rel.isEmpty() || rel.isLiquid() || !rel.getType().isOccluding()) n++;
        }
        return n;
    }

    /** Rock moved per ore found. Higher is normal, very low means they knew where to dig. */
    public long ratio(Stats s, String ore) {
        long found = s.ore(ore);
        return found == 0 ? -1 : s.stone / found;
    }

    /** Percent of that ore that was sealed in until the moment it was broken. */
    public int blindPercent(Stats s, String ore) {
        long found = s.ore(ore);
        return found == 0 ? 0 : (int) Math.round(s.blindOf(ore) * 100.0 / found);
    }

    /**
     * 0 = nothing to say, higher = more worth a look. Never a verdict on its own -
     * it is a reason to go watch them, not a reason to ban.
     */
    public int suspicion(Stats s) {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("anticheat");
        long minSamples = cfg == null ? 500 : cfg.getLong("min-samples", 500);
        if (s.stone < minSamples) return 0;

        int score = 0;
        ConfigurationSection warn = cfg == null ? null : cfg.getConfigurationSection("ratio-warn");
        if (warn != null) for (String ore : warn.getKeys(false)) {
            long limit = warn.getLong(ore);
            long r = ratio(s, ore);
            if (r >= 0 && r < limit) {
                // how far under the line they are decides how loud this is
                score += (int) Math.min(50, Math.round((1.0 - (double) r / limit) * 50));
            }
        }
        int blindLimit = cfg == null ? 60 : cfg.getInt("blind-find-percent", 60);
        for (String ore : new String[]{"DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS"}) {
            if (s.ore(ore) < 6) continue;
            int bp = blindPercent(s, ore);
            if (bp > blindLimit) score += Math.min(40, (bp - blindLimit) * 2);
        }
        return Math.min(100, score);
    }

    public String verdict(int score) {
        if (score >= 70) return "<dark_red>น่าสงสัยมาก";
        if (score >= 40) return "<red>น่าสงสัย";
        if (score >= 20) return "<yellow>ควรดูไว้";
        return "<green>ปกติ";
    }

    // ---------------- events ----------------

    private String key(Location l) {
        return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!ORES.contains(e.getBlock().getType())) return;
        placed.add(key(e.getBlock().getLocation()));
        // keep the set from growing without end
        while (placed.size() > 20000) {
            Iterator<String> it = placed.iterator();
            it.next();
            it.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!plugin.getConfig().getBoolean("anticheat.enabled", true)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        if (p.hasPermission("zones.admin")) return;

        Block b = e.getBlock();
        Material m = b.getType();
        Stats s = of(p.getUniqueId());
        s.lastSeen = System.currentTimeMillis();

        if (STONE.contains(m)) { s.stone++; dirty = true; return; }
        if (!ORES.contains(m)) return;
        if (placed.remove(key(b.getLocation()))) return;   // they put it there themselves

        String name = m.name();
        s.ores.merge(name, 1L, Long::sum);
        if (exposedFaces(b) <= 1) s.blind.merge(name, 1L, Long::sum);
        dirty = true;

        int after = suspicion(s);
        if (after >= plugin.getConfig().getInt("anticheat.alert-at", 60)
                && plugin.getConfig().getBoolean("anticheat.alert-staff", true)) {
            maybeAlert(p, s, after);
        }
    }

    private final Map<UUID, Long> lastAlert = new HashMap<>();

    private void maybeAlert(Player p, Stats s, int score) {
        long now = System.currentTimeMillis();
        Long prev = lastAlert.get(p.getUniqueId());
        if (prev != null && now - prev < 600_000L) return;   // at most once every 10 minutes
        lastAlert.put(p.getUniqueId(), now);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("zones.admin.mining")) continue;
            Notify.chat(staff, "<dark_red>[ตรวจจับ] <yellow>" + p.getName()
                    + " <gray>คะแนนน่าสงสัย <white>" + score + "<gray>/100  <white>/mining " + p.getName());
        }
    }

    // ---------------- storage ----------------

    public void load() {
        stats.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                Stats st = new Stats();
                st.stone = s.getLong("stone");
                st.lastSeen = s.getLong("last-seen");
                ConfigurationSection o = s.getConfigurationSection("ores");
                if (o != null) for (String k : o.getKeys(false)) st.ores.put(k, o.getLong(k));
                ConfigurationSection bl = s.getConfigurationSection("blind");
                if (bl != null) for (String k : bl.getKeys(false)) st.blind.put(k, bl.getLong(k));
                stats.put(id, st);
            } catch (Exception ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
            String p = "players." + e.getKey();
            Stats s = e.getValue();
            cfg.set(p + ".stone", s.stone);
            cfg.set(p + ".last-seen", s.lastSeen);
            for (Map.Entry<String, Long> o : s.ores.entrySet()) cfg.set(p + ".ores." + o.getKey(), o.getValue());
            for (Map.Entry<String, Long> o : s.blind.entrySet()) cfg.set(p + ".blind." + o.getKey(), o.getValue());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save mining.yml", ex);
        }
    }

    public void reset(UUID id) { stats.remove(id); dirty = true; }

    /** Online players sorted by how much they need a look at. */
    public List<Map.Entry<UUID, Stats>> ranked() {
        List<Map.Entry<UUID, Stats>> list = new ArrayList<>(stats.entrySet());
        list.sort((a, b) -> Integer.compare(suspicion(b.getValue()), suspicion(a.getValue())));
        return list;
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("zones.admin.mining")) {
            Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            if (args.length < 2) { Notify.chat(p, "<red>/mining reset <ชื่อ>"); return true; }
            var t = Bukkit.getOfflinePlayer(args[1]);
            reset(t.getUniqueId());
            save(true);
            Notify.chat(p, "<green>ล้างสถิติของ <yellow>" + args[1] + " <green>แล้ว");
            return true;
        }
        if (args.length == 0) { top(p); return true; }
        report(p, args[0]);
        return true;
    }

    private void top(Player p) {
        Notify.chat(p, "<gold>อันดับคนที่ควรดู");
        Notify.chat(p, "<dark_gray>คะแนนไม่ใช่คำตัดสิน เป็นแค่เหตุผลให้ไปดูเขาขุดจริง ๆ");
        int shown = 0;
        for (Map.Entry<UUID, Stats> e : ranked()) {
            int sc = suspicion(e.getValue());
            if (sc <= 0) break;
            var off = Bukkit.getOfflinePlayer(e.getKey());
            String nm = off.getName() == null ? e.getKey().toString().substring(0, 8) : off.getName();
            Notify.chat(p, verdict(sc) + " <white>" + nm + " <gray>" + sc + "/100  <dark_gray>/mining " + nm);
            if (++shown >= 10) break;
        }
        if (shown == 0) Notify.chat(p, "<green>ยังไม่มีใครเข้าข่าย");
    }

    private void report(Player p, String who) {
        var off = Bukkit.getOfflinePlayer(who);
        Stats s = stats.get(off.getUniqueId());
        if (s == null || s.stone == 0) { Notify.chat(p, "<gray>ยังไม่มีข้อมูลการขุดของ <yellow>" + who); return; }

        int sc = suspicion(s);
        Notify.chat(p, "<gold>สถิติการขุดของ <yellow>" + who);
        Notify.chat(p, "<gray>หินที่ขุดไปทั้งหมด <white>" + s.stone + " <gray>ก้อน");
        Notify.chat(p, "<gray>สรุป " + verdict(sc) + " <gray>(" + sc + "/100)");

        List<String> watch = List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS",
                "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE", "GOLD_ORE", "DEEPSLATE_GOLD_ORE");
        for (String ore : watch) {
            long found = s.ore(ore);
            if (found == 0) continue;
            long r = ratio(s, ore);
            int bp = blindPercent(s, ore);
            Notify.chat(p, "<white>" + thai(ore) + " <gray>เจอ <white>" + found
                    + " <dark_gray>| <gray>ขุดหิน <white>" + r + " <gray>ก้อนต่อ 1"
                    + " <dark_gray>| <gray>มองไม่เห็นจนขุดถึง <white>" + bp + "%");
        }
        Notify.chat(p, "<dark_gray>ขุดหินต่อเพชร 1 เม็ด คนเล่นปกติมักอยู่หลักหลายร้อย");
        Notify.chat(p, "<dark_gray>ถ้าจะแบน ให้ไปส่องเขาขุดจริงก่อน <white>/spectate " + who);
    }

    private String thai(String ore) {
        return switch (ore) {
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> "เพชร";
            case "ANCIENT_DEBRIS" -> "เศษโบราณ";
            case "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE" -> "มรกต";
            case "GOLD_ORE", "DEEPSLATE_GOLD_ORE" -> "ทอง";
            default -> ore;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p) || !p.hasPermission("zones.admin.mining")) return out;
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            if ("reset".startsWith(pre)) out.add("reset");
            for (Player t : Bukkit.getOnlinePlayers())
                if (t.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(t.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            for (Player t : Bukkit.getOnlinePlayers())
                if (t.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    out.add(t.getName());
        }
        return out;
    }
}
