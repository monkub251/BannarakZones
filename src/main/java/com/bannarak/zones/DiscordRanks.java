package com.bannarak.zones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Level;

/**
 * Ranks come from Discord now. There is no ladder to climb in game and
 * nothing to buy: a player links their Discord account once, and whatever
 * roles they hold over there decide the title and the permissions they
 * get here. The bot pushes changes to the web panel's /api/discord
 * endpoints, so any small script can drive it.
 */
public class DiscordRanks implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public static class Rank {
        public String id;
        public String display;
        public String prefix = "";
        public int order;
        public String roleId = "";                       // the Discord role
        public List<String> permissions = new ArrayList<>();
    }

    /** What we know about one player's Discord account. */
    public static class Link {
        public String discordId = "";
        public String discordName = "";
        public final Set<String> roles = new LinkedHashSet<>();
        public long linkedAt;
    }

    private final BannarakZones plugin;
    private final File file;

    private final List<Rank> ranks = new ArrayList<>();
    private final Map<UUID, Link> links = new HashMap<>();
    private final Map<String, UUID> pending = new HashMap<>();     // code -> player
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private boolean dirty = false;

    public DiscordRanks(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "discord.yml");
    }

    public List<Rank> ranks() { return ranks; }

    public Link linkOf(UUID id) { return links.get(id); }

    /** The rank this player holds, highest order first. Null if none fits. */
    public Rank rankOf(UUID uuid) {
        Link link = links.get(uuid);
        Rank best = null;
        if (link != null) {
            for (Rank r : ranks) {
                boolean held = (!r.roleId.isBlank() && link.roles.contains(r.roleId))
                        || link.roles.contains("manual:" + r.id);
                if (!held) continue;
                if (best == null || r.order > best.order) best = r;
            }
        }
        if (best != null) return best;
        // Nothing linked: fall back to the lowest rank so chat still has a title.
        return ranks.isEmpty() ? null : ranks.get(0);
    }

    public String prefixOf(UUID uuid) {
        Rank r = rankOf(uuid);
        return r == null ? "" : r.prefix;
    }

    // ---------------- loading ----------------

    public void load() {
        ranks.clear();
        File f = new File(plugin.getDataFolder(), "ranks.yml");
        if (!f.exists()) plugin.saveResource("ranks.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("ranks");
        if (root == null) {
            plugin.getLogger().warning("ranks.yml ไม่มีหัวข้อ ranks:");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Rank r = new Rank();
            r.id = id;
            r.display = s.getString("display", id);
            r.prefix = s.getString("prefix", "");
            r.order = s.getInt("order", 0);
            r.roleId = s.getString("discord-role", "");
            r.permissions = s.getStringList("permissions");
            ranks.add(r);
        }
        ranks.sort(Comparator.comparingInt(a -> a.order));
        plugin.getLogger().info("โหลดยศ " + ranks.size() + " ขั้น (ผูกกับ Discord)");
        loadLinks();
    }

    // ---------------- permissions ----------------

    private void applyPermissions(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) try { p.removeAttachment(old); } catch (IllegalArgumentException ignored) { }
        Rank r = rankOf(p.getUniqueId());
        if (r == null || r.permissions.isEmpty()) return;
        PermissionAttachment att = p.addAttachment(plugin);
        for (String node : r.permissions) att.setPermission(node, true);
        attachments.put(p.getUniqueId(), att);
    }

    /**
     * Sets a rank by hand, for staff tools and for the stretch before the
     * Discord bot is wired up. It works by granting the matching role id
     * locally, so the moment the bot does start pushing roles the same
     * resolution applies and nothing has to be unpicked.
     */
    public boolean setRank(UUID uuid, String rankId) {
        Rank target = null;
        for (Rank r : ranks) if (r.id.equalsIgnoreCase(rankId)) { target = r; break; }
        if (target == null) return false;

        Link link = links.computeIfAbsent(uuid, k -> new Link());
        for (Rank r : ranks) if (!r.roleId.isBlank()) link.roles.remove(r.roleId);
        if (!target.roleId.isBlank()) link.roles.add(target.roleId);
        else link.roles.add("manual:" + target.id);
        dirty = true;
        save(false);
        refresh(uuid);
        return true;
    }

    public void refresh(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) Bukkit.getScheduler().runTask(plugin, () -> applyPermissions(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyPermissions(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        attachments.remove(e.getPlayer().getUniqueId());
    }

    // ---------------- linking ----------------

    /** A short code the player types to the bot. One use, short lived. */
    public String issueCode(Player p) {
        pending.values().removeIf(v -> v.equals(p.getUniqueId()));
        String code;
        do {
            code = Integer.toString(100000 + random.nextInt(900000));
        } while (pending.containsKey(code));
        pending.put(code, p.getUniqueId());
        String finalCode = code;
        Bukkit.getScheduler().runTaskLater(plugin, () -> pending.remove(finalCode), 20L * 600);
        return code;
    }

    /** Called by the bot through the web panel. Returns the player's name. */
    public String completeLink(String code, String discordId, String discordName) {
        UUID who = pending.remove(code);
        if (who == null) return null;
        Link link = links.computeIfAbsent(who, k -> new Link());
        link.discordId = discordId == null ? "" : discordId;
        link.discordName = discordName == null ? "" : discordName;
        link.linkedAt = System.currentTimeMillis();
        dirty = true;
        save(true);
        refresh(who);
        Player p = Bukkit.getPlayer(who);
        if (p != null) Bukkit.getScheduler().runTask(plugin, () ->
                Notify.popup(p, "<gold>ผูก Discord แล้ว", "<gray>" + link.discordName,
                        "ui.toast.challenge_complete", 1.1f));
        return p != null ? p.getName() : plugin.money().nameOf(who);
    }

    /** Called by the bot when someone's roles change over there. */
    public boolean setRoles(String discordId, Collection<String> roleIds) {
        UUID who = null;
        for (var e : links.entrySet())
            if (e.getValue().discordId.equals(discordId)) { who = e.getKey(); break; }
        if (who == null) return false;
        Link link = links.get(who);
        link.roles.clear();
        link.roles.addAll(roleIds);
        dirty = true;
        save(true);
        refresh(who);
        Player p = Bukkit.getPlayer(who);
        if (p != null) {
            Rank r = rankOf(who);
            Bukkit.getScheduler().runTask(plugin, () ->
                    Notify.chat(p, "<gray>ยศของคุณอัปเดตเป็น " + (r == null ? "-" : r.display)));
        }
        return true;
    }

    public boolean unlink(UUID who) {
        if (links.remove(who) == null) return false;
        dirty = true;
        save(true);
        refresh(who);
        return true;
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }

        if (args.length > 0 && args[0].equalsIgnoreCase("unlink")) {
            Notify.chat(p, unlink(p.getUniqueId())
                    ? "<yellow>ยกเลิกการผูก Discord แล้ว"
                    : "<red>คุณยังไม่ได้ผูกบัญชีไว้");
            return true;
        }

        Link link = links.get(p.getUniqueId());
        Rank r = rankOf(p.getUniqueId());
        String invite = plugin.getConfig().getString("discord.invite", "");

        p.sendMessage(MM.deserialize("<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        p.sendMessage(MM.deserialize("   <gradient:#f0cf8a:#b8863a><bold>ยศของคุณ</bold></gradient>"));
        p.sendMessage(MM.deserialize("<gray>ตอนนี้ " + (r == null ? "<white>-" : r.prefix + "<white>" + r.display)));

        if (link != null && !link.discordId.isBlank()) {
            p.sendMessage(MM.deserialize("<gray>ผูกกับ <white>" + link.discordName));
            p.sendMessage(MM.deserialize("<dark_gray>ยศมาจากบทบาทใน Discord เปลี่ยนที่นั่นแล้วมาที่นี่เอง"));
            p.sendMessage(MM.deserialize("<dark_gray>เลิกผูก: <white>/rank unlink"));
        } else {
            String code = issueCode(p);
            p.sendMessage(MM.deserialize("<gray>ยังไม่ได้ผูกกับ Discord"));
            if (!invite.isBlank())
                p.sendMessage(MM.deserialize("<gray>เข้าดิสของเซิร์ฟ <aqua>" + invite)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(invite)));
            p.sendMessage(MM.deserialize("<gray>แล้วพิมพ์ในดิส <bold><yellow>/link " + code + "</yellow></bold>")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard("/link " + code)));
            p.sendMessage(MM.deserialize("<dark_gray>รหัสใช้ได้ครั้งเดียว หมดอายุใน 10 นาที"));
        }
        p.sendMessage(MM.deserialize("<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        p.playSound(p.getLocation(), "ui.button.click", 0.6f, 1.3f);
        return true;
    }

    // ---------------- persistence ----------------

    private void loadLinks() {
        links.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("links");
        if (root == null) return;
        for (String raw : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(raw);
                Link l = new Link();
                l.discordId = root.getString(raw + ".discord-id", "");
                l.discordName = root.getString(raw + ".discord-name", "");
                l.roles.addAll(root.getStringList(raw + ".roles"));
                l.linkedAt = root.getLong(raw + ".linked-at", 0);
                links.put(id, l);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("discord.yml: ข้าม " + raw);
            }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (var e : links.entrySet()) {
            String base = "links." + e.getKey();
            yml.set(base + ".discord-id", e.getValue().discordId);
            yml.set(base + ".discord-name", e.getValue().discordName);
            yml.set(base + ".roles", new ArrayList<>(e.getValue().roles));
            yml.set(base + ".linked-at", e.getValue().linkedAt);
        }
        try {
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "บันทึก discord.yml ไม่ได้", ex);
        }
    }
}
