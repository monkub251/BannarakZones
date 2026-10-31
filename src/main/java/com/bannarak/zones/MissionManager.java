package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class MissionManager {

    private final BannarakZones plugin;
    private final File defFile;
    private final File progressFile;

    private final List<Mission> missions = new ArrayList<>();
    /** player -> mission id -> highest tier already paid out (-1 = none) */
    private final Map<UUID, Map<String, Integer>> claimed = new HashMap<>();
    private boolean dirty = false;

    public MissionManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.defFile = new File(plugin.getDataFolder(), "missions.yml");
        this.progressFile = new File(plugin.getDataFolder(), "missions-progress.yml");
    }

    public List<Mission> missions() { return missions; }

    // ---------------- definitions ----------------

    public void loadDefinitions() {
        missions.clear();
        if (!defFile.exists()) plugin.saveResource("missions.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(defFile);
        ConfigurationSection root = cfg.getConfigurationSection("missions");
        if (root == null) {
            plugin.getLogger().warning("missions.yml has no 'missions' section.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Statistic stat = Statistic.valueOf(s.getString("statistic", "").toUpperCase(Locale.ROOT));
                Material icon = Material.matchMaterial(s.getString("icon", "PAPER"));
                Material mat = s.getString("material") == null
                        ? null : Material.matchMaterial(s.getString("material"));
                EntityType ent = s.getString("entity") == null
                        ? null : EntityType.valueOf(s.getString("entity").toUpperCase(Locale.ROOT));
                double scale = s.getDouble("scale", 1);
                String unit = s.getString("unit", "");
                List<Mission.Tier> tiers = new ArrayList<>();
                ConfigurationSection ts = s.getConfigurationSection("tiers");
                if (ts != null) {
                    List<String> keys = new ArrayList<>(ts.getKeys(false));
                    keys.sort(Comparator.comparingLong(Long::parseLong));
                    for (String k : keys) {
                        long amount = Long.parseLong(k);
                        double reward = ts.getDouble(k + ".reward", 0);
                        List<String> cmds = ts.getStringList(k + ".commands");
                        tiers.add(new Mission.Tier(amount, reward, cmds));
                    }
                }
                if (tiers.isEmpty()) continue;
                missions.add(new Mission(id, s.getString("display", id),
                        icon == null ? Material.PAPER : icon, stat, mat, ent, scale, unit, tiers));
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipping mission '" + id + "': " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + missions.size() + " mission(s).");
    }

    // ---------------- progress ----------------

    public int claimedTier(UUID uuid, String missionId) {
        return claimed.getOrDefault(uuid, Map.of()).getOrDefault(missionId, -1);
    }

    private void setClaimed(UUID uuid, String missionId, int tier) {
        claimed.computeIfAbsent(uuid, k -> new HashMap<>()).put(missionId, tier);
        dirty = true;
    }

    /** Pays out every milestone the player has passed since the last check. */
    public void check(Player p) {
        UUID id = p.getUniqueId();
        for (Mission m : missions) {
            long have = m.progress(p);
            int done = claimedTier(id, m.id);
            for (int i = done + 1; i < m.tierCount(); i++) {
                Mission.Tier t = m.tier(i);
                if (t == null || have < t.amount) break;
                award(p, m, t, i);
                setClaimed(id, m.id, i);
            }
        }
    }

    private void award(Player p, Mission m, Mission.Tier t, int index) {
        if (t.reward > 0) plugin.money().add(p.getUniqueId(), t.reward);

        String line = "<yellow>" + m.display + " <gray>ขั้นที่ <white>" + (index + 1);
        String sub = "<gray>+<gold>" + plugin.money().format(t.reward);
        if (plugin.getConfig().getBoolean("missions.popup", true))
            Notify.popup(p, "<gradient:#ffd966:#f0a500>ภารกิจสำเร็จ</gradient>", line + "  " + sub,
                    "entity.player.levelup", 1.3f);
        else
            Notify.bar(p, line + " " + sub, "entity.player.levelup", 1.3f);

        if (plugin.getConfig().getBoolean("missions.broadcast-final", true)
                && index == m.tierCount() - 1) {
            Bukkit.broadcast(Notify.of("<gold>★ <yellow>" + p.getName()
                    + " <white>ทำภารกิจ <yellow>" + m.display + " <white>ครบทุกขั้นแล้ว"));
        }

        for (String cmd : t.commands)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("<player>", p.getName()).replace("%player%", p.getName()));
    }

    public void startTracking() {
        long period = Math.max(20L, plugin.getConfig().getLong("missions.check-interval-ticks", 100L));
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) check(p);
            save(false);
        }, 200L, period);
    }

    // ---------------- persistence ----------------

    public void load() {
        claimed.clear();
        if (!progressFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(progressFile);
        ConfigurationSection root = cfg.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                Map<String, Integer> map = new HashMap<>();
                for (String mid : s.getKeys(false)) map.put(mid, s.getInt(mid, -1));
                claimed.put(id, map);
            } catch (Exception ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Integer>> e : claimed.entrySet())
            for (Map.Entry<String, Integer> m : e.getValue().entrySet())
                cfg.set("players." + e.getKey() + "." + m.getKey(), m.getValue());
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(progressFile);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save mission progress", ex);
        }
    }
}
