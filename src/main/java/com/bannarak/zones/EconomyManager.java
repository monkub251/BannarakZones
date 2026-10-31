package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** The server's own money. No other plugin owns this number. */
public class EconomyManager {

    private final BannarakZones plugin;
    private final File file;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final DecimalFormat fmt = new DecimalFormat("#,##0.##");
    private boolean dirty = false;

    public EconomyManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
    }

    public String currencyName() {
        return plugin.getConfig().getString("economy.currency-name", "เหรียญ");
    }

    public String symbol() {
        return plugin.getConfig().getString("economy.currency-symbol", "₡");
    }

    public double startingBalance() {
        return plugin.getConfig().getDouble("economy.starting-balance", 1000);
    }

    public String format(double amount) {
        return symbol() + fmt.format(amount);
    }

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public void createAccount(UUID uuid, String name) {
        if (balances.putIfAbsent(uuid, startingBalance()) == null) dirty = true;
        if (name != null) names.put(uuid, name);
    }

    public double get(UUID uuid) {
        return balances.getOrDefault(uuid, 0.0);
    }

    public boolean has(UUID uuid, double amount) {
        return get(uuid) >= amount;
    }

    public void set(UUID uuid, double amount) {
        balances.put(uuid, Math.max(0, round(amount)));
        dirty = true;
    }

    public void add(UUID uuid, double amount) {
        set(uuid, get(uuid) + amount);
    }

    /** Returns false when the player cannot afford it; nothing is taken in that case. */
    public boolean take(UUID uuid, double amount) {
        if (!has(uuid, amount)) return false;
        set(uuid, get(uuid) - amount);
        return true;
    }

    public boolean transfer(UUID from, UUID to, double amount) {
        if (amount <= 0 || !take(from, amount)) return false;
        add(to, amount);
        return true;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public String nameOf(UUID uuid) {
        String n = names.get(uuid);
        if (n != null) return n;
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() == null ? uuid.toString().substring(0, 8) : op.getName();
    }

    public void rememberName(UUID uuid, String name) {
        if (name != null && !name.equals(names.get(uuid))) {
            names.put(uuid, name);
            dirty = true;
        }
    }

    public List<Map.Entry<UUID, Double>> top(int limit) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(balances.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(limit, list.size()));
    }

    public UUID lookup(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet())
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
        return online == null ? null : online.getUniqueId();
    }

    // ---------- persistence ----------

    public void load() {
        balances.clear();
        names.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("accounts");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                balances.put(id, root.getDouble(key + ".balance", 0));
                String n = root.getString(key + ".name");
                if (n != null) names.put(id, n);
            } catch (Exception ignored) { }
        }
        plugin.getLogger().info("Loaded " + balances.size() + " account(s).");
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            cfg.set("accounts." + e.getKey() + ".balance", e.getValue());
            String n = names.get(e.getKey());
            if (n != null) cfg.set("accounts." + e.getKey() + ".name", n);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save economy data", ex);
        }
    }
}
