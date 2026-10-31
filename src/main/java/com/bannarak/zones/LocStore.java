package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/** Homes, warps and the server spawn, all in one file. */
public class LocStore {

    private final BannarakZones plugin;
    private final File file;

    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();
    private final Map<String, Location> warps = new LinkedHashMap<>();
    private final Map<String, String> warpIcons = new LinkedHashMap<>();
    private Location spawn;
    private boolean dirty = false;

    public LocStore(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "locations.yml");
    }

    // ---------- homes ----------

    public Map<String, Location> homesOf(UUID uuid) {
        return homes.getOrDefault(uuid, Map.of());
    }

    public Location home(UUID uuid, String name) {
        return homesOf(uuid).get(name.toLowerCase(Locale.ROOT));
    }

    public void setHome(UUID uuid, String name, Location loc) {
        homes.computeIfAbsent(uuid, k -> new LinkedHashMap<>()).put(name.toLowerCase(Locale.ROOT), loc);
        dirty = true;
    }

    public boolean deleteHome(UUID uuid, String name) {
        Map<String, Location> map = homes.get(uuid);
        if (map == null) return false;
        boolean removed = map.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) dirty = true;
        return removed;
    }

    public int homeLimit(org.bukkit.entity.Player p) {
        int max = plugin.getConfig().getInt("homes.default-limit", 1);
        int ceiling = plugin.getConfig().getInt("homes.permission-ceiling", 20);
        for (int i = ceiling; i > max; i--)
            if (p.hasPermission("zones.homes." + i)) return i;
        return max;
    }

    // ---------- warps ----------

    public Set<String> warpNames() { return warps.keySet(); }
    public Location warp(String name) { return warps.get(name.toLowerCase(Locale.ROOT)); }
    public String warpIcon(String name) { return warpIcons.getOrDefault(name.toLowerCase(Locale.ROOT), "ENDER_PEARL"); }

    public void setWarp(String name, Location loc, String icon) {
        warps.put(name.toLowerCase(Locale.ROOT), loc);
        if (icon != null) warpIcons.put(name.toLowerCase(Locale.ROOT), icon);
        dirty = true;
    }

    public boolean deleteWarp(String name) {
        boolean removed = warps.remove(name.toLowerCase(Locale.ROOT)) != null;
        warpIcons.remove(name.toLowerCase(Locale.ROOT));
        if (removed) dirty = true;
        return removed;
    }

    // ---------- spawn ----------

    public Location spawn() {
        if (spawn != null) return spawn;
        String worldName = plugin.getConfig().getString("spawn.world", "lobby");
        World w = Bukkit.getWorld(worldName);
        if (w == null) w = Bukkit.getWorlds().get(0);
        return new Location(w,
                plugin.getConfig().getDouble("spawn.x", 0.5),
                plugin.getConfig().getDouble("spawn.y", 100),
                plugin.getConfig().getDouble("spawn.z", 0.5),
                (float) plugin.getConfig().getDouble("spawn.yaw", 0),
                (float) plugin.getConfig().getDouble("spawn.pitch", 0));
    }

    public void setSpawn(Location loc) {
        spawn = loc;
        dirty = true;
    }

    // ---------- persistence ----------

    private static void write(YamlConfiguration cfg, String path, Location l) {
        cfg.set(path + ".world", l.getWorld() == null ? "world" : l.getWorld().getName());
        cfg.set(path + ".x", l.getX());
        cfg.set(path + ".y", l.getY());
        cfg.set(path + ".z", l.getZ());
        cfg.set(path + ".yaw", l.getYaw());
        cfg.set(path + ".pitch", l.getPitch());
    }

    private static Location read(ConfigurationSection s) {
        if (s == null) return null;
        World w = Bukkit.getWorld(s.getString("world", "world"));
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    public void load() {
        homes.clear(); warps.clear(); warpIcons.clear(); spawn = null;
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection h = cfg.getConfigurationSection("homes");
        if (h != null) for (String key : h.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection per = h.getConfigurationSection(key);
                if (per == null) continue;
                Map<String, Location> map = new LinkedHashMap<>();
                for (String name : per.getKeys(false)) {
                    Location l = read(per.getConfigurationSection(name));
                    if (l != null) map.put(name, l);
                }
                homes.put(id, map);
            } catch (Exception ignored) { }
        }

        ConfigurationSection w = cfg.getConfigurationSection("warps");
        if (w != null) for (String name : w.getKeys(false)) {
            Location l = read(w.getConfigurationSection(name));
            if (l != null) {
                warps.put(name, l);
                String icon = w.getString(name + ".icon");
                if (icon != null) warpIcons.put(name, icon);
            }
        }

        spawn = read(cfg.getConfigurationSection("spawn"));
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Location>> e : homes.entrySet())
            for (Map.Entry<String, Location> hm : e.getValue().entrySet())
                write(cfg, "homes." + e.getKey() + "." + hm.getKey(), hm.getValue());
        for (Map.Entry<String, Location> e : warps.entrySet()) {
            write(cfg, "warps." + e.getKey(), e.getValue());
            cfg.set("warps." + e.getKey() + ".icon", warpIcons.get(e.getKey()));
        }
        if (spawn != null) write(cfg, "spawn", spawn);
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save locations", ex);
        }
    }
}
