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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

/** The server's own shop: categories and prices come from shop.yml. */
public class ShopManager implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final Map<String, Category> categories = new LinkedHashMap<>();

    public ShopManager(BannarakZones plugin) {
        this.plugin = plugin;
    }

    public static class Entry {
        Material material;
        double buy = -1;   // -1 means the shop will not sell it
        double sell = -1;  // -1 means the shop will not buy it
        String name;
    }

    public static class Category {
        String id;
        String display;
        Material icon = Material.CHEST;
        int slot = -1;
        final List<Entry> entries = new ArrayList<>();
    }

    public class Holder implements InventoryHolder {
        public final String categoryId;  // null = category list
        private Inventory inv;
        public Holder(String categoryId) { this.categoryId = categoryId; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- loading ----------------

    public void load() {
        categories.clear();
        File f = new File(plugin.getDataFolder(), "shop.yml");
        if (!f.exists()) plugin.saveResource("shop.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("categories");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Category c = new Category();
            c.id = id;
            c.display = s.getString("display", id);
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            if (icon != null) c.icon = icon;
            c.slot = s.getInt("slot", -1);
            ConfigurationSection items = s.getConfigurationSection("items");
            if (items != null) for (String key : items.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) { plugin.getLogger().warning("Shop: unknown item " + key); continue; }
                Entry e = new Entry();
                e.material = m;
                e.buy = items.getDouble(key + ".buy", -1);
                e.sell = items.getDouble(key + ".sell", -1);
                e.name = items.getString(key + ".name");
                c.entries.add(e);
            }
            categories.put(id.toLowerCase(Locale.ROOT), c);
        }
        plugin.getLogger().info("Loaded " + categories.size() + " shop category(ies).");
    }

    // ---------------- menus ----------------

    public void openCategories(Player p) {
        Holder holder = new Holder(null);
        int rows = Math.max(3, (int) Math.ceil(categories.size() / 9.0) + 2);
        Inventory inv = Bukkit.createInventory(holder, Math.min(54, rows * 9),
                MM.deserialize("<dark_gray>ร้านค้า"));
        holder.setInventory(inv);
        int auto = 10;
        for (Category c : categories.values()) {
            int slot = c.slot >= 0 ? c.slot : auto++;
            if (slot >= inv.getSize()) continue;
            inv.setItem(slot, item(c.icon, "<yellow>" + c.display,
                    List.of("<gray>มี <white>" + c.entries.size() + " <gray>รายการ", "", "<green>คลิกเพื่อเปิด")));
        }
        p.openInventory(inv);
    }

    public void openCategory(Player p, String id) {
        Category c = categories.get(id.toLowerCase(Locale.ROOT));
        if (c == null) { Notify.chat(p, "<red>ไม่พบหมวดนี้"); return; }
        Holder holder = new Holder(c.id);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<dark_gray>" + c.display));
        holder.setInventory(inv);
        int slot = 0;
        for (Entry e : c.entries) {
            if (slot > 44) break;
            List<String> lore = new ArrayList<>();
            if (e.buy >= 0) {
                lore.add("<gray>ซื้อ 1 ชิ้น <gold>" + plugin.money().format(e.buy));
                lore.add("<gray>ซื้อ 1 สแต็ก <gold>" + plugin.money().format(e.buy * e.material.getMaxStackSize()));
            }
            if (e.sell >= 0) {
                lore.add("<gray>ขาย 1 ชิ้น <green>" + plugin.money().format(e.sell));
            }
            lore.add("");
            if (e.buy >= 0) lore.add("<yellow>คลิกซ้าย <gray>ซื้อ 1 · <yellow>ชิฟต์+ซ้าย <gray>ซื้อ 1 สแต็ก");
            if (e.sell >= 0) lore.add("<yellow>คลิกขวา <gray>ขาย 1 · <yellow>ชิฟต์+ขวา <gray>ขายทั้งหมด");
            String display = e.name != null ? e.name : "<white>" + pretty(e.material);
            inv.setItem(slot++, item(e.material, display, lore));
        }
        inv.setItem(49, item(Material.ARROW, "<white>กลับ", List.of()));
        inv.setItem(53, item(Material.GOLD_INGOT, "<gold>เงินของคุณ",
                List.of("<yellow>" + plugin.money().format(plugin.money().get(p.getUniqueId())))));
        p.openInventory(inv);
    }

    private static String pretty(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    // ---------------- trading ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (holder.categoryId == null) {
            for (Category c : categories.values())
                if (c.icon == clicked.getType()) { openCategory(p, c.id); return; }
            return;
        }
        if (e.getRawSlot() == 49) { openCategories(p); return; }
        if (e.getRawSlot() == 53) return;

        Category c = categories.get(holder.categoryId.toLowerCase(Locale.ROOT));
        if (c == null) return;
        Entry entry = null;
        for (Entry en : c.entries) if (en.material == clicked.getType()) { entry = en; break; }
        if (entry == null) return;

        ClickType type = e.getClick();
        if (type.isLeftClick()) buy(p, entry, type.isShiftClick() ? entry.material.getMaxStackSize() : 1);
        else if (type.isRightClick()) sell(p, entry, type.isShiftClick() ? -1 : 1);
        refreshBalance(e.getInventory(), p);
    }

    private void refreshBalance(Inventory inv, Player p) {
        if (inv.getSize() >= 54)
            inv.setItem(53, item(Material.GOLD_INGOT, "<gold>เงินของคุณ",
                    List.of("<yellow>" + plugin.money().format(plugin.money().get(p.getUniqueId())))));
    }

    private void buy(Player p, Entry e, int amount) {
        if (e.buy < 0) { Notify.bar(p, "<red>ของชิ้นนี้ไม่ได้วางขาย", "block.note_block.bass", 0.8f); return; }
        double cost = e.buy * amount;
        if (!plugin.money().has(p.getUniqueId(), cost)) {
            Notify.bar(p, "<red>เงินไม่พอ ต้องมี <gold>" + plugin.money().format(cost),
                    "block.note_block.bass", 0.8f);
            return;
        }
        ItemStack stack = new ItemStack(e.material, amount);
        if (p.getInventory().firstEmpty() == -1) {
            Notify.bar(p, "<red>กระเป๋าเต็ม", "block.note_block.bass", 0.8f);
            return;
        }
        plugin.money().take(p.getUniqueId(), cost);
        for (ItemStack rest : p.getInventory().addItem(stack).values())
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        plugin.money().save(false);
        Notify.bar(p, "<green>ซื้อ <white>" + pretty(e.material) + " x" + amount
                + " <gray>- <gold>" + plugin.money().format(cost), "entity.villager.yes", 1.3f);
    }

    private void sell(Player p, Entry e, int amount) {
        if (e.sell < 0) { Notify.bar(p, "<red>ร้านไม่รับซื้อของชิ้นนี้", "block.note_block.bass", 0.8f); return; }
        int have = 0;
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == e.material) have += it.getAmount();
        if (have <= 0) { Notify.bar(p, "<red>ไม่มีของชิ้นนี้ในกระเป๋า", "block.note_block.bass", 0.8f); return; }
        int sellAmount = amount < 0 ? have : Math.min(amount, have);
        p.getInventory().removeItem(new ItemStack(e.material, sellAmount));
        double gain = e.sell * sellAmount;
        plugin.money().add(p.getUniqueId(), gain);
        plugin.money().save(false);
        Notify.bar(p, "<green>ขาย <white>" + pretty(e.material) + " x" + sellAmount
                + " <gray>+ <gold>" + plugin.money().format(gain), "entity.villager.yes", 1.6f);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length > 0) openCategory(p, args[0]);
        else openCategories(p);
        return true;
    }
}
