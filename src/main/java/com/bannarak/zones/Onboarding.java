package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The first five minutes, and the way out when something goes wrong.
 *
 * <p>Three small things that have nothing in common except when they matter:
 * the kit a new player starts with, the rules they can read without asking,
 * and a way to reach staff who are not online.
 */
public class Onboarding implements Listener, CommandExecutor {

    private final BannarakZones plugin;
    /** Who has already been given a kit. Kept here, written to players.yml. */
    private final Set<UUID> kitted = new HashSet<>();

    public Onboarding(BannarakZones plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------
    // starter kit
    // ---------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("starter-kit.enabled", true)) return;
        Player p = e.getPlayer();
        if (kitted.contains(p.getUniqueId())) return;
        // Trust our own record over the server's "has played before" flag: that
        // flag is set the moment they connect, so on a laggy first join the kit
        // would be skipped and never come back.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) giveKit(p, false);
        }, 40L);
    }

    /** Hands out the kit. {@code again} skips the once-only check, for /kit by staff. */
    public void giveKit(Player p, boolean again) {
        if (!again && !kitted.add(p.getUniqueId())) return;
        dirty = true;

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("starter-kit.items");
        if (sec == null) return;
        List<ItemStack> give = new ArrayList<>();
        for (String key : sec.getKeys(false)) {
            ConfigurationSection it = sec.getConfigurationSection(key);
            if (it == null) continue;
            Material mat = Material.matchMaterial(it.getString("material", "STONE"));
            if (mat == null) {
                plugin.getLogger().warning("starter-kit: ไม่รู้จักไอเทม " + it.getString("material"));
                continue;
            }
            ItemStack stack = new ItemStack(mat, Math.max(1, it.getInt("amount", 1)));
            String name = it.getString("name");
            if (name != null && !name.isBlank()) {
                ItemMeta meta = stack.getItemMeta();
                meta.displayName(Notify.of(name).decoration(
                        net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                stack.setItemMeta(meta);
            }
            give.add(stack);
        }
        for (ItemStack stack : give) {
            for (ItemStack left : p.getInventory().addItem(stack).values())
                p.getWorld().dropItemNaturally(p.getLocation(), left);
        }

        long money = plugin.getConfig().getLong("starter-kit.money", 0);
        if (money > 0 && plugin.money() != null) {
            plugin.money().add(p.getUniqueId(), money);
            Notify.chat(p, "<gray>ได้เงินเริ่มต้น <gold>" + plugin.money().format(money));
        }
        Notify.popup(p, "<gold><bold>ยินดีต้อนรับ", "<white>ได้ของเริ่มต้นแล้ว เปิดกระเป๋าดูได้เลย",
                "entity.player.levelup", 1.2f);
    }

    public Set<UUID> kitted() { return kitted; }

    // ---------------------------------------------------------------
    // storage — who already had a kit, so a relog never hands out a second
    // ---------------------------------------------------------------

    private boolean dirty = false;

    private java.io.File file() { return new java.io.File(plugin.getDataFolder(), "kit.yml"); }

    public void load() {
        kitted.clear();
        java.io.File f = file();
        if (!f.exists()) return;
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String raw : cfg.getStringList("given")) {
            try { kitted.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        var cfg = new org.bukkit.configuration.file.YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (UUID id : kitted) out.add(id.toString());
        cfg.set("given", out);
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            cfg.save(file());
            dirty = false;
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("เซฟ kit.yml ไม่สำเร็จ: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // commands
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player p)) { sender.sendMessage("ใช้ในเกมเท่านั้น"); return true; }

        switch (name) {
            case "rules" -> rules(p);
            case "ticket" -> ticket(p, a);
            case "repair" -> repair(p, false);
            case "repairall" -> repair(p, true);
            case "kit" -> {
                if (!p.hasPermission("zones.admin")) { Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงาน"); return true; }
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : p;
                if (t == null) { Notify.chat(p, "<red>ไม่พบผู้เล่นชื่อนั้น"); return true; }
                giveKit(t, true);
                Notify.bar(p, "<green>ให้ชุดเริ่มต้นกับ <white>" + t.getName(), null, 1.2f);
            }
            default -> { return false; }
        }
        return true;
    }

    /**
     * Mends what a player is holding, or everything they carry, for money.
     *
     * <p>Priced per point of damage so a nearly-new tool costs almost nothing
     * and a ruined one costs real money — a flat fee would make it cheaper to
     * wear a pickaxe out than to look after it.
     */
    private void repair(Player p, boolean all) {
        double perPoint = plugin.getConfig().getDouble("repair.price-per-damage", 15);
        double multiplier = all ? plugin.getConfig().getDouble("repair.all-multiplier", 1.0) : 1.0;

        List<ItemStack> targets = new ArrayList<>();
        if (all) {
            for (ItemStack it : p.getInventory().getContents()) if (it != null) targets.add(it);
            for (ItemStack it : p.getInventory().getArmorContents()) if (it != null) targets.add(it);
        } else {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() != Material.AIR) targets.add(hand);
        }

        long cost = 0;
        List<ItemStack> fixable = new ArrayList<>();
        for (ItemStack it : targets) {
            if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d)) continue;
            if (!d.hasDamage()) continue;
            cost += Math.round(d.getDamage() * perPoint * multiplier);
            fixable.add(it);
        }
        if (fixable.isEmpty()) {
            Notify.chat(p, all ? "<gray>ของทุกชิ้นยังเต็มหลอดอยู่" : "<gray>ของชิ้นนี้ไม่ต้องซ่อม");
            return;
        }
        if (!plugin.money().take(p.getUniqueId(), cost)) {
            Notify.bar(p, "<red>เงินไม่พอ <gray>ต้องใช้ <gold>" + plugin.money().format(cost),
                    "block.note_block.bass", 0.7f);
            return;
        }
        for (ItemStack it : fixable) {
            var meta = it.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.Damageable d) {
                d.setDamage(0);
                it.setItemMeta(meta);
            }
        }
        plugin.money().save(false);
        Notify.popup(p, "<green>ซ่อมเสร็จแล้ว",
                "<white>" + fixable.size() + " ชิ้น · <gold>" + plugin.money().format(cost),
                "block.anvil_use", 1.2f);
    }

    private void rules(Player p) {
        List<String> lines = plugin.getConfig().getStringList("rules");
        if (lines.isEmpty()) {
            Notify.chat(p, "<gray>ยังไม่ได้เขียนกฎเซิร์ฟ");
            return;
        }
        p.sendMessage(Notify.of("<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        for (String l : lines) p.sendMessage(Notify.of(l));
        p.sendMessage(Notify.of("<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        p.playSound(p.getLocation(), "item.book.page_turn", 1f, 1.2f);
    }

    // ---------------------------------------------------------------
    // tickets
    // ---------------------------------------------------------------

    private void ticket(Player p, String[] a) {
        if (a.length == 0) {
            // No typing on a phone: ask with the same window every other
            // menu uses, then send whatever comes back.
            if (plugin.prompt() != null) {
                plugin.prompt().text(p, "พิมพ์เรื่องที่จะแจ้งแอดมิน", null, msg -> send(p, msg));
            } else {
                Notify.chat(p, "<gray>ใช้ <white>/ticket <เรื่องที่จะแจ้ง>");
            }
            return;
        }
        send(p, String.join(" ", a));
    }

    private void send(Player p, String message) {
        String hook = plugin.getConfig().getString("ticket.discord-webhook", "");
        if (hook == null || hook.isBlank() || !hook.startsWith("https://")) {
            Notify.chat(p, "<red>ระบบแจ้งแอดมินยังไม่ได้ตั้งค่า");
            plugin.getLogger().warning("มีคนใช้ /ticket แต่ยังไม่ได้ตั้ง ticket.discord-webhook");
            return;
        }
        long cooldown = plugin.getConfig().getLong("ticket.cooldown-seconds", 60);
        Long last = lastTicket.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cooldown * 1000) {
            Notify.chat(p, "<gray>เพิ่งแจ้งไปเมื่อกี้ รออีกสักครู่");
            return;
        }
        lastTicket.put(p.getUniqueId(), System.currentTimeMillis());

        String clean = message.length() > 900 ? message.substring(0, 900) + "…" : message;
        String world = p.getWorld().getName();
        String where = p.getLocation().getBlockX() + " " + p.getLocation().getBlockY()
                + " " + p.getLocation().getBlockZ();
        String body = "{\"username\":\"แจ้งเรื่องจากในเกม\",\"content\":"
                + json("**" + p.getName() + "** แจ้งเรื่อง\n> " + clean
                       + "\n`" + world + " " + where + "`") + "}";

        // Off the main thread: a slow Discord must never hold up the server.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = post(hook, body);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (ok) Notify.popup(p, "<green>ส่งให้แอดมินแล้ว",
                        "<gray>รอทีมงานตอบในดิสคอร์ด", "entity.experience_orb.pickup", 1.3f);
                else Notify.chat(p, "<red>ส่งไม่สำเร็จ ลองใหม่อีกครั้ง");
            });
        });
    }

    private final java.util.Map<UUID, Long> lastTicket = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean post(String url, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) plugin.getLogger().warning("ส่ง ticket ไม่สำเร็จ HTTP " + code);
            return code / 100 == 2;
        } catch (Exception ex) {
            plugin.getLogger().warning("ส่ง ticket ไม่สำเร็จ: " + ex.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Just enough escaping for a JSON string, so a quote in chat cannot break the payload. */
    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
                }
            }
        }
        return b.append('"').toString();
    }
}
