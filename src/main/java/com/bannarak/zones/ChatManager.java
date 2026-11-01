package com.bannarak.zones;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Everything that happens in chat that is not a rank prefix: private
 * messages, ignore lists, the word filter, spam brakes, the join and
 * leave lines, and the player list header.
 */
public class ChatManager implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern LINK =
            Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    private final BannarakZones plugin;
    private final File file;

    /** who each player last talked to, so /r knows where to go */
    private final Map<UUID, UUID> replyTo = new HashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new HashMap<>();
    private final Set<UUID> spying = new HashSet<>();

    private final Map<UUID, Long> lastChat = new HashMap<>();
    private final Map<UUID, String> lastLine = new HashMap<>();

    private boolean locked = false;
    private boolean dirty = false;

    public ChatManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "chat.yml");
    }

    // ---------------- settings ----------------

    private String txt(String key, String fallback) {
        return plugin.getConfig().getString("chat." + key, fallback);
    }

    private List<String> blockedWords() {
        return plugin.getConfig().getStringList("chat.blocked-words");
    }

    // ---------------- chat pipeline ----------------

    /** Runs after the mute check, before the rank renderer dresses the line. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String raw = PLAIN.serialize(e.message());
        boolean staff = p.hasPermission("zones.chat.bypass") || p.hasPermission("zones.admin");

        if (locked && !staff) {
            e.setCancelled(true);
            Notify.chat(p, txt("locked", "<red>แชตถูกล็อกอยู่ ทีมงานกำลังคุยกัน"));
            return;
        }

        if (!staff) {
            long gap = plugin.getConfig().getLong("chat.cooldown-ms", 1200);
            long now = System.currentTimeMillis();
            Long before = lastChat.get(p.getUniqueId());
            if (before != null && now - before < gap) {
                e.setCancelled(true);
                Notify.bar(p, txt("too-fast", "<red>พิมพ์ช้าลงหน่อย"), "block.note_block.bass", 0.8f);
                return;
            }
            if (plugin.getConfig().getBoolean("chat.block-repeats", true)
                    && raw.equalsIgnoreCase(lastLine.get(p.getUniqueId()))) {
                e.setCancelled(true);
                Notify.bar(p, txt("repeat", "<red>พูดซ้ำเดิมไม่ได้"), "block.note_block.bass", 0.8f);
                return;
            }
            lastChat.put(p.getUniqueId(), now);
            lastLine.put(p.getUniqueId(), raw);

            if (plugin.getConfig().getBoolean("chat.block-links", true)
                    && !p.hasPermission("zones.chat.link")
                    && LINK.matcher(raw).find()) {
                e.setCancelled(true);
                Notify.chat(p, txt("no-links", "<red>ส่งลิงก์ในแชตไม่ได้"));
                return;
            }

            String hit = firstBlockedWord(raw);
            if (hit != null) {
                e.setCancelled(true);
                Notify.chat(p, txt("filtered", "<red>คำนี้ใช้ในแชตไม่ได้"));
                for (Player staffer : Bukkit.getOnlinePlayers())
                    if (staffer.hasPermission("zones.admin"))
                        Notify.chat(staffer, "<dark_gray>[กรอง] <gray>" + p.getName()
                                + " <dark_gray>พยายามพูด: <white>" + raw);
                plugin.getLogger().info("[chat filter] " + p.getName() + ": " + raw + " (คำ: " + hit + ")");
                return;
            }
        }

        // people who chose not to hear this player simply do not receive the line
        Set<UUID> hidden = new HashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers())
            if (ignores(viewer.getUniqueId(), p.getUniqueId())) hidden.add(viewer.getUniqueId());
        if (!hidden.isEmpty()) e.viewers().removeIf(v -> v instanceof Player pl && hidden.contains(pl.getUniqueId()));
    }

    /** Catches the word even when it is spaced or padded out: "โ ก ง" or "โกooง". */
    private String firstBlockedWord(String raw) {
        String squashed = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s._\\-*+]", "");
        for (String word : blockedWords()) {
            String w = word.toLowerCase(Locale.ROOT).trim();
            if (!w.isEmpty() && squashed.contains(w)) return word;
        }
        return null;
    }

    // ---------------- join, leave, player list ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getConfig().getBoolean("chat.join-messages", true)) {
            e.joinMessage(null);
        } else if (!p.hasPlayedBefore()) {
            e.joinMessage(MM.deserialize(txt("first-join",
                    "<gold>✦ <white><player> <gray>เข้าเซิร์ฟครั้งแรก ต้อนรับหน่อย!")
                    .replace("<player>", p.getName())));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Notify.popup(p, "<gold>BANNARAKX", "<gray>ยินดีต้อนรับ", "ui.toast.challenge_complete", 1.1f);
            }, 25L);
        } else {
            e.joinMessage(MM.deserialize(txt("join",
                    "<dark_gray>[<green>+<dark_gray>] <gray><player>").replace("<player>", p.getName())));
        }
        refreshTab(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        e.quitMessage(plugin.getConfig().getBoolean("chat.join-messages", true)
                ? MM.deserialize(txt("quit", "<dark_gray>[<red>-<dark_gray>] <gray><player>")
                    .replace("<player>", p.getName()))
                : null);
        lastChat.remove(p.getUniqueId());
        lastLine.remove(p.getUniqueId());
        replyTo.remove(p.getUniqueId());
    }

    /** Called on a timer so prefixes and the online count stay current. */
    public void refreshTab(Player p) {
        if (!plugin.getConfig().getBoolean("chat.tab-list", true)) return;
        var rank = plugin.ranks().rankOf(p.getUniqueId());
        String prefix = rank == null ? "" : rank.prefix;
        p.playerListName(MM.deserialize(prefix + "<white>" + p.getName()));

        String header = String.join("<newline>", plugin.getConfig().getStringList("chat.tab-header"));
        String footer = String.join("<newline>", plugin.getConfig().getStringList("chat.tab-footer"));
        if (header.isBlank() && footer.isBlank()) return;
        String online = String.valueOf(Bukkit.getOnlinePlayers().size());
        p.sendPlayerListHeaderAndFooter(
                MM.deserialize(fill(header, p, online)),
                MM.deserialize(fill(footer, p, online)));
    }

    private String fill(String s, Player p, String online) {
        return s.replace("<player>", p.getName())
                .replace("<online>", online)
                .replace("<max>", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("<money>", String.valueOf(Math.round(plugin.money().get(p.getUniqueId()))));
    }

    public void startTabTimer() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) refreshTab(p);
        }, 100L, 100L);
    }

    // ---------------- ignore list ----------------

    public boolean ignores(UUID viewer, UUID speaker) {
        if (viewer.equals(speaker)) return false;
        Set<UUID> set = ignored.get(viewer);
        return set != null && set.contains(speaker);
    }

    private boolean toggleIgnore(UUID viewer, UUID other) {
        Set<UUID> set = ignored.computeIfAbsent(viewer, k -> new HashSet<>());
        dirty = true;
        if (set.remove(other)) { if (set.isEmpty()) ignored.remove(viewer); return false; }
        set.add(other);
        return true;
    }

    // ---------------- private messages ----------------

    private void whisper(Player from, Player to, String text) {
        if (ignores(to.getUniqueId(), from.getUniqueId()) && !from.hasPermission("zones.admin")) {
            Notify.chat(from, "<red>คนนี้ปิดรับข้อความจากคุณ");
            return;
        }
        if (plugin.punishments().isMuted(from.getUniqueId())) {
            Notify.chat(from, "<red>คุณถูกปิดปากอยู่");
            return;
        }
        String out = txt("whisper-out", "<dark_gray>[<gray>คุณ <dark_gray>→ <gray><to><dark_gray>] <white><message>");
        String in = txt("whisper-in", "<dark_gray>[<gray><from> <dark_gray>→ <gray>คุณ<dark_gray>] <white><message>");
        from.sendMessage(MM.deserialize(out.replace("<to>", to.getName()).replace("<message>", text)));
        to.sendMessage(MM.deserialize(in.replace("<from>", from.getName()).replace("<message>", text)));
        to.playSound(to.getLocation(), "entity.experience_orb.pickup", 0.5f, 1.6f);

        replyTo.put(from.getUniqueId(), to.getUniqueId());
        replyTo.put(to.getUniqueId(), from.getUniqueId());

        for (UUID id : spying) {
            Player spy = Bukkit.getPlayer(id);
            if (spy == null || spy.equals(from) || spy.equals(to)) continue;
            Notify.chat(spy, "<dark_gray>[สอด] <gray>" + from.getName() + " → "
                    + to.getName() + ": <white>" + text);
        }
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("chat")) return adminChat(sender, args);

        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }

        switch (name) {
            case "msg" -> {
                if (args.length < 2) { Notify.chat(p, "<gray>ใช้ <white>/msg <ชื่อ> <ข้อความ>"); return true; }
                Player to = Bukkit.getPlayerExact(args[0]);
                if (to == null) { Notify.chat(p, "<red>ไม่เจอคนนี้ในเซิร์ฟ"); return true; }
                if (to.equals(p)) { Notify.chat(p, "<red>คุยกับตัวเองไม่ได้"); return true; }
                whisper(p, to, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "r" -> {
                if (args.length == 0) { Notify.chat(p, "<gray>ใช้ <white>/r <ข้อความ>"); return true; }
                UUID back = replyTo.get(p.getUniqueId());
                Player to = back == null ? null : Bukkit.getPlayer(back);
                if (to == null) { Notify.chat(p, "<red>ไม่มีใครให้ตอบ"); return true; }
                whisper(p, to, String.join(" ", args));
            }
            case "ignore" -> {
                if (args.length == 0) {
                    Set<UUID> set = ignored.get(p.getUniqueId());
                    if (set == null || set.isEmpty()) { Notify.chat(p, "<gray>คุณไม่ได้บล็อกใคร"); return true; }
                    StringBuilder sb = new StringBuilder("<gray>บล็อกอยู่: <white>");
                    for (UUID id : set) sb.append(plugin.money().nameOf(id)).append(" ");
                    Notify.chat(p, sb.toString());
                    return true;
                }
                var target = Bukkit.getOfflinePlayer(args[0]);
                if (target.getUniqueId().equals(p.getUniqueId())) {
                    Notify.chat(p, "<red>บล็อกตัวเองไม่ได้"); return true;
                }
                boolean on = toggleIgnore(p.getUniqueId(), target.getUniqueId());
                Notify.chat(p, on ? "<yellow>บล็อก <white>" + args[0] + " <yellow>แล้ว"
                                  : "<green>เลิกบล็อก <white>" + args[0] + " <green>แล้ว");
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean adminChat(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zones.admin.chat")) {
            sender.sendMessage("§cคำสั่งนี้สำหรับทีมงานเท่านั้น"); return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "clear" -> {
                Component blank = Component.empty();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("zones.admin")) continue;
                    for (int i = 0; i < 80; i++) p.sendMessage(blank);
                }
                Bukkit.broadcast(Notify.of("<gold>[แชต] <gray>ทีมงานล้างแชตแล้ว"));
            }
            case "lock" -> {
                locked = !locked;
                Bukkit.broadcast(Notify.of(locked
                        ? "<gold>[แชต] <red>แชตถูกล็อก"
                        : "<gold>[แชต] <green>แชตเปิดแล้ว"));
            }
            case "spy" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                boolean on = spying.contains(p.getUniqueId());
                if (on) spying.remove(p.getUniqueId()); else spying.add(p.getUniqueId());
                Notify.chat(p, on ? "<gray>ปิดการสอดแชตส่วนตัว" : "<yellow>เปิดการสอดแชตส่วนตัว");
            }
            default -> {
                sender.sendMessage("§7/chat clear §8· §7/chat lock §8· §7/chat spy");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("chat")) {
            return args.length == 1 ? List.of("clear", "lock", "spy") : List.of();
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                    out.add(p.getName());
            return out;
        }
        return List.of();
    }

    // ---------------- persistence ----------------

    public void load() {
        ignored.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        var section = yml.getConfigurationSection("ignored");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                Set<UUID> set = new HashSet<>();
                for (String id : section.getStringList(key)) set.add(UUID.fromString(id));
                if (!set.isEmpty()) ignored.put(UUID.fromString(key), set);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("chat.yml: ข้ามรายการที่อ่านไม่ออก (" + key + ")");
            }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (var e : ignored.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (UUID id : e.getValue()) ids.add(id.toString());
            yml.set("ignored." + e.getKey(), ids);
        }
        try {
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "บันทึก chat.yml ไม่ได้", ex);
        }
    }
}
