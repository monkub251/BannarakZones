package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

/**
 * Server-made gear. Each entry names a vanilla item to ride on, a model
 * number from the resource pack, and whatever stats it should carry.
 */
public class CustomItems implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final NamespacedKey idKey;
    private final Map<String, Def> items = new LinkedHashMap<>();

    public CustomItems(BannarakZones plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "custom_item");
    }

    public static class Def {
        String id;
        Material material = Material.STICK;
        int modelData = -1;
        String name = "";
        List<String> lore = new ArrayList<>();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        double attackDamage = -1;
        double attackSpeed = -1;
        double armour = -1;
        double toughness = -1;
        double maxHealth = -1;
        double movement = -1;
        double knockbackResistance = -1;
        boolean unbreakable = false;
        boolean glow = false;
    }

    public Collection<Def> all() { return items.values(); }

    public class Holder implements InventoryHolder {
        private Inventory inv;
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- loading ----------------

    public void load() {
        items.clear();
        File f = new File(plugin.getDataFolder(), "items.yml");
        if (!f.exists()) plugin.saveResource("items.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("items");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Def d = new Def();
            d.id = id;
            Material m = Material.matchMaterial(s.getString("material", "STICK"));
            if (m != null) d.material = m;
            d.modelData = s.getInt("model-data", -1);
            d.name = s.getString("name", id);
            d.lore = s.getStringList("lore");
            ConfigurationSection ench = s.getConfigurationSection("enchants");
            if (ench != null) for (String k : ench.getKeys(false)) d.enchants.put(k, ench.getInt(k, 1));
            d.attackDamage = s.getDouble("stats.attack-damage", -1);
            d.attackSpeed = s.getDouble("stats.attack-speed", -1);
            d.armour = s.getDouble("stats.armor", -1);
            d.toughness = s.getDouble("stats.armor-toughness", -1);
            d.maxHealth = s.getDouble("stats.max-health", -1);
            d.movement = s.getDouble("stats.movement-speed", -1);
            d.knockbackResistance = s.getDouble("stats.knockback-resistance", -1);
            d.unbreakable = s.getBoolean("unbreakable", false);
            d.glow = s.getBoolean("glow", false);
            items.put(id.toLowerCase(Locale.ROOT), d);
        }
        plugin.getLogger().info("Loaded " + items.size() + " custom item(s).");
    }

    // ---------------- building ----------------

    private Attribute attr(String... names) {
        for (String n : names) {
            try { return Attribute.valueOf(n); } catch (IllegalArgumentException | NoSuchFieldError ignored) { }
        }
        return null;
    }

    private void addModifier(ItemMeta meta, Attribute a, String suffix, double value, EquipmentSlotGroup slot) {
        if (a == null || value < 0) return;
        meta.addAttributeModifier(a, new AttributeModifier(
                new NamespacedKey(plugin, "bz_" + suffix),
                value, AttributeModifier.Operation.ADD_NUMBER, slot));
    }

    public ItemStack build(Def d, int amount) {
        ItemStack it = new ItemStack(d.material, Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(d.name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : d.lore) lore.add(MM.deserialize(line).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (d.modelData > 0) {
            try { meta.setCustomModelData(d.modelData); } catch (Throwable ignored) { }
        }
        for (Map.Entry<String, Integer> e : d.enchants.entrySet()) {
            Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(e.getKey().toLowerCase(Locale.ROOT)));
            if (ench != null) meta.addEnchant(ench, e.getValue(), true);
        }
        if (d.glow && meta.getEnchants().isEmpty()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        meta.setUnbreakable(d.unbreakable);

        EquipmentSlotGroup hand = EquipmentSlotGroup.MAINHAND;
        EquipmentSlotGroup armourSlot = EquipmentSlotGroup.ARMOR;
        addModifier(meta, attr("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"), "dmg", d.attackDamage, hand);
        addModifier(meta, attr("ATTACK_SPEED", "GENERIC_ATTACK_SPEED"), "spd", d.attackSpeed, hand);
        addModifier(meta, attr("ARMOR", "GENERIC_ARMOR"), "arm", d.armour, armourSlot);
        addModifier(meta, attr("ARMOR_TOUGHNESS", "GENERIC_ARMOR_TOUGHNESS"), "tough", d.toughness, armourSlot);
        addModifier(meta, attr("MAX_HEALTH", "GENERIC_MAX_HEALTH"), "hp", d.maxHealth, EquipmentSlotGroup.ANY);
        addModifier(meta, attr("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"), "move", d.movement, EquipmentSlotGroup.ANY);
        addModifier(meta, attr("KNOCKBACK_RESISTANCE", "GENERIC_KNOCKBACK_RESISTANCE"), "kb",
                d.knockbackResistance, armourSlot);

        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, d.id);
        it.setItemMeta(meta);
        return it;
    }

    public String idOf(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    // ---------------- menu ----------------

    public void open(Player p) {
        Holder holder = new Holder();
        int rows = Math.max(3, Math.min(6, (int) Math.ceil(items.size() / 9.0) + 1));
        Inventory inv = Bukkit.createInventory(holder, rows * 9, MM.deserialize("<dark_gray>ไอเทมของเซิร์ฟ"));
        holder.setInventory(inv);
        int slot = 0;
        for (Def d : items.values()) {
            if (slot >= inv.getSize()) break;
            ItemStack preview = build(d, 1);
            ItemMeta meta = preview.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(MM.deserialize("<dark_gray>id: " + d.id).decoration(TextDecoration.ITALIC, false));
            if (p.hasPermission("zones.admin"))
                lore.add(MM.deserialize("<yellow>คลิกเพื่อหยิบ").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            preview.setItemMeta(meta);
            inv.setItem(slot++, preview);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("zones.admin")) return;
        String id = idOf(e.getCurrentItem());
        if (id == null) return;
        Def d = items.get(id.toLowerCase(Locale.ROOT));
        if (d == null) return;
        for (ItemStack rest : p.getInventory().addItem(build(d, 1)).values())
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        Notify.bar(p, "<green>หยิบ " + d.name + " <green>แล้ว", "entity.item.pickup", 1.4f);
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (sender instanceof Player p) open(p);
            else for (Def d : items.values()) sender.sendMessage(d.id);
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("zones.admin")) { sender.sendMessage(Notify.of("<red>ไม่มีสิทธิ์")); return true; }
            if (args.length < 3) { sender.sendMessage(Notify.of("<gray>/citem give <id> <ผู้เล่น> [จำนวน]")); return true; }
            Def d = items.get(args[1].toLowerCase(Locale.ROOT));
            Player target = Bukkit.getPlayerExact(args[2]);
            if (d == null || target == null) { sender.sendMessage(Notify.of("<red>ไม่พบไอเทมหรือผู้เล่น")); return true; }
            int amount = args.length > 3 ? Math.max(1, Integer.parseInt(args[3])) : 1;
            for (ItemStack rest : target.getInventory().addItem(build(d, amount)).values())
                target.getWorld().dropItemNaturally(target.getLocation(), rest);
            Notify.bar(target, "<green>ได้รับ " + d.name, "entity.item.pickup", 1.4f);
            sender.sendMessage(Notify.of("<green>ให้แล้ว"));
            return true;
        }
        sender.sendMessage(Notify.of("<gray>/citem list · /citem give <id> <ผู้เล่น> [จำนวน]"));
        return true;
    }
}
