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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Players sell to players: a shared stall anyone can browse. */
public class MarketManager implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> mailbox = new HashMap<>();
    private boolean dirty = false;

    public MarketManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
    }

    public static class Listing {
        UUID id;
        UUID seller;
        String sellerName;
        ItemStack item;
        double price;
        long listedAt;
    }

    public enum View { BROWSE, MINE, MAILBOX }

    public class Holder implements InventoryHolder {
        public final View view;
        public final int page;
        private Inventory inv;
        public Holder(View view, int page) { this.view = view; this.page = page; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- listing ----------------

    public void sell(Player p, double price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) { Notify.chat(p, "<red>ถือของที่จะขายก่อน"); return; }
        double min = plugin.getConfig().getDouble("market.min-price", 1);
        if (price < min) { Notify.chat(p, "<red>ราคาขั้นต่ำ <gold>" + plugin.money().format(min)); return; }
        int limit = plugin.getConfig().getInt("market.listings-per-player", 5);
        if (countOf(p.getUniqueId()) >= limit) {
            Notify.chat(p, "<red>วางขายได้ไม่เกิน <yellow>" + limit + " <red>รายการ");
            return;
        }
        Listing l = new Listing();
        l.id = UUID.randomUUID();
        l.seller = p.getUniqueId();
        l.sellerName = p.getName();
        l.item = hand.clone();
        l.price = price;
        l.listedAt = System.currentTimeMillis();
        listings.put(l.id, l);
        p.getInventory().setItemInMainHand(null);
        dirty = true;
        save(false);
        Notify.bar(p, "<green>วางขายแล้ว <gray>ราคา <gold>" + plugin.money().format(price),
                "entity.villager.yes", 1.3f);
        if (plugin.getConfig().getBoolean("market.broadcast-listings", false))
            Bukkit.broadcast(Notify.of("<gray>[ตลาด] <yellow>" + p.getName() + " <white>วางขายของ <gold>"
                    + plugin.money().format(price)));
    }

    private int countOf(UUID seller) {
        int n = 0;
        for (Listing l : listings.values()) if (l.seller.equals(seller)) n++;
        return n;
    }

    private void buy(Player buyer, Listing l) {
        if (l.seller.equals(buyer.getUniqueId())) { Notify.bar(buyer, "<red>นี่ของคุณเอง", null, 1f); return; }
        if (!plugin.money().has(buyer.getUniqueId(), l.price)) {
            Notify.bar(buyer, "<red>เงินไม่พอ", "block.note_block.bass", 0.8f);
            return;
        }
        if (listings.remove(l.id) == null) return;
        double tax = plugin.getConfig().getDouble("market.tax-percent", 5) / 100.0;
        double net = l.price * (1 - tax);
        plugin.money().take(buyer.getUniqueId(), l.price);
        plugin.money().add(l.seller, net);
        plugin.money().save(false);

        for (ItemStack rest : buyer.getInventory().addItem(l.item.clone()).values())
            buyer.getWorld().dropItemNaturally(buyer.getLocation(), rest);
        dirty = true;
        save(false);

        Notify.bar(buyer, "<green>ซื้อสำเร็จ <gray>- <gold>" + plugin.money().format(l.price),
                "entity.villager.yes", 1.4f);
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null)
            Notify.chat(seller, "<green>ขายของได้ <gold>" + plugin.money().format(net)
                    + " <gray>(หักค่าธรรมเนียมแล้ว) <white>ผู้ซื้อ " + buyer.getName());
    }

    private void cancel(Player p, Listing l) {
        if (!l.seller.equals(p.getUniqueId())) return;
        listings.remove(l.id);
        if (p.getInventory().firstEmpty() == -1) store(p.getUniqueId(), l.item);
        else p.getInventory().addItem(l.item);
        dirty = true;
        save(false);
        Notify.bar(p, "<yellow>เก็บของคืนแล้ว", null, 1f);
    }

    private void store(UUID owner, ItemStack item) {
        mailbox.computeIfAbsent(owner, k -> new ArrayList<>()).add(item);
        dirty = true;
    }

    public void expire() {
        int days = plugin.getConfig().getInt("market.expire-days", 7);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        List<Listing> gone = new ArrayList<>();
        for (Listing l : listings.values()) if (l.listedAt < cutoff) gone.add(l);
        for (Listing l : gone) {
            listings.remove(l.id);
            store(l.seller, l.item);
        }
        if (!gone.isEmpty()) { dirty = true; save(false); }
    }

    // ---------------- menus ----------------

    public void open(Player p, View view, int page) {
        List<Listing> list = new ArrayList<>();
        if (view == View.BROWSE) list.addAll(listings.values());
        else if (view == View.MINE)
            for (Listing l : listings.values()) if (l.seller.equals(p.getUniqueId())) list.add(l);

        List<ItemStack> box = mailbox.getOrDefault(p.getUniqueId(), List.of());
        int size = view == View.MAILBOX ? box.size() : list.size();
        int pages = Math.max(1, (int) Math.ceil(size / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));

        Holder holder = new Holder(view, page);
        String title = switch (view) {
            case BROWSE -> "<dark_gray>ตลาดกลาง";
            case MINE -> "<dark_gray>ของที่คุณวางขาย";
            case MAILBOX -> "<dark_gray>กล่องเก็บของคืน";
        };
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize(title + " <gray>(" + (page + 1) + "/" + pages + ")"));
        holder.setInventory(inv);

        if (view == View.MAILBOX) {
            for (int i = page * 45; i < box.size() && i - page * 45 < 45; i++)
                inv.setItem(i - page * 45, box.get(i));
        } else {
            for (int i = page * 45; i < list.size() && i - page * 45 < 45; i++) {
                Listing l = list.get(i);
                ItemStack display = l.item.clone();
                ItemMeta meta = display.getItemMeta();
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(MM.deserialize("<dark_gray>—").decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<gray>ราคา <gold>" + plugin.money().format(l.price)).decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<gray>ผู้ขาย <yellow>" + l.sellerName).decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize(view == View.MINE ? "<red>คลิกเพื่อเก็บคืน" : "<green>คลิกเพื่อซื้อ").decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                display.setItemMeta(meta);
                inv.setItem(i - page * 45, display);
            }
        }

        inv.setItem(45, button(Material.ARROW, "<white>หน้าก่อน"));
        inv.setItem(53, button(Material.ARROW, "<white>หน้าถัดไป"));
        inv.setItem(47, button(Material.CHEST, view == View.BROWSE ? "<yellow>ของที่ฉันขาย" : "<yellow>ดูตลาด"));
        inv.setItem(51, button(Material.SHULKER_BOX, "<aqua>กล่องเก็บของคืน <gray>(" + box.size() + ")"));
        inv.setItem(49, button(Material.GOLD_INGOT, "<gold>" + plugin.money().format(plugin.money().get(p.getUniqueId()))));
        p.openInventory(inv);
    }

    private ItemStack button(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (slot == 45) { open(p, holder.view, holder.page - 1); return; }
        if (slot == 53) { open(p, holder.view, holder.page + 1); return; }
        if (slot == 47) { open(p, holder.view == View.BROWSE ? View.MINE : View.BROWSE, 0); return; }
        if (slot == 51) { open(p, View.MAILBOX, 0); return; }
        if (slot == 49 || slot < 0 || slot >= 45) return;

        if (holder.view == View.MAILBOX) {
            List<ItemStack> box = mailbox.get(p.getUniqueId());
            int index = holder.page * 45 + slot;
            if (box == null || index >= box.size()) return;
            ItemStack item = box.remove(index);
            for (ItemStack rest : p.getInventory().addItem(item).values())
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            dirty = true;
            save(false);
            open(p, View.MAILBOX, holder.page);
            return;
        }

        List<Listing> list = new ArrayList<>();
        if (holder.view == View.BROWSE) list.addAll(listings.values());
        else for (Listing l : listings.values()) if (l.seller.equals(p.getUniqueId())) list.add(l);
        int index = holder.page * 45 + slot;
        if (index >= list.size()) return;
        Listing l = list.get(index);
        if (holder.view == View.MINE) cancel(p, l);
        else buy(p, l);
        open(p, holder.view, holder.page);
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (cmd.getName().equalsIgnoreCase("sell")) {
            if (args.length == 0) { Notify.chat(p, "<gray>ใช้: /sell <ราคา> <gray>ขณะถือของที่จะขาย"); return true; }
            try { sell(p, Double.parseDouble(args[0])); }
            catch (NumberFormatException ex) { Notify.chat(p, "<red>ราคาไม่ถูกต้อง"); }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("mine")) open(p, View.MINE, 0);
        else if (args.length > 0 && args[0].equalsIgnoreCase("box")) open(p, View.MAILBOX, 0);
        else open(p, View.BROWSE, 0);
        return true;
    }

    // ---------------- persistence ----------------

    public void load() {
        listings.clear(); mailbox.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("listings");
        if (root != null) for (String key : root.getKeys(false)) {
            try {
                Listing l = new Listing();
                l.id = UUID.fromString(key);
                l.seller = UUID.fromString(Objects.requireNonNull(root.getString(key + ".seller")));
                l.sellerName = root.getString(key + ".seller-name", "?");
                l.item = root.getItemStack(key + ".item");
                l.price = root.getDouble(key + ".price");
                l.listedAt = root.getLong(key + ".listed-at", System.currentTimeMillis());
                if (l.item != null) listings.put(l.id, l);
            } catch (Exception ignored) { }
        }
        ConfigurationSection box = cfg.getConfigurationSection("mailbox");
        if (box != null) for (String key : box.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                List<ItemStack> items = new ArrayList<>();
                for (Object o : box.getList(key, List.of()))
                    if (o instanceof ItemStack it) items.add(it);
                mailbox.put(id, items);
            } catch (Exception ignored) { }
        }
        plugin.getLogger().info("Loaded " + listings.size() + " market listing(s).");
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String base = "listings." + l.id;
            cfg.set(base + ".seller", l.seller.toString());
            cfg.set(base + ".seller-name", l.sellerName);
            cfg.set(base + ".item", l.item);
            cfg.set(base + ".price", l.price);
            cfg.set(base + ".listed-at", l.listedAt);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : mailbox.entrySet())
            if (!e.getValue().isEmpty()) cfg.set("mailbox." + e.getKey(), e.getValue());
        try { cfg.save(file); dirty = false; } catch (IOException ignored) { }
    }
}
