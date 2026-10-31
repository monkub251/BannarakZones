package com.bannarak.zones;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class ZoneStore {

    private final BannarakZones plugin;
    private final File zonesFile;
    private final File playersFile;

    private final Map<UUID, Zone> byId = new HashMap<>();
    private final Map<String, Map<Long, List<Zone>>> index = new HashMap<>();
    private final Map<UUID, Integer> slots = new HashMap<>();

    public ZoneStore(BannarakZones plugin) {
        this.plugin = plugin;
        this.zonesFile = new File(plugin.getDataFolder(), "zones.yml");
        this.playersFile = new File(plugin.getDataFolder(), "players.yml");
    }

    // ---------- lookup ----------

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private void addToIndex(Zone z) {
        Map<Long, List<Zone>> worldIndex = index.computeIfAbsent(z.world, k -> new HashMap<>());
        int minCx = (z.x - z.radius) >> 4, maxCx = (z.x + z.radius) >> 4;
        int minCz = (z.z - z.radius) >> 4, maxCz = (z.z + z.radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++)
            for (int cz = minCz; cz <= maxCz; cz++)
                worldIndex.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(z);
    }

    private void removeFromIndex(Zone z) {
        Map<Long, List<Zone>> worldIndex = index.get(z.world);
        if (worldIndex == null) return;
        for (Iterator<Map.Entry<Long, List<Zone>>> it = worldIndex.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, List<Zone>> e = it.next();
            e.getValue().remove(z);
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    public void reindex(Zone z) {
        removeFromIndex(z);
        addToIndex(z);
    }

    public Zone zoneAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return zoneAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Zone zoneAt(String world, int x, int y, int z) {
        Map<Long, List<Zone>> worldIndex = index.get(world);
        if (worldIndex == null) return null;
        List<Zone> list = worldIndex.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        boolean full = plugin.outsiderFullHeight();
        int vr = plugin.verticalRange();
        for (Zone zone : list)
            if (zone.contains(world, x, y, z, full, vr)) return zone;
        return null;
    }

    /** Zone whose column covers this x/z, ignoring height. Used for explosions and fire. */
    public Zone columnAt(String world, int x, int z) {
        Map<Long, List<Zone>> worldIndex = index.get(world);
        if (worldIndex == null) return null;
        List<Zone> list = worldIndex.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        for (Zone zone : list) if (zone.coversColumn(world, x, z)) return zone;
        return null;
    }

    public boolean wouldOverlap(String world, int x, int z, int radius, UUID ignoreId, int gap) {
        int reach = radius + gap;
        for (Zone zone : byId.values()) {
            if (!zone.world.equals(world)) continue;
            if (ignoreId != null && zone.id.equals(ignoreId)) continue;
            int need = reach + zone.radius;
            if (Math.abs(zone.x - x) <= need && Math.abs(zone.z - z) <= need) return true;
        }
        return false;
    }

    public Zone zoneByBeacon(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        String w = loc.getWorld().getName();
        for (Zone z : byId.values())
            if (z.world.equals(w) && z.x == loc.getBlockX() && z.y == loc.getBlockY() && z.z == loc.getBlockZ())
                return z;
        return null;
    }

    public List<Zone> zonesOf(UUID owner) {
        List<Zone> out = new ArrayList<>();
        for (Zone z : byId.values()) if (z.owner.equals(owner)) out.add(z);
        return out;
    }

    public Collection<Zone> all() { return byId.values(); }

    public void add(Zone z) { byId.put(z.id, z); addToIndex(z); }

    public void remove(Zone z) { byId.remove(z.id); removeFromIndex(z); }

    // ---------- slots ----------

    public int slotsOf(UUID uuid) { return slots.getOrDefault(uuid, 1); }

    public void setSlots(UUID uuid, int n) { slots.put(uuid, n); }

    // ---------- persistence ----------

    public void load() {
        byId.clear(); index.clear(); slots.clear();
        if (zonesFile.exists()) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(zonesFile);
            ConfigurationSection root = cfg.getConfigurationSection("zones");
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    ConfigurationSection s = root.getConfigurationSection(key);
                    if (s == null) continue;
                    try {
                        Zone z = new Zone(
                                UUID.fromString(key),
                                UUID.fromString(Objects.requireNonNull(s.getString("owner"))),
                                s.getString("owner-name", "?"),
                                Objects.requireNonNull(s.getString("world")),
                                s.getInt("x"), s.getInt("y"), s.getInt("z"),
                                s.getInt("radius", plugin.defaultRadius()));
                        z.allowPvp = s.getBoolean("allow-pvp", false);
                        z.allowExplosions = s.getBoolean("allow-explosions", false);
                        z.showMessages = s.getBoolean("show-messages", true);
                        ConfigurationSection mem = s.getConfigurationSection("members");
                        if (mem != null)
                            for (String mk : mem.getKeys(false))
                                z.addMember(UUID.fromString(mk), mem.getString(mk, "?"));
                        add(z);
                    } catch (Exception ex) {
                        plugin.getLogger().log(Level.WARNING, "Skipping broken zone " + key, ex);
                    }
                }
            }
        }
        if (playersFile.exists()) {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(playersFile);
            ConfigurationSection root = cfg.getConfigurationSection("slots");
            if (root != null)
                for (String key : root.getKeys(false))
                    try { slots.put(UUID.fromString(key), root.getInt(key)); } catch (Exception ignored) { }
        }
        plugin.getLogger().info("Loaded " + byId.size() + " zone(s).");
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Zone z : byId.values()) {
            String base = "zones." + z.id;
            cfg.set(base + ".owner", z.owner.toString());
            cfg.set(base + ".owner-name", z.ownerName);
            cfg.set(base + ".world", z.world);
            cfg.set(base + ".x", z.x);
            cfg.set(base + ".y", z.y);
            cfg.set(base + ".z", z.z);
            cfg.set(base + ".radius", z.radius);
            cfg.set(base + ".allow-pvp", z.allowPvp);
            cfg.set(base + ".allow-explosions", z.allowExplosions);
            cfg.set(base + ".show-messages", z.showMessages);
            for (UUID m : z.members) cfg.set(base + ".members." + m, z.memberName(m));
        }
        YamlConfiguration pc = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : slots.entrySet()) pc.set("slots." + e.getKey(), e.getValue());
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(zonesFile);
            pc.save(playersFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save zone data", ex);
        }
    }
}
