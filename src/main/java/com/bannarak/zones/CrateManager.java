package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Keys, crate blocks and the roll. */
public class CrateManager implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final NamespacedKey keyTag;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, String> blocks = new HashMap<>();   // "world,x,y,z" -> crate id
    private final Set<UUID> rolling = new HashSet<>();

    public CrateManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.keyTag = new NamespacedKey(plugin, "crate_key");
    }

    public static class Reward {
        String display = "";
        int weight = 1;
        double money = 0;
        Material item;
        int amount = 1;
        List<String> commands = new ArrayList<>();
        Material icon = Material.CHEST;
    }

    public static class Crate {
        String id;
        String display;
        Material keyMaterial = Material.TRIPWIRE_HOOK;
        String keyName = "<yellow>กุญแจ";
        Material icon = Material.ENDER_CHEST;
        final List<Reward> rewards = new ArrayList<>();
        int totalWeight = 0;
    }

    public class Holder implements InventoryHolder {
        public final String crateId;
        private Inventory inv;
        public Holder(String crateId) { this.crateId = crateId; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------- loading ----------------

    public void load() {
        crates.clear();
        File f = new File(plugin.getDataFolder(), "crates.yml");
        if (!f.exists()) plugin.saveResource("crates.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("crates");
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Crate c = new Crate();
            c.id = id;
            c.display = s.getString("display", id);
            Material km = Material.matchMaterial(s.getString("key-item", "TRIPWIRE_HOOK"));
            if (km != null) c.keyMaterial = km;
            c.keyName = s.getString("key-name", "<yellow>กุญแจ " + c.display);
            Material ic = Material.matchMaterial(s.getString("icon", "ENDER_CHEST"));
            if (ic != null) c.icon = ic;
            ConfigurationSection rs = s.getConfigurationSection("rewards");
            if (rs != null) for (String rk : rs.getKeys(false)) {
                Reward r = new Reward();
                r.display = rs.getString(rk + ".display", rk);
                r.weight = Math.max(1, rs.getInt(rk + ".weight", 1));
                r.money = rs.getDouble(rk + ".money", 0);
                String item = rs.getString(rk + ".item");
                if (item != null) r.item = Material.matchMaterial(item);
                r.amount = rs.getInt(rk + ".amount", 1);
                r.commands = rs.getStringList(rk + ".commands");
                Material ri = Material.matchMaterial(rs.getString(rk + ".icon",
                        r.item != null ? r.item.name() : "GOLD_INGOT"));
                if (ri != null) r.icon = ri;
                c.rewards.add(r);
                c.totalWeight += r.weight;
            }
            crates.put(id.toLowerCase(Locale.ROOT), c);
        }
        loadBlocks();
        plugin.getLogger().info("Loaded " + crates.size() + " crate(s).");
    }

    private File blocksFile() { return new File(plugin.getDataFolder(), "crate-blocks.yml"); }

    private void loadBlocks() {
        blocks.clear();
        File f = blocksFile();
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        for (String key : cfg.getKeys(false)) blocks.put(key, cfg.getString(key));
    }

    private void saveBlocks() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, String> e : blocks.entrySet()) cfg.set(e.getKey(), e.getValue());
        try { cfg.save(blocksFile()); } catch (IOException ignored) { }
    }

    private static String keyOf(Location l) {
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    // ---------------- keys ----------------

    public ItemStack makeKey(Crate c, int amount) {
        ItemStack it = new ItemStack(c.keyMaterial, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(c.keyName).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                MM.deserialize("<gray>คลิกขวาที่กล่อง <white>" + c.display).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, c.id);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
        return it;
    }

    private String crateIdOfKey(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
    }

    /** One hologram id per crate block, so setting it twice replaces the label. */
    private String holoId(org.bukkit.Location l) {
        return "crate_" + l.getWorld().getName() + "_" + l.getBlockX()
                + "_" + l.getBlockY() + "_" + l.getBlockZ();
    }

    /** Hangs the crate's name over the block, so nobody has to guess which one it is. */
    private void label(org.bukkit.Location block, String crateId) {
        if (plugin.holos() == null) return;
        Crate c = crates.get(crateId.toLowerCase(Locale.ROOT));
        if (c == null) return;
        plugin.holos().put(holoId(block),
                block.clone().add(0.5, 1.6, 0.5),
                java.util.List.of(c.display, "<gray>คลิกขวาด้วยกุญแจ"),
                1f);
    }

    // ---------------- interaction ----------------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        String crateId = blocks.get(keyOf(e.getClickedBlock().getLocation()));
        if (crateId == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Crate c = crates.get(crateId.toLowerCase(Locale.ROOT));
        if (c == null) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        String held = crateIdOfKey(hand);
        if (held == null || !held.equalsIgnoreCase(c.id)) {
            preview(p, c);
            return;
        }
        if (rolling.contains(p.getUniqueId())) return;
        hand.setAmount(hand.getAmount() - 1);
        roll(p, c, e.getClickedBlock().getLocation());
    }

    public void preview(Player p, Crate c) {
        Holder holder = new Holder(c.id);
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<dark_gray>ของใน " + c.display));
        holder.setInventory(inv);
        int slot = 0;
        for (Reward r : c.rewards) {
            if (slot > 44) break;
            double chance = c.totalWeight == 0 ? 0 : (r.weight * 100.0 / c.totalWeight);
            inv.setItem(slot++, item(r.icon, r.display,
                    List.of("<gray>โอกาส <white>" + String.format(Locale.US, "%.2f", chance) + "%")));
        }
        inv.setItem(49, item(Material.TRIPWIRE_HOOK, "<yellow>ต้องใช้กุญแจ",
                List.of("<gray>ถือกุญแจแล้วคลิกขวาที่กล่อง")));
        p.openInventory(inv);
    }

    private Reward pick(Crate c) {
        int roll = new Random().nextInt(Math.max(1, c.totalWeight));
        int acc = 0;
        for (Reward r : c.rewards) {
            acc += r.weight;
            if (roll < acc) return r;
        }
        return c.rewards.isEmpty() ? null : c.rewards.get(c.rewards.size() - 1);
    }

    private void roll(Player p, Crate c, Location at) {
        Reward prize = pick(c);
        if (prize == null) return;
        rolling.add(p.getUniqueId());

        Holder holder = new Holder(c.id);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<dark_gray>กำลังสุ่ม..."));
        holder.setInventory(inv);
        p.openInventory(inv);

        World w = at.getWorld();
        new BukkitRunnable() {
            int step = 0;
            final int steps = 26;
            @Override public void run() {
                if (step >= steps) {
                    cancel();
                    for (int i = 0; i < 27; i++) inv.setItem(i, null);
                    inv.setItem(13, item(prize.icon, prize.display, List.of("<green>ได้รับแล้ว")));
                    give(p, prize);
                    p.playSound(p.getLocation(), "ui.toast.challenge_complete", 0.9f, 1.2f);
                    if (w != null) w.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0.5, 1.2, 0.5),
                            30, 0.4, 0.4, 0.4, 0.1);
                    rolling.remove(p.getUniqueId());
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (p.getOpenInventory().getTopInventory().equals(inv)) p.closeInventory();
                    }, 50L);
                    return;
                }
                Reward spin = c.rewards.get(new Random().nextInt(c.rewards.size()));
                inv.setItem(13, item(spin.icon, spin.display, List.of()));
                p.playSound(p.getLocation(), "block.note_block.hat", 0.6f, 1.0f + step * 0.03f);
                step++;
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    private void give(Player p, Reward r) {
        if (r.money > 0) {
            plugin.money().add(p.getUniqueId(), r.money);
            plugin.money().save(false);
        }
        if (r.item != null) {
            ItemStack stack = new ItemStack(r.item, Math.max(1, r.amount));
            for (ItemStack rest : p.getInventory().addItem(stack).values())
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        for (String cmd : r.commands)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("<player>", p.getName()));
        Notify.bar(p, "<green>ได้รับ " + r.display, null, 1f);
        if (plugin.getConfig().getBoolean("crates.broadcast-rare", true) && r.weight <= 2)
            Bukkit.broadcast(Notify.of("<gold>★ <yellow>" + p.getName() + " <white>สุ่มได้ " + r.display));
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat == null ? Material.CHEST : mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(MM.deserialize(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ---------------- commands ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Notify.of("<gray>/crate list · /crate key <ชื่อกล่อง> <ผู้เล่น> [จำนวน] · /crate set <ชื่อกล่อง> · /crate unset"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                sender.sendMessage(Notify.of("<gold>กล่องทั้งหมด:"));
                for (Crate c : crates.values())
                    sender.sendMessage(Notify.of("<gray> - <white>" + c.id + " <dark_gray>(" + c.display + "<dark_gray>)"));
            }
            case "key" -> {
                if (!sender.hasPermission("zones.admin")) { sender.sendMessage(Notify.of("<red>ไม่มีสิทธิ์")); return true; }
                if (args.length < 3) { sender.sendMessage(Notify.of("<gray>/crate key <ชื่อกล่อง> <ผู้เล่น> [จำนวน]")); return true; }
                Crate c = crates.get(args[1].toLowerCase(Locale.ROOT));
                Player target = Bukkit.getPlayerExact(args[2]);
                if (c == null || target == null) { sender.sendMessage(Notify.of("<red>ไม่พบกล่องหรือผู้เล่น")); return true; }
                int amount = args.length > 3 ? Math.max(1, Integer.parseInt(args[3])) : 1;
                for (ItemStack rest : target.getInventory().addItem(makeKey(c, amount)).values())
                    target.getWorld().dropItemNaturally(target.getLocation(), rest);
                Notify.bar(target, "<green>ได้รับ " + c.keyName + " <white>x" + amount, "entity.item.pickup", 1.4f);
                sender.sendMessage(Notify.of("<green>ให้กุญแจแล้ว"));
            }
            case "set" -> {
                if (!(sender instanceof Player p)) return true;
                if (!p.hasPermission("zones.admin")) { Notify.chat(p, "<red>ไม่มีสิทธิ์"); return true; }
                if (args.length < 2) { Notify.chat(p, "<gray>/crate set <ชื่อกล่อง>"); return true; }
                var target = p.getTargetBlockExact(6);
                if (target == null) { Notify.chat(p, "<red>มองไปที่บล็อกก่อน"); return true; }
                if (!crates.containsKey(args[1].toLowerCase(Locale.ROOT))) { Notify.chat(p, "<red>ไม่พบกล่องนี้"); return true; }
                String cid = args[1].toLowerCase(Locale.ROOT);
                blocks.put(keyOf(target.getLocation()), cid);
                saveBlocks();
                label(target.getLocation(), cid);
                Notify.chat(p, "<green>ตั้งบล็อกนี้เป็นกล่อง <yellow>" + args[1] + " <green>แล้ว");
                Notify.chat(p, "<gray>ใส่ป้ายลอยให้แล้ว คนเล่นจะเห็นว่าบล็อกนี้ไม่ธรรมดา");
            }
            case "unset" -> {
                if (!(sender instanceof Player p)) return true;
                if (!p.hasPermission("zones.admin")) { Notify.chat(p, "<red>ไม่มีสิทธิ์"); return true; }
                var target = p.getTargetBlockExact(6);
                if (target == null) return true;
                if (blocks.remove(keyOf(target.getLocation())) != null) {
                    saveBlocks();
                    if (plugin.holos() != null) plugin.holos().drop(holoId(target.getLocation()));
                    Notify.chat(p, "<yellow>เอาบล็อกนี้ออกจากระบบกล่องแล้ว");
                } else Notify.chat(p, "<gray>บล็อกนี้ไม่ใช่กล่อง");
            }
            default -> sender.sendMessage(Notify.of("<gray>/crate list · key · set · unset"));
        }
        return true;
    }
}
