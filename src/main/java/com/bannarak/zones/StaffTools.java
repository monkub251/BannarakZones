package com.bannarak.zones;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * The staff toolkit: vanish, god, speed, night vision, invsee, enderchest, wear-a-head.
 * All of it is also reachable from the admin panel, so nothing here needs typing.
 */
public class StaffTools implements CommandExecutor, TabCompleter, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final Set<UUID> vanished = new HashSet<>();
    private final Set<UUID> godded = new HashSet<>();

    public StaffTools(BannarakZones plugin) {
        this.plugin = plugin;
    }

    public boolean isVanished(UUID id) { return vanished.contains(id); }
    public boolean isGod(UUID id) { return godded.contains(id); }
    public Set<UUID> vanished() { return vanished; }

    // ---------------- vanish ----------------

    /** Who is allowed to see vanished staff: anyone holding zones.admin.vanish.see. */
    private boolean canSeeVanished(Player viewer) {
        return viewer.hasPermission("zones.admin.vanish.see") || viewer.hasPermission("zones.admin");
    }

    public void setVanished(Player p, boolean on, boolean announce) {
        if (on) vanished.add(p.getUniqueId()); else vanished.remove(p.getUniqueId());

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            if (on && !canSeeVanished(other)) other.hidePlayer(plugin, p);
            else other.showPlayer(plugin, p);
        }

        // a vanished body should not get in the way
        p.setCollidable(!on);
        p.setSleepingIgnored(on);
        if (on) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        } else {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
        }

        if (announce && plugin.getConfig().getBoolean("staff.fake-join-quit", true)) {
            // make it look like a normal join/leave so players do not notice a watcher
            Bukkit.broadcast(Notify.of(on
                    ? "<yellow>" + p.getName() + " ออกจากเกม"
                    : "<yellow>" + p.getName() + " เข้าร่วมเกม"));
        }
        Notify.bar(p, on ? "<aqua>ล่องหนแล้ว <gray>คนอื่นมองไม่เห็นคุณ" : "<green>เลิกล่องหนแล้ว",
                "block.note_block.pling", on ? 0.8f : 1.4f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player joined = e.getPlayer();
        // hide anyone already vanished from the player who just walked in
        for (UUID id : vanished) {
            Player v = Bukkit.getPlayer(id);
            if (v != null && !canSeeVanished(joined)) joined.hidePlayer(plugin, v);
        }
        // staff who log in vanished stay vanished
        if (vanished.contains(joined.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> setVanished(joined, true, false));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // keep the vanish flag so a reconnect comes back vanished, but drop god
        godded.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())
                && plugin.getConfig().getBoolean("staff.vanish-blocks-pickup", true))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && vanished.contains(p.getUniqueId()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (godded.contains(p.getUniqueId()) || vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ---------------- head ----------------

    /** Puts the target's head on the viewer's own head slot. */
    public void wearHead(Player viewer, OfflinePlayer target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);
        meta.displayName(MM.deserialize("<yellow>หัวของ " + target.getName()));
        head.setItemMeta(meta);
        ItemStack old = viewer.getInventory().getHelmet();
        viewer.getInventory().setHelmet(head);
        if (old != null && old.getType() != Material.AIR) {
            HashMap<Integer, ItemStack> left = viewer.getInventory().addItem(old);
            for (ItemStack it : left.values()) viewer.getWorld().dropItemNaturally(viewer.getLocation(), it);
        }
        Notify.bar(viewer, "<green>ใส่หัวของ <yellow>" + target.getName() + " <green>แล้ว", null, 1f);
    }

    /** Hands over a head item rather than wearing it. */
    public void giveHead(Player viewer, OfflinePlayer target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);
        meta.displayName(MM.deserialize("<yellow>หัวของ " + target.getName()));
        head.setItemMeta(meta);
        HashMap<Integer, ItemStack> left = viewer.getInventory().addItem(head);
        for (ItemStack it : left.values()) viewer.getWorld().dropItemNaturally(viewer.getLocation(), it);
        Notify.bar(viewer, "<green>ได้หัวของ <yellow>" + target.getName() + " <green>แล้ว", null, 1f);
    }

    // ---------------- toggles ----------------

    public void toggleGod(Player p) {
        boolean on = !godded.remove(p.getUniqueId());
        if (on) godded.add(p.getUniqueId());
        p.setInvulnerable(on);
        Notify.bar(p, on ? "<gold>เปิดอมตะแล้ว" : "<gray>ปิดอมตะแล้ว", "block.note_block.pling", on ? 1.6f : 0.8f);
    }

    public void setSpeed(Player p, int level, boolean flying) {
        float v = Math.max(0, Math.min(10, level)) / 10f;
        if (flying) p.setFlySpeed(Math.min(1f, v));
        else p.setWalkSpeed(Math.min(1f, v));
        Notify.bar(p, "<green>ตั้งความเร็ว <yellow>" + level + "<green>/10", null, 1f);
    }

    public void toggleNightVision(Player p) {
        if (p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            Notify.bar(p, "<gray>ปิดมองกลางคืนแล้ว", null, 1f);
        } else {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false));
            Notify.bar(p, "<green>เปิดมองกลางคืนแล้ว", null, 1f);
        }
    }

    public void toggleFly(Player p) {
        p.setAllowFlight(!p.getAllowFlight());
        p.setFlying(p.getAllowFlight());
        Notify.bar(p, p.getAllowFlight() ? "<green>บินได้แล้ว" : "<gray>ปิดการบินแล้ว", null, 1f);
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        switch (name) {
            case "vanish" -> {
                if (!p.hasPermission("zones.admin.vanish")) { deny(p); return true; }
                setVanished(p, !vanished.contains(p.getUniqueId()), true);
            }
            case "god" -> {
                if (!p.hasPermission("zones.admin.god")) { deny(p); return true; }
                toggleGod(p);
            }
            case "speed" -> {
                if (!p.hasPermission("zones.admin.speed")) { deny(p); return true; }
                int lv = 2;
                if (args.length > 0) {
                    try { lv = Integer.parseInt(args[0]); }
                    catch (NumberFormatException ex) { Notify.chat(p, "<red>ใส่ตัวเลข 0-10"); return true; }
                }
                setSpeed(p, lv, p.isFlying());
            }
            case "nv", "nightvision" -> {
                if (!p.hasPermission("zones.admin.nv")) { deny(p); return true; }
                toggleNightVision(p);
            }
            case "invsee" -> {
                if (!p.hasPermission("zones.admin.invsee")) { deny(p); return true; }
                Player t = one(p, args);
                if (t != null) p.openInventory(t.getInventory());
            }
            case "ec", "enderchest" -> {
                if (!p.hasPermission("zones.admin.invsee")) { deny(p); return true; }
                Player t = one(p, args);
                if (t != null) p.openInventory(t.getEnderChest());
            }
            case "head" -> {
                if (!p.hasPermission("zones.admin.head")) { deny(p); return true; }
                if (args.length == 0) { Notify.chat(p, "<red>/head <ชื่อผู้เล่น>"); return true; }
                OfflinePlayer t = Bukkit.getPlayerExact(args[0]);
                if (t == null) t = Bukkit.getOfflinePlayer(args[0]);
                wearHead(p, t);
            }
            default -> { return false; }
        }
        return true;
    }

    private void deny(Player p) { Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น"); }

    private Player one(Player p, String[] args) {
        if (args.length == 0) { Notify.chat(p, "<red>ต้องบอกชื่อผู้เล่นด้วย"); return null; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) Notify.chat(p, "<red>ไม่เจอ <yellow>" + args[0] + " <red>ในเซิร์ฟ");
        return t;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p) || args.length != 1) return out;
        String pre = args[0].toLowerCase(Locale.ROOT);
        if (cmd.getName().equalsIgnoreCase("speed")) {
            for (String s : List.of("1", "2", "3", "5", "10")) if (s.startsWith(pre)) out.add(s);
            return out;
        }
        for (Player t : Bukkit.getOnlinePlayers())
            if (t.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(t.getName());
        return out;
    }
}
