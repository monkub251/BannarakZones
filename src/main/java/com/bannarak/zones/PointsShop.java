package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * The shop points are spent in. Three pages: colours, pets, and whatever the
 * owner wires up through commands (a skin, for instance).
 *
 * Nothing here hands out money or items - see PointsManager for why.
 */
public class PointsShop implements CommandExecutor, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;

    public PointsShop(BannarakZones plugin) { this.plugin = plugin; }

    public enum Page { MAIN, COLOURS, PETS, EXTRAS }

    public static class Holder implements InventoryHolder {
        public final Page page;
        private Inventory inv;
        public Holder(Page page) { this.page = page; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    private ItemStack item(Material mat, String name, List<String> lore, boolean glow) {
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

    private Material mat(String s, Material fallback) {
        Material m = s == null ? null : Material.matchMaterial(s);
        return m == null ? fallback : m;
    }

    // ---------------- pages ----------------

    public void openMain(Player p) {
        Holder h = new Holder(Page.MAIN);
        Inventory inv = Bukkit.createInventory(h, 27, MM.deserialize("<dark_gray>ร้านพ้อย"));
        h.setInventory(inv);

        inv.setItem(11, item(Material.NAME_TAG, "<light_purple>สีชื่อ",
                List.of("<gray>เปลี่ยนสีชื่อในแชทและข้างจอ", "", "<green>คลิกเพื่อเปิด"), false));
        inv.setItem(13, item(Material.BONE, "<yellow>สัตว์เลี้ยง",
                List.of("<gray>เดินตามคุณไปทุกที่", "<dark_gray>ตกแต่งอย่างเดียว ตีไม่ได้", "", "<green>คลิกเพื่อเปิด"), false));
        inv.setItem(15, item(Material.CHEST, "<aqua>อื่น ๆ",
                List.of("<gray>สกินและของตกแต่งอื่น", "", "<green>คลิกเพื่อเปิด"), false));

        balance(inv, p, 26);
        inv.setItem(22, item(Material.GOLD_NUGGET, "<gold>เติมพ้อย",
                List.of("<gray>พิมพ์ <white>/topup"), false));
        p.openInventory(inv);
    }

    private void balance(Inventory inv, Player p, int slot) {
        inv.setItem(slot, item(Material.SUNFLOWER, "<gold>พ้อยของคุณ",
                List.of("<yellow>" + plugin.points().format(plugin.points().get(p.getUniqueId())) + " P"), true));
    }

    public void openColours(Player p) {
        Holder h = new Holder(Page.COLOURS);
        Inventory inv = Bukkit.createInventory(h, 54, MM.deserialize("<dark_gray>ร้านพ้อย <gray>· สีชื่อ"));
        h.setInventory(inv);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("cosmetics.colours");
        int slot = 0;
        if (root != null) for (String key : root.getKeys(false)) {
            if (slot > 44) break;
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            long price = s.getLong("price", 0);
            boolean owned = plugin.cosmetics().ownsColour(p.getUniqueId(), key);
            boolean active = key.equals(plugin.cosmetics().activeColourKey(p.getUniqueId()));
            String tag = s.getString("tag", "<white>");
            List<String> lore = new ArrayList<>();
            lore.add("<gray>ตัวอย่าง " + tag + p.getName());
            lore.add("");
            if (active) lore.add("<green>กำลังใช้อยู่");
            else if (owned) lore.add("<yellow>คลิกเพื่อใช้");
            else {
                lore.add("<gray>ราคา <yellow>" + plugin.points().format(price) + " P");
                lore.add("<yellow>คลิกเพื่อซื้อ");
            }
            inv.setItem(slot++, item(mat(s.getString("icon"), Material.NAME_TAG),
                    tag + s.getString("display", key), lore, owned));
        }
        if (slot == 0) inv.setItem(22, item(Material.BARRIER, "<gray>ยังไม่มีสีให้เลือก",
                List.of("<dark_gray>ตั้งที่ cosmetics.colours ใน config.yml"), false));
        inv.setItem(45, item(Material.ARROW, "<white>กลับ", List.of(), false));
        inv.setItem(49, item(Material.BARRIER, "<red>เอาสีออก", List.of("<gray>กลับไปใช้ชื่อสีปกติ"), false));
        balance(inv, p, 53);
        p.openInventory(inv);
    }

    public void openPets(Player p) {
        Holder h = new Holder(Page.PETS);
        Inventory inv = Bukkit.createInventory(h, 54, MM.deserialize("<dark_gray>ร้านพ้อย <gray>· สัตว์เลี้ยง"));
        h.setInventory(inv);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("cosmetics.pets");
        int slot = 0;
        if (root != null) for (String key : root.getKeys(false)) {
            if (slot > 44) break;
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            long price = s.getLong("price", 0);
            boolean owned = plugin.cosmetics().ownsPet(p.getUniqueId(), key);
            boolean active = key.equals(plugin.cosmetics().activePetKey(p.getUniqueId()));
            List<String> lore = new ArrayList<>();
            lore.add("<dark_gray>ตกแต่งอย่างเดียว ตีไม่ได้ ช่วยอะไรไม่ได้");
            lore.add("");
            if (active) lore.add("<green>กำลังเรียกออกมาอยู่");
            else if (owned) lore.add("<yellow>คลิกเพื่อเรียกออกมา");
            else {
                lore.add("<gray>ราคา <yellow>" + plugin.points().format(price) + " P");
                lore.add("<yellow>คลิกเพื่อซื้อ");
            }
            inv.setItem(slot++, item(mat(s.getString("icon"), Material.BONE),
                    s.getString("display", key), lore, owned));
        }
        if (slot == 0) inv.setItem(22, item(Material.BARRIER, "<gray>ยังไม่มีสัตว์เลี้ยงให้เลือก",
                List.of("<dark_gray>ตั้งที่ cosmetics.pets ใน config.yml"), false));
        inv.setItem(45, item(Material.ARROW, "<white>กลับ", List.of(), false));
        inv.setItem(49, item(Material.BARRIER, "<red>เก็บสัตว์เลี้ยง", List.of(), false));
        balance(inv, p, 53);
        p.openInventory(inv);
    }

    public void openExtras(Player p) {
        Holder h = new Holder(Page.EXTRAS);
        Inventory inv = Bukkit.createInventory(h, 54, MM.deserialize("<dark_gray>ร้านพ้อย <gray>· อื่น ๆ"));
        h.setInventory(inv);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("cosmetics.extras");
        int slot = 0;
        if (root != null) for (String key : root.getKeys(false)) {
            if (slot > 44) break;
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            List<String> lore = new ArrayList<>(s.getStringList("lore"));
            lore.add("");
            lore.add("<gray>ราคา <yellow>" + plugin.points().format(s.getLong("price", 0)) + " P");
            lore.add("<yellow>คลิกเพื่อซื้อ");
            inv.setItem(slot++, item(mat(s.getString("icon"), Material.CHEST),
                    s.getString("display", key), lore, false));
        }
        if (slot == 0) inv.setItem(22, item(Material.BARRIER, "<gray>ยังไม่มีของในหมวดนี้",
                List.of("<dark_gray>ตั้งที่ cosmetics.extras ใน config.yml"), false));
        inv.setItem(45, item(Material.ARROW, "<white>กลับ", List.of(), false));
        balance(inv, p, 53);
        p.openInventory(inv);
    }

    // ---------------- clicks ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;

        switch (h.page) {
            case MAIN -> {
                switch (slot) {
                    case 11 -> openColours(p);
                    case 13 -> openPets(p);
                    case 15 -> openExtras(p);
                    case 22 -> { p.closeInventory(); p.performCommand("topup"); }
                    default -> { }
                }
            }
            case COLOURS -> {
                if (slot == 45) { openMain(p); return; }
                if (slot == 49) { plugin.cosmetics().setColour(p, null); openColours(p); return; }
                pick(p, "cosmetics.colours", slot, true);
            }
            case PETS -> {
                if (slot == 45) { openMain(p); return; }
                if (slot == 49) { plugin.cosmetics().setPet(p, null); openPets(p); return; }
                pick(p, "cosmetics.pets", slot, false);
            }
            case EXTRAS -> {
                if (slot == 45) { openMain(p); return; }
                extra(p, slot);
            }
        }
    }

    /** Maps a clicked slot back to the key at that position in the config section. */
    private String keyAt(String path, int slot) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection(path);
        if (root == null) return null;
        int i = 0;
        for (String key : root.getKeys(false)) {
            if (root.getConfigurationSection(key) == null) continue;
            if (i == slot) return key;
            i++;
        }
        return null;
    }

    private void pick(Player p, String path, int slot, boolean colour) {
        String key = keyAt(path, slot);
        if (key == null) return;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path + "." + key);
        if (s == null) return;
        UUID id = p.getUniqueId();
        boolean owned = colour ? plugin.cosmetics().ownsColour(id, key) : plugin.cosmetics().ownsPet(id, key);

        if (!owned) {
            long price = s.getLong("price", 0);
            if (!plugin.points().take(id, price)) {
                Notify.bar(p, "<red>พ้อยไม่พอ <gray>ต้องมี <yellow>" + plugin.points().format(price) + " P",
                        "block.note_block.bass", 0.7f);
                return;
            }
            plugin.points().record(p.getName() + " ซื้อ " + key + " ราคา " + plugin.points().format(price) + " P");
            plugin.points().save(false);
            if (colour) plugin.cosmetics().grantColour(id, key);
            else plugin.cosmetics().grantPet(id, key);
            Notify.bar(p, "<green>ซื้อ <yellow>" + s.getString("display", key) + " <green>แล้ว",
                    "entity.player.levelup", 1.4f);
        }
        if (colour) { plugin.cosmetics().setColour(p, key); openColours(p); }
        else { plugin.cosmetics().setPet(p, key); openPets(p); }
        plugin.cosmetics().save(false);
    }

    /** Extras are whatever the owner wired up: each one just runs commands. */
    private void extra(Player p, int slot) {
        String key = keyAt("cosmetics.extras", slot);
        if (key == null) return;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("cosmetics.extras." + key);
        if (s == null) return;
        long price = s.getLong("price", 0);
        if (!plugin.points().take(p.getUniqueId(), price)) {
            Notify.bar(p, "<red>พ้อยไม่พอ <gray>ต้องมี <yellow>" + plugin.points().format(price) + " P",
                    "block.note_block.bass", 0.7f);
            return;
        }
        plugin.points().record(p.getName() + " ซื้อ " + key + " ราคา " + plugin.points().format(price) + " P");
        plugin.points().save(false);
        for (String raw : s.getStringList("commands")) {
            String cmd = raw.replace("%player%", p.getName());
            if (cmd.startsWith("console:")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.substring(8).trim());
            else p.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }
        Notify.bar(p, "<green>ซื้อ <yellow>" + s.getString("display", key) + " <green>แล้ว",
                "entity.player.levelup", 1.4f);
        openExtras(p);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        openMain(p);
        return true;
    }
}
