package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Blocks that do something special when placed.
 *
 * The problem this solves: a beacon that claims land looks exactly like a
 * beacon that does not. Here every special block is a named item carrying a
 * tag in its own data, so a player can tell at a glance, and an ordinary
 * beacon they bought or crafted stays an ordinary beacon.
 */
public class ServerBlocks implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final NamespacedKey key;

    public ServerBlocks(BannarakZones plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "bz_block");
    }

    /** Every block in the game that behaves differently because of this plugin. */
    public enum Kind {
        ZONE("zone", "<aqua><bold>บล็อกจับจอง", List.of(
                "<gray>วางลงพื้นเพื่อ <white>จับจองที่ดิน",
                "<gray>คนอื่นทุบหรือเปิดหีบในเขตไม่ได้",
                "",
                "<gray>ทุบบล็อกนี้ <white>= ยกเลิกเขต",
                "<yellow>/zone <gray>เพื่อตั้งค่าเขต"), "zones.use"),

        ADMIN("admin", "<dark_red><bold>บล็อกทีมงาน", List.of(
                "<gray>วางลงพื้นเพื่อ <white>ล็อกพื้นที่กว้าง",
                "<gray>ใช้กับสปอว์นและพื้นที่ส่วนกลาง",
                "",
                "<dark_gray>ทีมงานเท่านั้น"), "zones.admin");

        public final String id, display, perm;
        public final List<String> lore;
        Kind(String id, String display, List<String> lore, String perm) {
            this.id = id; this.display = display; this.lore = lore; this.perm = perm;
        }
    }

    public Material materialOf(Kind k) {
        return k == Kind.ZONE ? plugin.zoneBlock() : plugin.adminBlock();
    }

    // ---------------- tagging ----------------

    public ItemStack make(Kind k, int amount) {
        ItemStack it = new ItemStack(materialOf(k), Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(k.display).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String s : k.lore) lore.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, k.id);
        it.setItemMeta(meta);
        return it;
    }

    /** Which special block this item is, or null for an ordinary one. */
    public Kind kindOf(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return null;
        for (Kind k : Kind.values()) if (k.id.equals(id)) return k;
        return null;
    }

    /**
     * Whether an untagged block of the right material should still work.
     * Leaving this off means every beacon claims land, tagged or not, which is
     * how the server behaved before these items existed.
     */
    public boolean requireTag() {
        return plugin.getConfig().getBoolean("zone.require-tagged-block", true);
    }

    /** True when placing this item should trigger the special behaviour. */
    public boolean triggers(ItemStack inHand, Kind k) {
        Kind tagged = kindOf(inHand);
        if (tagged != null) return tagged == k;
        return !requireTag();
    }

    public void give(Player p, Kind k, int amount) {
        for (ItemStack left : p.getInventory().addItem(make(k, amount)).values())
            p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // ---------------- the reference menu ----------------

    public class Holder implements InventoryHolder {
        private Inventory inv;
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 27, MM.deserialize("<dark_gray>บล็อกพิเศษของเซิร์ฟ"));
        h.setInventory(inv);

        int slot = 11;
        for (Kind k : Kind.values()) {
            if (slot > 15) break;
            boolean allowed = p.hasPermission(k.perm);
            List<String> lore = new ArrayList<>(k.lore);
            lore.add("");
            lore.add("<dark_gray>บล็อก: " + materialOf(k).name());
            if (allowed) lore.add("<yellow>คลิกเพื่อรับ 1 ชิ้น");
            else lore.add("<red>คุณยังใช้บล็อกนี้ไม่ได้");
            inv.setItem(slot, display(materialOf(k), k.display, lore, allowed));
            slot += 2;
        }

        inv.setItem(22, display(Material.PAPER, "<gray>บล็อกอื่นทำงานปกติ", List.of(
                "<gray>" + (requireTag() ? "บล็อกธรรมดาที่ซื้อหรือคราฟต์เอง"
                                         : "ตอนนี้บล็อกธรรมดาก็ใช้ได้เหมือนกัน"),
                "<gray>" + (requireTag() ? "จะไม่ทำอะไรพิเศษ ใช้ตกแต่งได้เลย"
                                         : "เปิด zone.require-tagged-block เพื่อแยก")), false));
        p.openInventory(inv);
    }

    private ItemStack display(Material mat, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(mat == null ? Material.PAPER : mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        if (glow) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        Kind[] kinds = Kind.values();
        int index = (slot - 11) / 2;
        if (slot < 11 || slot > 15 || (slot - 11) % 2 != 0 || index >= kinds.length) return;
        Kind k = kinds[index];
        if (!p.hasPermission(k.perm)) {
            Notify.bar(p, "<red>คุณยังใช้บล็อกนี้ไม่ได้", "block.note_block.bass", 0.7f);
            return;
        }
        give(p, k, 1);
        Notify.bar(p, "<green>ได้ " + k.display + " <green>แล้ว", "entity.item.pickup", 1.2f);
    }

}
