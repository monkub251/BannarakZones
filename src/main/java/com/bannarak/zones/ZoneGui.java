package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ZoneGui implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BannarakZones plugin;

    public ZoneGui(BannarakZones plugin) {
        this.plugin = plugin;
    }

    public enum Page { MAIN, MEMBERS, ADD }

    public static class Holder implements InventoryHolder {
        public final UUID zoneId;
        public final Page page;
        private Inventory inv;
        public Holder(UUID zoneId, Page page) { this.zoneId = zoneId; this.page = page; }
        public void setInventory(Inventory inv) { this.inv = inv; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private Zone zoneById(UUID id) {
        for (Zone z : plugin.store().all()) if (z.id.equals(id)) return z;
        return null;
    }

    // ---------------- main page ----------------

    public void openMain(Player p, Zone z) {
        Holder holder = new Holder(z.id, Page.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27,
                MM.deserialize("<dark_gray>เขตของ <gold>" + z.ownerName));
        holder.setInventory(inv);

        inv.setItem(4, item(Material.BEACON, "<gold>เขตป้องกัน", List.of(
                "<gray>เจ้าของ: <white>" + z.ownerName,
                "<gray>รัศมี: <white>" + z.radius + " <gray>(" + (z.radius * 2) + "x" + (z.radius * 2) + ")",
                "<gray>สมาชิก: <white>" + z.members.size(),
                "<gray>พิกัด: <white>" + z.x + ", " + z.y + ", " + z.z)));

        int next = plugin.nextRadius(z.radius);
        if (next < 0) {
            inv.setItem(11, item(Material.BARRIER, "<yellow>ขยายเขต", List.of("<gray>ขยายสุดแล้ว")));
        } else {
            double price = plugin.radiusPrice(next);
            inv.setItem(11, item(Material.EMERALD, "<green>ขยายเขต", List.of(
                    "<gray>รัศมี <white>" + z.radius + " <gray>-> <yellow>" + next,
                    "<gray>ราคา <gold>" + BannarakZones.money(price) + " <gray>เหรียญ",
                    "",
                    "<yellow>คลิกเพื่อซื้อ")));
        }

        int owned = plugin.store().zonesOf(z.owner).size();
        int slots = plugin.store().slotsOf(z.owner);
        inv.setItem(12, item(Material.CHEST, "<green>ซื้อช่องเขตเพิ่ม", List.of(
                "<gray>ตอนนี้ใช้ <white>" + owned + "<gray>/<white>" + slots + " <gray>ช่อง",
                "<gray>ราคา <gold>" + BannarakZones.money(plugin.slotPrice(slots)) + " <gray>เหรียญ",
                "",
                "<yellow>คลิกเพื่อซื้อ")));

        inv.setItem(13, item(Material.PLAYER_HEAD, "<aqua>สมาชิก", List.of(
                "<gray>มี <white>" + z.members.size() + " <gray>คน",
                "",
                "<yellow>คลิกเพื่อจัดการ")));

        inv.setItem(14, item(z.allowPvp ? Material.IRON_SWORD : Material.SHIELD,
                (z.allowPvp ? "<red>PvP: เปิด" : "<green>PvP: ปิด"),
                List.of("<gray>คลิกเพื่อสลับ")));

        inv.setItem(15, item(z.allowExplosions ? Material.TNT : Material.GUNPOWDER,
                (z.allowExplosions ? "<red>ระเบิด: เปิด" : "<green>ระเบิด: ปิด"),
                List.of("<gray>คลิกเพื่อสลับ")));

        inv.setItem(20, item(z.showMessages ? Material.PAPER : Material.MAP,
                (z.showMessages ? "<green>ข้อความเข้า/ออก: เปิด" : "<gray>ข้อความเข้า/ออก: ปิด"),
                List.of("<gray>คลิกเพื่อสลับ")));

        inv.setItem(21, item(Material.ENDER_EYE, "<light_purple>ดูขอบเขต",
                List.of("<gray>แสดงเส้นขอบเขตชั่วคราว")));

        if (z.owner.equals(p.getUniqueId()) || plugin.canBypass(p))
            inv.setItem(26, item(Material.BARRIER, "<red>ลบเขตนี้",
                    List.of("<gray>บีคอนจะคืนให้", "<red>กดแล้วหายทันที")));

        p.openInventory(inv);
    }

    // ---------------- members ----------------

    public void openMembers(Player p, Zone z) {
        Holder holder = new Holder(z.id, Page.MEMBERS);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<dark_gray>สมาชิกในเขต"));
        holder.setInventory(inv);
        int slot = 0;
        for (UUID m : z.members) {
            if (slot > 17) break;
            inv.setItem(slot++, head(m, z.memberName(m), List.of("<red>คลิกเพื่อเอาออก")));
        }
        inv.setItem(22, item(Material.LIME_DYE, "<green>เพิ่มสมาชิก", List.of("<gray>เลือกจากคนที่ออนไลน์")));
        inv.setItem(18, item(Material.ARROW, "<white>กลับ", null));
        p.openInventory(inv);
    }

    public void openAdd(Player p, Zone z) {
        Holder holder = new Holder(z.id, Page.ADD);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<dark_gray>เลือกคนที่จะเพิ่ม"));
        holder.setInventory(inv);
        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot > 44) break;
            if (z.isTrusted(online.getUniqueId())) continue;
            inv.setItem(slot++, head(online.getUniqueId(), online.getName(), List.of("<green>คลิกเพื่อเพิ่ม")));
        }
        inv.setItem(49, item(Material.ARROW, "<white>กลับ", null));
        p.openInventory(inv);
    }

    private ItemStack head(UUID uuid, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        meta.setOwningPlayer(op);
        meta.displayName(MM.deserialize("<yellow>" + name).decoration(TextDecoration.ITALIC, false));
        if (lore != null) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        meta.getPersistentDataContainer();
        it.setItemMeta(meta);
        return it;
    }

    // ---------------- clicks ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Zone z = zoneById(holder.zoneId);
        if (z == null) { p.closeInventory(); return; }
        if (!z.isTrusted(p.getUniqueId()) && !plugin.canBypass(p)) { p.closeInventory(); return; }
        boolean owner = z.owner.equals(p.getUniqueId()) || plugin.canBypass(p);

        switch (holder.page) {
            case MAIN -> handleMain(e, p, z, owner);
            case MEMBERS -> handleMembers(e, p, z, owner);
            case ADD -> handleAdd(e, p, z, owner);
        }
    }

    private void handleMain(InventoryClickEvent e, Player p, Zone z, boolean owner) {
        switch (e.getRawSlot()) {
            case 11 -> {
                if (!owner) { plugin.msg(p, "not-owner"); return; }
                buyRadius(p, z);
            }
            case 12 -> {
                if (!owner) { plugin.msg(p, "not-owner"); return; }
                buySlot(p, z);
            }
            case 13 -> openMembers(p, z);
            case 14 -> {
                if (!owner) return;
                z.allowPvp = !z.allowPvp;
                plugin.store().save();
                openMain(p, z);
            }
            case 15 -> {
                if (!owner) return;
                z.allowExplosions = !z.allowExplosions;
                plugin.store().save();
                openMain(p, z);
            }
            case 20 -> {
                if (!owner) return;
                z.showMessages = !z.showMessages;
                plugin.store().save();
                openMain(p, z);
            }
            case 21 -> {
                p.closeInventory();
                plugin.showBorder(p, z);
            }
            case 26 -> {
                if (!owner) return;
                plugin.store().remove(z);
                plugin.store().save();
                p.closeInventory();
                plugin.msg(p, "removed");
            }
            default -> { }
        }
    }

    private void handleMembers(InventoryClickEvent e, Player p, Zone z, boolean owner) {
        int slot = e.getRawSlot();
        if (slot == 18) { openMain(p, z); return; }
        if (slot == 22) { openAdd(p, z); return; }
        if (slot < 0 || slot > 17) return;
        if (!owner) { plugin.msg(p, "not-owner"); return; }
        List<UUID> list = new ArrayList<>(z.members);
        if (slot >= list.size()) return;
        UUID target = list.get(slot);
        String name = z.memberName(target);
        z.removeMember(target);
        plugin.store().save();
        plugin.msg(p, "member-removed", BannarakZones.p("player", name));
        openMembers(p, z);
    }

    private void handleAdd(InventoryClickEvent e, Player p, Zone z, boolean owner) {
        int slot = e.getRawSlot();
        if (slot == 49) { openMembers(p, z); return; }
        if (!owner) { plugin.msg(p, "not-owner"); return; }
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() != Material.PLAYER_HEAD) return;
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        OfflinePlayer op = meta.getOwningPlayer();
        if (op == null) return;
        z.addMember(op.getUniqueId(), op.getName() == null ? "?" : op.getName());
        plugin.store().save();
        plugin.msg(p, "member-added", BannarakZones.p("player", op.getName() == null ? "?" : op.getName()));
        openMembers(p, z);
    }

    // ---------------- purchases ----------------

    private void buyRadius(Player p, Zone z) {
        int next = plugin.nextRadius(z.radius);
        if (next < 0) { plugin.msg(p, "max-radius"); return; }
        if (plugin.economy() == null) { plugin.msg(p, "no-economy"); return; }
        double price = plugin.radiusPrice(next);
        if (!plugin.economy().has(p, price)) {
            plugin.msg(p, "no-money", BannarakZones.p("price", BannarakZones.money(price)));
            return;
        }
        if (plugin.store().wouldOverlap(z.world, z.x, z.z, next, z.id, plugin.minGap())) {
            plugin.msg(p, "overlap");
            return;
        }
        plugin.economy().withdrawPlayer(p, price);
        z.radius = next;
        plugin.store().reindex(z);
        plugin.store().save();
        plugin.msg(p, "upgraded", BannarakZones.p("radius", String.valueOf(next)));
        plugin.playSound(p, "entity.player.levelup", 1.4f);
        p.closeInventory();
        plugin.showBorder(p, z);
    }

    private void buySlot(Player p, Zone z) {
        if (plugin.economy() == null) { plugin.msg(p, "no-economy"); return; }
        int slots = plugin.store().slotsOf(p.getUniqueId());
        double price = plugin.slotPrice(slots);
        if (!plugin.economy().has(p, price)) {
            plugin.msg(p, "no-money", BannarakZones.p("price", BannarakZones.money(price)));
            return;
        }
        plugin.economy().withdrawPlayer(p, price);
        plugin.store().setSlots(p.getUniqueId(), slots + 1);
        plugin.store().save();
        plugin.msg(p, "slot-bought", BannarakZones.p("slots", String.valueOf(slots + 1)));
        plugin.playSound(p, "entity.player.levelup", 1.0f);
        openMain(p, z);
    }
}
