package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

/**
 * Points: the currency people buy with real money on the website.
 *
 * Kept completely apart from in-game money on purpose. Points buy looks only -
 * skins, name colours, pets. They never buy money, items or any advantage,
 * because Minecraft's own rules do not allow selling a competitive edge, and
 * because an economy where items come from other players stops working the
 * moment somebody can buy their way past it.
 */
public class PointsManager implements CommandExecutor, TabCompleter {

    private final BannarakZones plugin;
    private final File file;
    private final Map<UUID, Long> balances = new HashMap<>();
    /** newest first, capped */
    private final List<String> log = new ArrayList<>();
    /** payment references already credited, so a retried webhook cannot pay twice */
    private final LinkedHashSet<String> references = new LinkedHashSet<>();
    private boolean dirty = false;

    public PointsManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "points.yml");
    }

    public long get(UUID id) { return balances.getOrDefault(id, 0L); }

    public void add(UUID id, long amount) {
        if (amount == 0) return;
        balances.merge(id, amount, Long::sum);
        dirty = true;
    }

    public boolean take(UUID id, long amount) {
        if (get(id) < amount) return false;
        balances.put(id, get(id) - amount);
        dirty = true;
        return true;
    }

    public void set(UUID id, long amount) {
        balances.put(id, Math.max(0, amount));
        dirty = true;
    }

    /** Read-only view for the web panel. */
    public Map<UUID, Long> balances() { return Collections.unmodifiableMap(balances); }

    public String format(long v) { return String.format(Locale.US, "%,d", v); }

    /** Writes a line anyone can read back with /points log - topups must be auditable. */
    public void record(String what) {
        log.add(0, new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()) + " " + what);
        while (log.size() > 500) log.remove(log.size() - 1);
        dirty = true;
    }

    public List<String> log() { return log; }

    public boolean seenReference(String ref) { return references.contains(ref); }

    /** Called by the website through the web panel once a payment really cleared. */
    public synchronized boolean topup(UUID id, String name, long points, String reference) {
        if (points <= 0) return false;
        if (reference != null && !reference.equals("-")) {
            if (!references.add(reference)) return false;
            while (references.size() > 5000) {
                var it = references.iterator(); it.next(); it.remove();
            }
        }
        add(id, points);
        record("เติม " + format(points) + " P ให้ " + name + " อ้างอิง " + reference);
        save(true);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            Notify.bar(p, "<gold>ได้รับ <yellow>" + format(points) + " P <gold>แล้ว",
                    "entity.player.levelup", 1.3f);
            Notify.chat(p, "<gray>ยอดพ้อยตอนนี้ <yellow>" + format(get(id)) + " P <dark_gray>· /pshop");
        }
        return true;
    }

    // ---------------- storage ----------------

    public void load() {
        balances.clear();
        log.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection b = cfg.getConfigurationSection("balances");
        if (b != null) for (String k : b.getKeys(false)) {
            try { balances.put(UUID.fromString(k), b.getLong(k)); } catch (Exception ignored) { }
        }
        log.addAll(cfg.getStringList("log"));
        references.addAll(cfg.getStringList("references"));
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) cfg.set("balances." + e.getKey(), e.getValue());
        cfg.set("log", log);
        cfg.set("references", new ArrayList<>(references));
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save points.yml", ex);
        }
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("topup")) {
            if (!(sender instanceof Player p)) return true;
            String url = plugin.getConfig().getString("points.topup-url", "");
            Notify.chat(p, "<gold>เติมพ้อย");
            if (url.isBlank()) Notify.chat(p, "<gray>ทีมงานยังไม่ได้ตั้งลิงก์เติมเงิน");
            else Notify.chat(p, "<yellow>" + url);
            Notify.chat(p, "<gray>ยอดของคุณตอนนี้ <yellow>" + format(get(p.getUniqueId())) + " P");
            Notify.chat(p, "<dark_gray>พ้อยใช้ซื้อของตกแต่งเท่านั้น ซื้อเงินหรือไอเทมไม่ได้");
            return true;
        }

        // admin side
        if (args.length > 0 && List.of("give", "take", "set", "log").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (!sender.hasPermission("zones.admin.points")) {
                sender.sendMessage("No permission.");
                return true;
            }
            return adminCommand(sender, args);
        }

        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0) {
            Notify.chat(p, "<gold>พ้อยของคุณ <yellow>" + format(get(p.getUniqueId())) + " P");
            Notify.chat(p, "<dark_gray>/pshop ร้านพ้อย · /topup เติมพ้อย");
            return true;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayer(args[0]);
        Notify.chat(p, "<gray>พ้อยของ <yellow>" + args[0] + " <gray>คือ <yellow>"
                + format(get(t.getUniqueId())) + " P");
        return true;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("log")) {
            sender.sendMessage("— ประวัติพ้อย 20 รายการล่าสุด —");
            for (int i = 0; i < Math.min(20, log.size()); i++) sender.sendMessage("  " + log.get(i));
            if (log.isEmpty()) sender.sendMessage("  (ยังไม่มี)");
            return true;
        }
        if (args.length < 3) { sender.sendMessage("/points " + sub + " <ชื่อ> <จำนวน>"); return true; }
        OfflinePlayer t = Bukkit.getOfflinePlayer(args[1]);
        long amount;
        try { amount = Long.parseLong(args[2]); }
        catch (NumberFormatException ex) { sender.sendMessage("ใส่ตัวเลข"); return true; }

        switch (sub) {
            case "give" -> { add(t.getUniqueId(), amount); record("ทีมงาน " + sender.getName()
                    + " ให้ " + format(amount) + " P กับ " + args[1]); }
            case "take" -> {
                if (!take(t.getUniqueId(), amount)) { sender.sendMessage("พ้อยไม่พอ"); return true; }
                record("ทีมงาน " + sender.getName() + " หัก " + format(amount) + " P จาก " + args[1]);
            }
            case "set" -> { set(t.getUniqueId(), amount); record("ทีมงาน " + sender.getName()
                    + " ตั้งพ้อยของ " + args[1] + " เป็น " + format(amount)); }
            default -> { return true; }
        }
        save(true);
        sender.sendMessage("พ้อยของ " + args[1] + " ตอนนี้ " + format(get(t.getUniqueId())) + " P");
        Player online = Bukkit.getPlayer(t.getUniqueId());
        if (online != null) Notify.bar(online, "<gold>พ้อยของคุณถูกปรับเป็น <yellow>"
                + format(get(t.getUniqueId())) + " P", "block.note_block.pling", 1.2f);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("topup")) return out;
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            if (sender.hasPermission("zones.admin.points"))
                for (String s : List.of("give", "take", "set", "log")) if (s.startsWith(pre)) out.add(s);
            for (Player t : Bukkit.getOnlinePlayers())
                if (t.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(t.getName());
        } else if (args.length == 2 && sender.hasPermission("zones.admin.points")) {
            for (Player t : Bukkit.getOnlinePlayers())
                if (t.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    out.add(t.getName());
        }
        return out;
    }
}
