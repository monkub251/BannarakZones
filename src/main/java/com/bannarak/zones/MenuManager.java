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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;

/**
 * Every menu in the server is described in menu.yml, so new buttons are a
 * config edit rather than a rebuild.
 */
public class MenuManager implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final Map<String, MenuDef> menus = new LinkedHashMap<>();

    public MenuManager(BannarakZones plugin) {
        this.plugin = plugin;
    }

    public static class Button {
        Material icon = Material.PAPER;
        String name = "";
        List<String> lore = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String permission = null;
        boolean glow = false;
    }

    public static class MenuDef {
        String id;
        String title = "เมนู";
        int rows = 3;
        final Map<Integer, Button> buttons = new LinkedHashMap<>();
        Material filler = null;
    }

    public class Holder implements InventoryHolder {
        public final String menuId;
        private Inventory inv;
        public Holder(String menuId) { this.menuId = menuId; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- loading ----------------

    public void load() {
        menus.clear();
        File f = new File(plugin.getDataFolder(), "menu.yml");
        if (!f.exists()) plugin.saveResource("menu.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("menus");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            MenuDef def = new MenuDef();
            def.id = id;
            def.title = s.getString("title", id);
            def.rows = Math.max(1, Math.min(6, s.getInt("rows", 3)));
            String fill = s.getString("filler");
            if (fill != null) def.filler = Material.matchMaterial(fill);
            ConfigurationSection items = s.getConfigurationSection("items");
            if (items != null) for (String slotKey : items.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(slotKey);
                    ConfigurationSection b = items.getConfigurationSection(slotKey);
                    if (b == null) continue;
                    Button btn = new Button();
                    Material m = Material.matchMaterial(b.getString("icon", "PAPER"));
                    btn.icon = m == null ? Material.PAPER : m;
                    btn.name = b.getString("name", "");
                    btn.lore = b.getStringList("lore");
                    btn.actions = b.getStringList("actions");
                    btn.permission = b.getString("permission");
                    btn.glow = b.getBoolean("glow", false);
                    def.buttons.put(slot, btn);
                } catch (NumberFormatException ignored) { }
            }
            menus.put(id.toLowerCase(Locale.ROOT), def);
        }
        plugin.getLogger().info("Loaded " + menus.size() + " menu(s).");
    }

    // ---------------- opening ----------------

    public boolean open(Player p, String id) {
        MenuDef def = menus.get(id == null ? "main" : id.toLowerCase(Locale.ROOT));
        if (def == null) return false;
        Holder holder = new Holder(def.id);
        Inventory inv = Bukkit.createInventory(holder, def.rows * 9, MM.deserialize(def.title));
        holder.setInventory(inv);

        if (def.filler != null) {
            ItemStack blank = new ItemStack(def.filler);
            ItemMeta bm = blank.getItemMeta();
            bm.displayName(Component.text(" "));
            blank.setItemMeta(bm);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, blank);
        }

        for (Map.Entry<Integer, Button> e : def.buttons.entrySet()) {
            Button b = e.getValue();
            if (b.permission != null && !p.hasPermission(b.permission)) continue;
            if (e.getKey() < 0 || e.getKey() >= inv.getSize()) continue;
            inv.setItem(e.getKey(), render(p, b));
        }
        p.openInventory(inv);
        return true;
    }

    private ItemStack render(Player p, Button b) {
        ItemStack it = new ItemStack(b.icon);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(fill(p, b.name)).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : b.lore)
            lore.add(MM.deserialize(fill(p, line)).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (b.glow) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        it.setItemMeta(meta);
        return it;
    }

    /** The handful of placeholders menus can use. */
    private String fill(Player p, String text) {
        if (text == null) return "";
        return text
                .replace("<player>", p.getName())
                .replace("<money>", plugin.money().format(plugin.money().get(p.getUniqueId())))
                .replace("<zones>", String.valueOf(plugin.store().zonesOf(p.getUniqueId()).size()))
                .replace("<slots>", String.valueOf(plugin.store().slotsOf(p.getUniqueId())))
                .replace("<homes>", String.valueOf(plugin.locations().homesOf(p.getUniqueId()).size()));
    }

    // ---------------- actions ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        MenuDef def = menus.get(holder.menuId.toLowerCase(Locale.ROOT));
        if (def == null) return;
        Button b = def.buttons.get(e.getRawSlot());
        if (b == null) return;
        if (b.permission != null && !p.hasPermission(b.permission)) return;
        run(p, b.actions);
    }

    private void run(Player p, List<String> actions) {
        for (String raw : actions) {
            String action = raw.trim();
            String lower = action.toLowerCase(Locale.ROOT);
            if (lower.startsWith("open:")) {
                String target = action.substring(5).trim();
                plugin.getServer().getScheduler().runTask(plugin, () -> open(p, target));
            } else if (lower.startsWith("console:")) {
                String cmd = action.substring(8).trim().replace("<player>", p.getName());
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            } else if (lower.startsWith("message:")) {
                Notify.chat(p, action.substring(8).trim());
            } else if (lower.startsWith("sound:")) {
                p.playSound(p.getLocation(), action.substring(6).trim(), 0.8f, 1.2f);
            } else if (lower.equals("close")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> p.closeInventory());
            } else {
                String cmd = (lower.startsWith("player:") ? action.substring(7) : action).trim();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    p.closeInventory();
                    p.performCommand(cmd);
                });
            }
        }
    }

    // ---------------- hotbar compass ----------------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!plugin.getConfig().getBoolean("menu.compass-item", true)) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.COMPASS) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        open(e.getPlayer(), "main");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        String id = args.length > 0 ? args[0] : "main";
        if (!open(p, id)) Notify.chat(p, "<red>ไม่พบเมนูชื่อนี้");
        return true;
    }
}
