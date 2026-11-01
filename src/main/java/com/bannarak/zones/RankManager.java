package com.bannarak.zones;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Ranks earned by playing, or bought outright. */
public class RankManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    private final List<Rank> ranks = new ArrayList<>();
    private final Map<UUID, String> current = new HashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private boolean dirty = false;

    public RankManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ranks-progress.yml");
    }

    public static class Rank {
        public String id;
        public String display;
        public String prefix = "";
        public int order;
        public double price = -1;          // -1 = cannot be bought
        public double needMoney = 0;
        public double needHours = 0;
        public int needMissions = 0;
        public List<String> permissions = new ArrayList<>();
        public List<String> commands = new ArrayList<>();
    }

    public List<Rank> ranks() { return ranks; }

    public Rank rankOf(UUID uuid) {
        String id = current.get(uuid);
        if (id != null) for (Rank r : ranks) if (r.id.equals(id)) return r;
        return ranks.isEmpty() ? null : ranks.get(0);
    }

    // ---------------- loading ----------------

    public void load() {
        ranks.clear();
        File def = new File(plugin.getDataFolder(), "ranks.yml");
        if (!def.exists()) plugin.saveResource("ranks.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(def);
        ConfigurationSection root = cfg.getConfigurationSection("ranks");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                Rank r = new Rank();
                r.id = id;
                r.display = s.getString("display", id);
                r.prefix = s.getString("prefix", "");
                r.order = s.getInt("order", 0);
                r.price = s.getDouble("price", -1);
                r.needMoney = s.getDouble("require.money", 0);
                r.needHours = s.getDouble("require.hours", 0);
                r.needMissions = s.getInt("require.missions", 0);
                r.permissions = s.getStringList("permissions");
                r.commands = s.getStringList("commands");
                ranks.add(r);
            }
            ranks.sort(Comparator.comparingInt(a -> a.order));
        }
        loadProgress();
        plugin.getLogger().info("Loaded " + ranks.size() + " rank(s).");
    }

    // ---------------- promotion ----------------

    private double hoursPlayed(Player p) {
        try {
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000.0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private int missionsDone(Player p) {
        int done = 0;
        for (Mission m : plugin.missions().missions())
            if (plugin.missions().claimedTier(p.getUniqueId(), m.id) >= m.tierCount() - 1) done++;
        return done;
    }

    /** Sets someone's rank outright, for staff tools. False if no such rank. */
    public boolean setRank(UUID uuid, String rankId) {
        for (Rank r : ranks) {
            if (!r.id.equalsIgnoreCase(rankId)) continue;
            current.put(uuid, r.id);
            dirty = true;
            save(false);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) applyPermissions(online);
            return true;
        }
        return false;
    }

    public boolean qualifies(Player p, Rank r) {
        return plugin.money().get(p.getUniqueId()) >= r.needMoney
                && hoursPlayed(p) >= r.needHours
                && missionsDone(p) >= r.needMissions;
    }

    /** Walks the ladder and promotes as far as the player has earned. */
    public void check(Player p) {
        Rank now = rankOf(p.getUniqueId());
        int nowOrder = now == null ? Integer.MIN_VALUE : now.order;
        Rank best = now;
        for (Rank r : ranks) {
            if (r.order <= nowOrder) continue;
            if (r.price >= 0 && (r.needMoney > 0 || r.needHours > 0 || r.needMissions > 0)) {
                // a paid rank with requirements is still earnable
                if (!qualifies(p, r)) continue;
            } else if (r.price >= 0) {
                continue;   // purely purchasable, never auto-granted
            } else if (!qualifies(p, r)) {
                continue;
            }
            if (best == null || r.order > best.order) best = r;
        }
        if (best != null && (now == null || best.order > now.order)) grant(p, best, true);
    }

    public void grant(Player p, Rank r, boolean announce) {
        current.put(p.getUniqueId(), r.id);
        dirty = true;
        applyPermissions(p);
        if (announce) {
            Notify.popup(p, "<gradient:#ffe08a:#ffa600>เลื่อนยศ</gradient>",
                    "<white>คุณได้ยศ " + r.display, "ui.toast.challenge_complete", 1.0f);
            if (plugin.getConfig().getBoolean("ranks.broadcast", true))
                Bukkit.broadcast(Notify.of("<gold>★ <yellow>" + p.getName()
                        + " <white>เลื่อนเป็น " + r.display));
        }
        for (String cmd : r.commands)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("<player>", p.getName()));
    }

    private void applyPermissions(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) try { p.removeAttachment(old); } catch (Exception ignored) { }
        Rank r = rankOf(p.getUniqueId());
        if (r == null || r.permissions.isEmpty()) return;
        PermissionAttachment att = p.addAttachment(plugin);
        for (String node : r.permissions) att.setPermission(node, true);
        attachments.put(p.getUniqueId(), att);
    }

    public boolean buy(Player p, Rank r) {
        if (r.price < 0) { Notify.chat(p, "<red>ยศนี้ซื้อไม่ได้"); return false; }
        Rank now = rankOf(p.getUniqueId());
        if (now != null && now.order >= r.order) { Notify.chat(p, "<gray>คุณมียศนี้หรือสูงกว่าแล้ว"); return false; }
        if (!plugin.money().has(p.getUniqueId(), r.price)) {
            Notify.chat(p, "<red>เงินไม่พอ ต้องมี <gold>" + plugin.money().format(r.price));
            return false;
        }
        plugin.money().take(p.getUniqueId(), r.price);
        plugin.money().save(false);
        grant(p, r, true);
        return true;
    }

    // ---------------- chat ----------------

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("ranks.format-chat", true)) return;
        Rank r = rankOf(e.getPlayer().getUniqueId());
        String prefix = r == null ? "" : r.prefix;
        String format = plugin.getConfig().getString("ranks.chat-format",
                "<prefix><white><player><gray>: <white><message>");
        e.renderer((source, displayName, message, viewer) -> {
            String base = format
                    .replace("<prefix>", prefix)
                    .replace("<player>", source.getName());
            int split = base.indexOf("<message>");
            if (split < 0) return MM.deserialize(base).append(message);
            Component head = MM.deserialize(base.substring(0, split));
            Component tail = MM.deserialize(base.substring(split + "<message>".length()));
            return head.append(message).append(tail);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyPermissions(e.getPlayer());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> check(e.getPlayer()), 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        attachments.remove(e.getPlayer().getUniqueId());
    }

    public void startTracking() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) check(p);
            save(false);
        }, 600L, 1200L);
    }

    // ---------------- persistence ----------------

    private void loadProgress() {
        current.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try { current.put(UUID.fromString(key), root.getString(key)); } catch (Exception ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : current.entrySet()) cfg.set("players." + e.getKey(), e.getValue());
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ignored) { }
    }
}
