package com.bannarak.zones;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * The staff toolkit: a stick that reaches across the map, a jail with a
 * timer, and spectating a player from behind their eyes.
 */
public class AdminTools implements Listener, CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;

    private Location jail;
    /** player -> when their sentence ends, 0 for no timer */
    private final Map<UUID, Long> jailed = new HashMap<>();
    /** staff -> where they were before they started spectating */
    private final Map<UUID, Location> spectateReturn = new HashMap<>();
    private final Map<UUID, GameMode> spectateMode = new HashMap<>();

    private boolean dirty = false;

    public AdminTools(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "admintools.yml");
    }

    private NamespacedKey stickKey() { return new NamespacedKey(plugin, "admin_stick"); }

    private int reach() { return plugin.getConfig().getInt("admin-tools.stick-reach", 200); }

    // ---------------- the stick ----------------

    public ItemStack stick() {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<gradient:#f0cf8a:#b8863a><bold>ไม้ทีมงาน</bold></gradient>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                MM.deserialize("<gray>คลิกซ้าย <white>ตีเป้าหมายที่เล็งอยู่")
                        .decoration(TextDecoration.ITALIC, false),
                MM.deserialize("<gray>คลิกขวา <white>วาร์ปไปจุดที่เล็ง")
                        .decoration(TextDecoration.ITALIC, false),
                MM.deserialize("<gray>ย่อ+คลิกซ้าย <white>ฆ่าทันที")
                        .decoration(TextDecoration.ITALIC, false),
                MM.deserialize("<gray>ย่อ+คลิกขวา <white>ดึงเป้าหมายมาหา")
                        .decoration(TextDecoration.ITALIC, false),
                MM.deserialize("<dark_gray>ระยะ " + reach() + " บล็อก")
                        .decoration(TextDecoration.ITALIC, false)));
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(stickKey(), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isStick(ItemStack it) {
        return it != null && it.getType() == Material.STICK && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer()
                     .has(stickKey(), PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = false)
    public void onStick(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isStick(e.getItem())) return;
        if (!p.hasPermission("zones.admin.astick")) return;
        e.setCancelled(true);

        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR
                || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean sneak = p.isSneaking();

        RayTraceResult hit = p.getWorld().rayTrace(
                p.getEyeLocation(), p.getEyeLocation().getDirection(), reach(),
                FluidCollisionMode.NEVER, true, 0.6,
                en -> en != p && en instanceof LivingEntity);

        if (hit == null) {
            Notify.bar(p, "<gray>ไม่เจออะไรในระยะ " + reach() + " บล็อก", null, 1f);
            return;
        }

        Entity target = hit.getHitEntity();

        if (target instanceof LivingEntity living) {
            String who = target instanceof Player pl ? pl.getName() : target.getType().name();
            if (right && sneak) {
                target.teleportAsync(p.getLocation());
                Notify.bar(p, "<aqua>ดึง " + who + " มาแล้ว", "entity.enderman.teleport", 1.2f);
            } else if (right) {
                p.teleportAsync(target.getLocation());
                Notify.bar(p, "<aqua>วาร์ปไปหา " + who, "entity.enderman.teleport", 1.2f);
            } else if (sneak) {
                living.setHealth(0);
                Notify.bar(p, "<red>ฆ่า " + who + " แล้ว", null, 1f);
            } else {
                double dmg = plugin.getConfig().getDouble("admin-tools.stick-damage", 6);
                living.damage(dmg, p);
                living.getWorld().spawnParticle(Particle.CRIT, living.getLocation().add(0, 1, 0),
                        12, 0.3, 0.4, 0.3, 0.05);
                Notify.bar(p, "<yellow>ตี " + who + " <gray>" + Math.round(dmg) + " หน่วย",
                        "entity.player.attack.strong", 1.1f);
            }
            return;
        }

        // Nothing alive in the way, so the stick becomes a travel tool.
        Location where = hit.getHitPosition().toLocation(p.getWorld());
        if (right) {
            p.teleportAsync(where.add(0, 1, 0));
            Notify.bar(p, "<aqua>วาร์ปไป " + where.getBlockX() + ", " + where.getBlockY()
                    + ", " + where.getBlockZ(), "entity.enderman.teleport", 1.3f);
        } else {
            p.getWorld().spawnParticle(Particle.END_ROD, where, 20, 0.2, 0.2, 0.2, 0.02);
            Notify.bar(p, "<gray>เล็งที่ " + where.getBlockX() + ", " + where.getBlockY()
                    + ", " + where.getBlockZ(), null, 1f);
        }
    }

    // ---------------- jail ----------------

    public boolean isJailed(UUID id) {
        Long until = jailed.get(id);
        if (until == null) return false;
        if (until != 0 && until < System.currentTimeMillis()) {
            jailed.remove(id);
            dirty = true;
            return false;
        }
        return true;
    }

    private void sendToJail(Player t, long until, String staff) {
        if (jail == null) return;
        jailed.put(t.getUniqueId(), until);
        dirty = true;
        save(true);
        t.teleportAsync(jail);
        Notify.popup(t, "<red>โดนขัง", until == 0 ? "<gray>ไม่มีกำหนด"
                : "<gray>" + Punishments.readable(until), "block.note_block.bass", 0.7f);
        Bukkit.broadcast(Notify.of("<gold>[ระบบ] <white>" + t.getName()
                + " <gray>ถูก " + staff + " ส่งเข้าห้องขัง"));
    }

    /** Keeps a jailed player inside the cell without freezing them solid. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (jail == null || !isJailed(p.getUniqueId())) return;
        if (e.getTo() == null) return;
        int radius = plugin.getConfig().getInt("admin-tools.jail-radius", 12);
        if (e.getTo().getWorld() != null
                && e.getTo().getWorld().equals(jail.getWorld())
                && e.getTo().distanceSquared(jail) <= (double) radius * radius) return;
        p.teleportAsync(jail);
        Notify.bar(p, "<red>ออกจากห้องขังไม่ได้", null, 1f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (jail == null) return;
        Player p = e.getPlayer();
        if (isJailed(p.getUniqueId()))
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.teleportAsync(jail);
            }, 20L);
    }

    // ---------------- spectate ----------------

    private void startSpectate(Player staff, Player target) {
        spectateReturn.put(staff.getUniqueId(), staff.getLocation());
        spectateMode.put(staff.getUniqueId(), staff.getGameMode());
        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleportAsync(target.getLocation()).thenRun(() ->
                Bukkit.getScheduler().runTask(plugin, () -> staff.setSpectatorTarget(target)));
        Notify.chat(staff, "<gray>กำลังส่องผ่านตา <white>" + target.getName()
                + " <gray>· พิมพ์ <white>/spectate <gray>อีกครั้งเพื่อออก");
    }

    private void stopSpectate(Player staff) {
        staff.setSpectatorTarget(null);
        GameMode back = spectateMode.remove(staff.getUniqueId());
        Location where = spectateReturn.remove(staff.getUniqueId());
        staff.setGameMode(back == null ? GameMode.CREATIVE : back);
        if (where != null) staff.teleportAsync(where);
        Notify.chat(staff, "<green>ออกจากโหมดส่องแล้ว");
    }

    public boolean spectating(UUID id) { return spectateReturn.containsKey(id); }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("zones.admin." + name)) {
            sender.sendMessage("§cคำสั่งนี้สำหรับทีมงานเท่านั้น");
            return true;
        }

        switch (name) {
            case "astick" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                p.getInventory().addItem(stick());
                Notify.chat(p, "<green>ได้ไม้ทีมงานแล้ว <gray>ระยะ " + reach() + " บล็อก");
            }
            case "setjail" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                jail = p.getLocation();
                dirty = true;
                save(true);
                Notify.chat(p, "<green>ตั้งห้องขังตรงนี้แล้ว <gray>รัศมี "
                        + plugin.getConfig().getInt("admin-tools.jail-radius", 12) + " บล็อก");
            }
            case "jail" -> {
                if (jail == null) { sender.sendMessage("§cยังไม่ได้ตั้งห้องขัง ใช้ /setjail ก่อน"); return true; }
                if (args.length == 0) { sender.sendMessage("§7/jail <ชื่อ> [เวลา เช่น 30m 2h perm]"); return true; }
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null) { sender.sendMessage("§cคนนี้ไม่ได้ออนไลน์"); return true; }
                long dur = args.length > 1 ? Punishments.parseDuration(args[1]) : 1_800_000L;
                long until = dur <= 0 ? 0 : System.currentTimeMillis() + dur;
                sendToJail(t, until, sender.getName());
            }
            case "unjail" -> {
                if (args.length == 0) { sender.sendMessage("§7/unjail <ชื่อ>"); return true; }
                UUID id = plugin.punishments().resolve(args[0]);
                if (id == null || jailed.remove(id) == null) {
                    sender.sendMessage("§7คนนี้ไม่ได้ถูกขังอยู่");
                    return true;
                }
                dirty = true;
                save(true);
                Player t = Bukkit.getPlayer(id);
                if (t != null) {
                    Location spawn = plugin.locations().spawn();
                    t.teleportAsync(spawn == null ? Bukkit.getWorlds().get(0).getSpawnLocation() : spawn);
                    Notify.popup(t, "<green>ปล่อยตัวแล้ว", "", "block.note_block.pling", 1.4f);
                }
                sender.sendMessage("§aปล่อย " + args[0] + " แล้ว");
            }
            case "spectate" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                if (spectating(p.getUniqueId())) { stopSpectate(p); return true; }
                if (args.length == 0) { Notify.chat(p, "<gray>ใช้ <white>/spectate <ชื่อ>"); return true; }
                Player t = Bukkit.getPlayerExact(args[0]);
                if (t == null) { Notify.chat(p, "<red>คนนี้ไม่ได้ออนไลน์"); return true; }
                startSpectate(p, t);
            }
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT)))
                out.add(p.getName());
        return out;
    }

    // ---------------- persistence ----------------

    public void load() {
        jailed.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        if (yml.isConfigurationSection("jail")) {
            World w = Bukkit.getWorld(yml.getString("jail.world", ""));
            if (w != null) jail = new Location(w,
                    yml.getDouble("jail.x"), yml.getDouble("jail.y"), yml.getDouble("jail.z"),
                    (float) yml.getDouble("jail.yaw"), (float) yml.getDouble("jail.pitch"));
        }
        var sec = yml.getConfigurationSection("jailed");
        if (sec != null) for (String raw : sec.getKeys(false)) {
            try { jailed.put(UUID.fromString(raw), sec.getLong(raw)); }
            catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("admintools.yml: ข้าม " + raw);
            }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration yml = new YamlConfiguration();
        if (jail != null && jail.getWorld() != null) {
            yml.set("jail.world", jail.getWorld().getName());
            yml.set("jail.x", jail.getX());
            yml.set("jail.y", jail.getY());
            yml.set("jail.z", jail.getZ());
            yml.set("jail.yaw", jail.getYaw());
            yml.set("jail.pitch", jail.getPitch());
        }
        for (var e : jailed.entrySet()) yml.set("jailed." + e.getKey(), e.getValue());
        try {
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "บันทึก admintools.yml ไม่ได้", ex);
        }
    }
}
