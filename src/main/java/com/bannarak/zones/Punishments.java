package com.bannarak.zones;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/** Bans, mutes and the record of who did what. */
public class Punishments implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    private final List<Entry> history = new ArrayList<>();
    private boolean dirty = false;

    public Punishments(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
    }

    public enum Type { BAN, MUTE, KICK, WARN }

    public static class Entry {
        public UUID id;
        public UUID target;
        public String targetName;
        public Type type;
        public String reason;
        public String staff;
        public long at;
        public long until;      // 0 = forever, -1 = not time based (kick/warn)
        public boolean lifted;
    }

    public List<Entry> history() { return history; }

    // ---------------- helpers ----------------

    /** Accepts 30m, 2h, 7d, 1w, or "perm". Returns 0 for permanent, -1 when unparsable. */
    public static long parseDuration(String raw) {
        if (raw == null) return -1;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.equals("perm") || s.equals("forever") || s.equals("ถาวร")) return 0;
        try {
            char unit = s.charAt(s.length() - 1);
            long n = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (unit) {
                case 's' -> n * 1000L;
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'd' -> n * 86_400_000L;
                case 'w' -> n * 604_800_000L;
                default -> -1;
            };
        } catch (Exception ex) {
            return -1;
        }
    }

    public static String readable(long until) {
        if (until == 0) return "ถาวร";
        long left = until - System.currentTimeMillis();
        if (left <= 0) return "หมดแล้ว";
        long days = left / 86_400_000L;
        long hours = (left % 86_400_000L) / 3_600_000L;
        long minutes = (left % 3_600_000L) / 60_000L;
        if (days > 0) return days + " วัน " + hours + " ชม.";
        if (hours > 0) return hours + " ชม. " + minutes + " นาที";
        return Math.max(1, minutes) + " นาที";
    }

    public Entry activeOf(UUID target, Type type) {
        for (Entry e : history) {
            if (e.lifted || !e.target.equals(target) || e.type != type) continue;
            if (e.until == 0) return e;
            if (e.until > System.currentTimeMillis()) return e;
        }
        return null;
    }

    public boolean isBanned(UUID id) { return activeOf(id, Type.BAN) != null; }
    public boolean isMuted(UUID id) { return activeOf(id, Type.MUTE) != null; }

    // ---------------- actions ----------------

    public Entry punish(UUID target, String targetName, Type type, String reason, String staff, long duration) {
        Entry e = new Entry();
        e.id = UUID.randomUUID();
        e.target = target;
        e.targetName = targetName;
        e.type = type;
        e.reason = reason == null || reason.isBlank() ? "ไม่ระบุเหตุผล" : reason;
        e.staff = staff;
        e.at = System.currentTimeMillis();
        e.until = switch (type) {
            case BAN, MUTE -> duration <= 0 ? 0 : System.currentTimeMillis() + duration;
            case KICK, WARN -> -1;
        };
        history.add(e);
        dirty = true;
        save(false);

        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            switch (type) {
                case BAN -> online.kick(banScreen(e));
                case KICK -> online.kick(MM.deserialize("<red>ถูกเตะออกจากเซิร์ฟ\n\n<white>" + e.reason
                        + "\n\n<gray>โดย " + e.staff));
                case MUTE -> Notify.chat(online, "<red>คุณถูกปิดปาก <gray>" + readable(e.until)
                        + "\n<white>เหตุผล: " + e.reason);
                case WARN -> Notify.popup(online, "<red>คำเตือน", "<white>" + e.reason,
                        "block.note_block.bass", 0.7f);
            }
        }
        if (plugin.getConfig().getBoolean("punish.broadcast", true) && type != Type.WARN) {
            String verb = switch (type) {
                case BAN -> "ถูกแบน";
                case MUTE -> "ถูกปิดปาก";
                case KICK -> "ถูกเตะ";
                default -> "";
            };
            Bukkit.broadcast(Notify.of("<red>⚠ <yellow>" + targetName + " <white>" + verb
                    + " <gray>(" + e.reason + ")"));
        }
        return e;
    }

    private net.kyori.adventure.text.Component banScreen(Entry e) {
        return MM.deserialize("<red><bold>คุณถูกแบนจากเซิร์ฟนี้</bold>\n\n"
                + "<white>เหตุผล: <yellow>" + e.reason + "\n"
                + "<white>ระยะเวลา: <yellow>" + readable(e.until) + "\n"
                + "<white>โดย: <gray>" + e.staff + "\n\n"
                + "<gray>" + plugin.getConfig().getString("punish.appeal", "ติดต่อทีมงานใน Discord"));
    }

    public boolean lift(UUID target, Type type) {
        Entry e = activeOf(target, type);
        if (e == null) return false;
        e.lifted = true;
        dirty = true;
        save(false);
        return true;
    }

    // ---------------- enforcement ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(AsyncPlayerPreLoginEvent e) {
        Entry ban = activeOf(e.getUniqueId(), Type.BAN);
        if (ban != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Entry mute = activeOf(e.getPlayer().getUniqueId(), Type.MUTE);
        if (mute == null) return;
        e.setCancelled(true);
        Notify.chat(e.getPlayer(), "<red>คุณถูกปิดปากอยู่ <gray>เหลือ " + readable(mute.until));
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("zones.admin")) {
            sender.sendMessage(Notify.of("<red>ไม่มีสิทธิ์"));
            return true;
        }
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("history")) {
            if (args.length == 0) { sender.sendMessage(Notify.of("<gray>/history <ชื่อ>")); return true; }
            showHistory(sender, args[0]);
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Notify.of("<gray>/" + name + " <ชื่อ> [เวลา เช่น 30m 2h 7d perm] [เหตุผล]"));
            return true;
        }

        String targetName = args[0];
        var offline = Bukkit.getOfflinePlayer(targetName);
        UUID target = offline.getUniqueId();
        if (offline.getName() != null) targetName = offline.getName();

        switch (name) {
            case "unban" -> {
                sender.sendMessage(Notify.of(lift(target, Type.BAN)
                        ? "<green>ปลดแบน <yellow>" + targetName + " <green>แล้ว"
                        : "<gray>คนนี้ไม่ได้ถูกแบนอยู่"));
                return true;
            }
            case "unmute" -> {
                sender.sendMessage(Notify.of(lift(target, Type.MUTE)
                        ? "<green>ปลดปิดปาก <yellow>" + targetName + " <green>แล้ว"
                        : "<gray>คนนี้ไม่ได้ถูกปิดปากอยู่"));
                return true;
            }
            default -> { }
        }

        long duration = 0;
        int reasonFrom = 1;
        if (args.length > 1) {
            long parsed = parseDuration(args[1]);
            if (parsed >= 0) { duration = parsed; reasonFrom = 2; }
        }
        String reason = args.length > reasonFrom
                ? String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length)) : "";

        Type type = switch (name) {
            case "ban" -> Type.BAN;
            case "mute" -> Type.MUTE;
            case "kick" -> Type.KICK;
            case "warn" -> Type.WARN;
            default -> null;
        };
        if (type == null) return false;
        if (type == Type.KICK || type == Type.WARN) {
            Player online = Bukkit.getPlayerExact(targetName);
            if (online == null) { sender.sendMessage(Notify.of("<red>คนนี้ไม่ได้ออนไลน์")); return true; }
        }
        Entry e = punish(target, targetName, type, reason, sender.getName(), duration);
        sender.sendMessage(Notify.of("<green>เรียบร้อย <gray>" + targetName + " · "
                + (e.until < 0 ? "ทันที" : readable(e.until))));
        return true;
    }

    private void showHistory(CommandSender to, String who) {
        var offline = Bukkit.getOfflinePlayer(who);
        UUID id = offline.getUniqueId();
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yy HH:mm");
        to.sendMessage(Notify.of("<gold>ประวัติของ <yellow>" + who));
        int shown = 0;
        for (int i = history.size() - 1; i >= 0 && shown < 15; i--) {
            Entry e = history.get(i);
            if (!e.target.equals(id)) continue;
            shown++;
            String state = e.lifted ? " <dark_gray>(ปลดแล้ว)" : "";
            to.sendMessage(Notify.of("<gray>" + fmt.format(new Date(e.at)) + " <white>" + e.type
                    + " <gray>โดย " + e.staff + " <dark_gray>- <white>" + e.reason + state));
        }
        if (shown == 0) to.sendMessage(Notify.of("<gray>ไม่มีประวัติ"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1)
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    out.add(p.getName());
        else if (args.length == 2)
            out.addAll(List.of("30m", "1h", "6h", "1d", "7d", "perm"));
        return out;
    }

    // ---------------- persistence ----------------

    public void load() {
        history.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("entries");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                Entry e = new Entry();
                e.id = UUID.fromString(key);
                e.target = UUID.fromString(Objects.requireNonNull(root.getString(key + ".target")));
                e.targetName = root.getString(key + ".target-name", "?");
                e.type = Type.valueOf(root.getString(key + ".type", "BAN"));
                e.reason = root.getString(key + ".reason", "");
                e.staff = root.getString(key + ".staff", "?");
                e.at = root.getLong(key + ".at");
                e.until = root.getLong(key + ".until");
                e.lifted = root.getBoolean(key + ".lifted", false);
                history.add(e);
            } catch (Exception ignored) { }
        }
        history.sort(Comparator.comparingLong(a -> a.at));
        plugin.getLogger().info("Loaded " + history.size() + " punishment record(s).");
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Entry e : history) {
            String base = "entries." + e.id;
            cfg.set(base + ".target", e.target.toString());
            cfg.set(base + ".target-name", e.targetName);
            cfg.set(base + ".type", e.type.name());
            cfg.set(base + ".reason", e.reason);
            cfg.set(base + ".staff", e.staff);
            cfg.set(base + ".at", e.at);
            cfg.set(base + ".until", e.until);
            cfg.set(base + ".lifted", e.lifted);
        }
        try { cfg.save(file); dirty = false; } catch (IOException ignored) { }
    }
}
