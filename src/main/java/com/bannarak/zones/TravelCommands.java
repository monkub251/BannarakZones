package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/** Homes, warps, spawn and teleport requests. */
public class TravelCommands implements CommandExecutor, TabCompleter, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BannarakZones plugin;
    private final LocStore locs;
    /** target -> requester */
    private final Map<UUID, UUID> requests = new HashMap<>();
    private final Map<UUID, Long> requestTime = new HashMap<>();

    public TravelCommands(BannarakZones plugin) {
        this.plugin = plugin;
        this.locs = plugin.locations();
    }

    public enum Page { HOMES, WARPS, TPA }

    public static class Holder implements InventoryHolder {
        public final Page page;
        private Inventory inv;
        public Holder(Page page) { this.page = page; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- join / respawn ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("spawn.teleport-on-first-join", true)) return;
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) return;
        p.teleport(locs.firstSpawn());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.getConfig().getBoolean("spawn.respawn-at-spawn", false)) return;
        // นอนเตียงหรือตั้ง respawn anchor ไว้แล้ว -> ปล่อยให้เกิดตรงนั้นตามปกติ
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;
        e.setRespawnLocation(locs.deathSpawn());
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("setspawn")) {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("zones.admin.setspawn")) { Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น"); return true; }
            return setSpawnCommand(p, args);
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        switch (name) {
            case "spawn" -> warm(p, locs.spawn(), "<gray>กำลังกลับจุดเกิด");
            case "sethome" -> setHome(p, args);
            case "home" -> home(p, args);
            case "delhome" -> delHome(p, args);
            case "warp" -> warp(p, args);
            case "setwarp" -> setWarp(p, args);
            case "delwarp" -> delWarp(p, args);
            case "tpa" -> tpa(p, args);
            case "tpaccept" -> tpaccept(p);
            case "tpdeny" -> tpdeny(p);
            default -> { return false; }
        }
        return true;
    }

    /**
     * /setspawn            -> main spawn (used by /spawn and as the fallback for the two below)
     * /setspawn first      -> where a new player lands on their very first join
     * /setspawn death      -> where a player respawns after dying without a bed
     * adding "ลบ" / "clear" to first or death removes that point and falls back to the main spawn
     */
    private boolean setSpawnCommand(Player p, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean clear = args.length > 1
                && (args[1].equalsIgnoreCase("clear")
                 || args[1].equalsIgnoreCase("ลบ")
                 || args[1].equalsIgnoreCase("reset"));

        switch (sub) {
            case "" -> {
                locs.setSpawn(p.getLocation());
                locs.save(true);
                Notify.chat(p, "<green>ตั้ง <yellow>จุดเกิดหลัก <green>ตรงที่คุณยืนแล้ว");
                Notify.chat(p, "<gray>ใช้กับ <white>/spawn <gray>และเป็นค่าสำรองของอีกสองจุด");
            }
            case "first", "firstjoin", "join", "แรก", "เข้าครั้งแรก" -> {
                if (clear) {
                    locs.clearFirstSpawn();
                    locs.save(true);
                    Notify.chat(p, "<yellow>ลบจุดเข้าครั้งแรกแล้ว <gray>คนใหม่จะไปจุดเกิดหลักแทน");
                    return true;
                }
                locs.setFirstSpawn(p.getLocation());
                locs.save(true);
                Notify.chat(p, "<green>ตั้ง <yellow>จุดเข้าครั้งแรก <green>ตรงที่คุณยืนแล้ว");
                if (!plugin.getConfig().getBoolean("spawn.teleport-on-first-join", true))
                    Notify.chat(p, "<red>หมายเหตุ: <gray>ตอนนี้ <white>spawn.teleport-on-first-join <gray>ปิดอยู่ จุดนี้จะยังไม่ถูกใช้");
            }
            case "death", "die", "respawn", "ตาย", "เกิดใหม่" -> {
                if (clear) {
                    locs.clearDeathSpawn();
                    locs.save(true);
                    Notify.chat(p, "<yellow>ลบจุดเกิดหลังตายแล้ว <gray>จะไปจุดเกิดหลักแทน");
                    return true;
                }
                locs.setDeathSpawn(p.getLocation());
                locs.save(true);
                Notify.chat(p, "<green>ตั้ง <yellow>จุดเกิดหลังตาย <green>ตรงที่คุณยืนแล้ว");
                Notify.chat(p, "<gray>ใช้เฉพาะคนที่ <white>ไม่ได้นอนเตียง <gray>ไว้");
                if (!plugin.getConfig().getBoolean("spawn.respawn-at-spawn", false))
                    Notify.chat(p, "<red>หมายเหตุ: <gray>ตอนนี้ <white>spawn.respawn-at-spawn <gray>ปิดอยู่ จุดนี้จะยังไม่ถูกใช้");
            }
            case "list", "ดู", "info" -> {
                Notify.chat(p, "<gold>จุดเกิดที่ตั้งไว้");
                Notify.chat(p, "<gray>หลัก: <white>" + describe(locs.spawn()));
                Notify.chat(p, "<gray>เข้าครั้งแรก: <white>"
                        + (locs.hasFirstSpawn() ? describe(locs.firstSpawn()) : "<dark_gray>ยังไม่ตั้ง (ใช้จุดหลัก)"));
                Notify.chat(p, "<gray>หลังตาย: <white>"
                        + (locs.hasDeathSpawn() ? describe(locs.deathSpawn()) : "<dark_gray>ยังไม่ตั้ง (ใช้จุดหลัก)"));
            }
            default -> {
                Notify.chat(p, "<red>ไม่รู้จัก <white>" + sub);
                Notify.chat(p, "<gray>/setspawn <white>· <gray>/setspawn <white>first <gray>· <gray>/setspawn <white>death <gray>· <gray>/setspawn <white>list");
            }
        }
        return true;
    }

    private String describe(Location l) {
        if (l == null || l.getWorld() == null) return "-";
        return l.getWorld().getName() + " "
                + (int) l.getX() + ", " + (int) l.getY() + ", " + (int) l.getZ();
    }

    private void setHome(Player p, String[] args) {
        String name = args.length > 0 ? args[0] : "home";
        Map<String, Location> mine = locs.homesOf(p.getUniqueId());
        int limit = locs.homeLimit(p);
        if (!mine.containsKey(name.toLowerCase(Locale.ROOT)) && mine.size() >= limit) {
            Notify.chat(p, "<red>ตั้งบ้านได้ <yellow>" + limit + " <red>หลัง ลบหลังเก่าก่อน");
            return;
        }
        locs.setHome(p.getUniqueId(), name, p.getLocation());
        locs.save(false);
        Notify.bar(p, "<green>ตั้งบ้าน <yellow>" + name + " <green>แล้ว", "block.note_block.pling", 1.5f);
    }

    private void home(Player p, String[] args) {
        Map<String, Location> mine = locs.homesOf(p.getUniqueId());
        if (mine.isEmpty()) { Notify.chat(p, "<gray>ยังไม่มีบ้าน ใช้ /sethome"); return; }
        if (args.length == 0) {
            if (mine.size() == 1) { warm(p, mine.values().iterator().next(), "<gray>กำลังกลับบ้าน"); return; }
            openHomes(p);
            return;
        }
        Location l = locs.home(p.getUniqueId(), args[0]);
        if (l == null) { Notify.chat(p, "<red>ไม่มีบ้านชื่อนี้"); return; }
        warm(p, l, "<gray>กำลังกลับบ้าน <yellow>" + args[0]);
    }

    private void delHome(Player p, String[] args) {
        if (args.length == 0) { Notify.chat(p, "<gray>ใช้: /delhome <ชื่อ>"); return; }
        if (locs.deleteHome(p.getUniqueId(), args[0])) {
            locs.save(false);
            Notify.bar(p, "<yellow>ลบบ้าน <white>" + args[0] + " <yellow>แล้ว", null, 1f);
        } else Notify.chat(p, "<red>ไม่มีบ้านชื่อนี้");
    }

    private void warp(Player p, String[] args) {
        if (args.length == 0) { openWarps(p); return; }
        Location l = locs.warp(args[0]);
        if (l == null) { Notify.chat(p, "<red>ไม่มีวาร์ปชื่อนี้"); return; }
        warm(p, l, "<gray>กำลังวาร์ปไป <yellow>" + args[0]);
    }

    private void setWarp(Player p, String[] args) {
        if (!p.hasPermission("zones.admin.setwarp")) { Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น"); return; }
        if (args.length == 0) { Notify.chat(p, "<gray>ใช้: /setwarp <ชื่อ> [ICON]"); return; }
        locs.setWarp(args[0], p.getLocation(), args.length > 1 ? args[1].toUpperCase(Locale.ROOT) : null);
        locs.save(true);
        Notify.chat(p, "<green>ตั้งวาร์ป <yellow>" + args[0] + " <green>แล้ว");
    }

    private void delWarp(Player p, String[] args) {
        if (!p.hasPermission("zones.admin.delwarp")) { Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น"); return; }
        if (args.length == 0) { Notify.chat(p, "<gray>ใช้: /delwarp <ชื่อ>"); return; }
        if (locs.deleteWarp(args[0])) { locs.save(true); Notify.chat(p, "<yellow>ลบวาร์ปแล้ว"); }
        else Notify.chat(p, "<red>ไม่มีวาร์ปชื่อนี้");
    }

    // ---------------- teleport requests ----------------

    private void tpa(Player p, String[] args) {
        if (args.length == 0) { openTpa(p); return; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || target.equals(p)) { Notify.chat(p, "<red>ไม่พบผู้เล่นคนนี้"); return; }
        requests.put(target.getUniqueId(), p.getUniqueId());
        requestTime.put(target.getUniqueId(), System.currentTimeMillis());
        Notify.chat(p, "<green>ส่งคำขอไปหา <yellow>" + target.getName() + " <gray>แล้ว");
        Notify.chat(target, "<yellow>" + p.getName() + " <white>ขอวาร์ปมาหาคุณ <gray>- <green>/tpaccept <gray>หรือ <red>/tpdeny");
        Notify.bar(target, "<yellow>" + p.getName() + " <white>ขอวาร์ปมาหา", "block.note_block.bell", 1.2f);
    }

    private void tpaccept(Player p) {
        UUID from = requests.remove(p.getUniqueId());
        long when = requestTime.getOrDefault(p.getUniqueId(), 0L);
        requestTime.remove(p.getUniqueId());
        int ttl = plugin.getConfig().getInt("teleport.request-seconds", 60);
        if (from == null || System.currentTimeMillis() - when > ttl * 1000L) {
            Notify.chat(p, "<gray>ไม่มีคำขอค้างอยู่");
            return;
        }
        Player other = Bukkit.getPlayer(from);
        if (other == null) { Notify.chat(p, "<red>คนขอออกจากเซิร์ฟไปแล้ว"); return; }
        Notify.chat(p, "<green>รับคำขอแล้ว");
        warm(other, p.getLocation(), "<gray>กำลังวาร์ปไปหา <yellow>" + p.getName());
    }

    private void tpdeny(Player p) {
        UUID from = requests.remove(p.getUniqueId());
        requestTime.remove(p.getUniqueId());
        Notify.chat(p, "<yellow>ปฏิเสธคำขอแล้ว");
        if (from != null) {
            Player other = Bukkit.getPlayer(from);
            if (other != null) Notify.chat(other, "<red>" + p.getName() + " ปฏิเสธคำขอวาร์ป");
        }
    }

    // ---------------- warm-up ----------------

    public void warm(Player p, Location dest, String label) {
        if (dest == null) { Notify.chat(p, "<red>ไปที่นั่นไม่ได้"); return; }
        int seconds = plugin.getConfig().getInt("teleport.warmup-seconds", 3);
        if (seconds <= 0 || p.hasPermission("zones.admin")) { doTeleport(p, dest); return; }
        final Location start = p.getLocation().clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            int left = seconds;
            @Override public void run() {
                if (!p.isOnline()) { cancel(); return; }
                if (p.getLocation().distanceSquared(start) > 1.5) {
                    Notify.bar(p, "<red>ยกเลิก เพราะขยับตัว", "block.note_block.bass", 0.8f);
                    cancel();
                    return;
                }
                if (left <= 0) {
                    doTeleport(p, dest);
                    cancel();
                    return;
                }
                Notify.bar(p, label + " <white>" + left + "...", "block.note_block.hat", 1.4f);
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void doTeleport(Player p, Location dest) {
        p.teleport(dest);
        Notify.bar(p, "<green>ถึงแล้ว", "entity.enderman.teleport", 1.4f);
        p.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, dest, 40, 0.4, 0.8, 0.4, 0.4);
    }

    // ---------------- menus ----------------

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat == null ? Material.PAPER : mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    public void openHomes(Player p) {
        Holder holder = new Holder(Page.HOMES);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<dark_gray>บ้านของฉัน"));
        holder.setInventory(inv);
        int slot = 0;
        for (Map.Entry<String, Location> e : locs.homesOf(p.getUniqueId()).entrySet()) {
            if (slot > 26) break;
            Location l = e.getValue();
            inv.setItem(slot++, item(Material.RED_BED, "<yellow>" + e.getKey(), List.of(
                    "<gray>" + (l.getWorld() == null ? "?" : l.getWorld().getName())
                            + " <dark_gray>" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(),
                    "", "<green>คลิกเพื่อกลับบ้าน")));
        }
        p.openInventory(inv);
    }

    public void openWarps(Player p) {
        Holder holder = new Holder(Page.WARPS);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<dark_gray>วาร์ป"));
        holder.setInventory(inv);
        int slot = 0;
        for (String name : locs.warpNames()) {
            if (slot > 53) break;
            Material icon = Material.matchMaterial(locs.warpIcon(name));
            inv.setItem(slot++, item(icon, "<aqua>" + name, List.of("<green>คลิกเพื่อวาร์ป")));
        }
        if (slot == 0) inv.setItem(22, item(Material.BARRIER, "<gray>ยังไม่มีวาร์ป", List.of()));
        p.openInventory(inv);
    }

    public void openTpa(Player p) {
        Holder holder = new Holder(Page.TPA);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<dark_gray>ขอวาร์ปหาเพื่อน"));
        holder.setInventory(inv);
        int slot = 0;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p) || slot > 53) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(other);
            meta.displayName(MM.deserialize("<yellow>" + other.getName()).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(MM.deserialize("<green>คลิกเพื่อขอวาร์ป").decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }
        if (slot == 0) inv.setItem(22, item(Material.BARRIER, "<gray>ไม่มีใครออนไลน์", List.of()));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR || it.getType() == Material.BARRIER) return;
        String label = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(Objects.requireNonNull(it.getItemMeta().displayName()));
        p.closeInventory();
        switch (holder.page) {
            case HOMES -> home(p, new String[]{label});
            case WARPS -> warp(p, new String[]{label});
            case TPA -> tpa(p, new String[]{label});
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p)) return out;
        if (args.length == 2 && cmd.getName().equalsIgnoreCase("setspawn")) {
            String s1 = args[0].toLowerCase(Locale.ROOT);
            if ((s1.equals("first") || s1.equals("death")) && p.hasPermission("zones.admin.setspawn"))
                for (String n : List.of("clear")) if (n.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(n);
            return out;
        }
        if (args.length != 1) return out;
        String prefix = args[0].toLowerCase(Locale.ROOT);
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "setspawn" -> {
                if (!p.hasPermission("zones.admin.setspawn")) return out;
                for (String n : List.of("first", "death", "list"))
                    if (n.startsWith(prefix)) out.add(n);
            }
            case "home", "delhome" -> {
                for (String n : locs.homesOf(p.getUniqueId()).keySet())
                    if (n.startsWith(prefix)) out.add(n);
            }
            case "warp", "delwarp" -> {
                for (String n : locs.warpNames()) if (n.startsWith(prefix)) out.add(n);
            }
            case "tpa" -> {
                for (Player o : Bukkit.getOnlinePlayers())
                    if (!o.equals(p) && o.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(o.getName());
            }
            default -> { }
        }
        return out;
    }
}
