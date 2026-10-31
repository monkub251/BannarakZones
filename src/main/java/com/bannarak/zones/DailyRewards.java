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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** A streak of seven days that keeps looping, with a bonus on the last day. */
public class DailyRewards implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final File file;
    private final Map<UUID, String> lastClaim = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private boolean dirty = false;

    public DailyRewards(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "daily.yml");
    }

    public static class Holder implements InventoryHolder {
        private Inventory inv;
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    private int days() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("daily.days");
        return s == null ? 7 : s.getKeys(false).size();
    }

    private double rewardFor(int day) {
        return plugin.getConfig().getDouble("daily.days." + day + ".money", 5000);
    }

    private List<String> commandsFor(int day) {
        return plugin.getConfig().getStringList("daily.days." + day + ".commands");
    }

    public boolean claimedToday(UUID uuid) {
        return LocalDate.now().toString().equals(lastClaim.get(uuid));
    }

    public int streakOf(UUID uuid) {
        return Math.max(1, streak.getOrDefault(uuid, 0) + 1);
    }

    private void claim(Player p) {
        UUID id = p.getUniqueId();
        if (claimedToday(id)) {
            Notify.bar(p, "<gray>วันนี้รับไปแล้ว กลับมาใหม่พรุ่งนี้", "block.note_block.bass", 0.8f);
            return;
        }
        String yesterday = LocalDate.now().minusDays(1).toString();
        int next = yesterday.equals(lastClaim.get(id)) ? streak.getOrDefault(id, 0) + 1 : 1;
        if (next > days()) next = 1;

        double money = rewardFor(next);
        plugin.money().add(id, money);
        plugin.money().save(false);
        for (String cmd : commandsFor(next))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("<player>", p.getName()));

        lastClaim.put(id, LocalDate.now().toString());
        streak.put(id, next);
        dirty = true;
        save(false);

        Notify.popup(p, "<gradient:#8ce99a:#2f9e44>รางวัลรายวัน</gradient>",
                "<white>วันที่ " + next + " <gray>+ <gold>" + plugin.money().format(money),
                "entity.player.levelup", 1.5f);
        open(p);
    }

    public void open(Player p) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<dark_gray>รางวัลรายวัน"));
        holder.setInventory(inv);
        int today = streakOf(p.getUniqueId());
        boolean claimed = claimedToday(p.getUniqueId());
        int total = days();

        for (int d = 1; d <= total && d <= 9; d++) {
            boolean past = d < today || (d == today && claimed);
            boolean now = d == today && !claimed;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>รางวัล <gold>" + plugin.money().format(rewardFor(d)));
            lore.add("");
            if (past) lore.add("<green>✔ รับแล้ว");
            else if (now) lore.add("<yellow>คลิกเพื่อรับวันนี้");
            else lore.add("<dark_gray>ยังไม่ถึง");
            inv.setItem(9 + d, item(past ? Material.LIME_STAINED_GLASS_PANE
                            : now ? Material.GOLD_INGOT : Material.GRAY_STAINED_GLASS_PANE,
                    (now ? "<yellow>" : past ? "<green>" : "<gray>") + "วันที่ " + d, lore, now));
        }
        inv.setItem(4, item(Material.CLOCK, "<gold>ต่อเนื่อง <yellow>" + (claimed ? today : today - 1) + " <gold>วัน",
                List.of("<gray>ขาดหนึ่งวันแล้วเริ่มนับใหม่"), false));
        p.openInventory(inv);
    }

    private ItemStack item(Material mat, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(mat);
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
        if (e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.GOLD_INGOT) claim(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("daily.remind-on-join", true)) return;
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !claimedToday(p.getUniqueId()))
                Notify.chat(p, "<gray>มีรางวัลรายวันรออยู่ <white>/daily");
        }, 80L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        open(p);
        return true;
    }

    // ---------------- persistence ----------------

    public void load() {
        lastClaim.clear(); streak.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                lastClaim.put(id, root.getString(key + ".last"));
                streak.put(id, root.getInt(key + ".streak", 0));
            } catch (Exception ignored) { }
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : lastClaim.entrySet()) {
            cfg.set("players." + e.getKey() + ".last", e.getValue());
            cfg.set("players." + e.getKey() + ".streak", streak.getOrDefault(e.getKey(), 0));
        }
        try { cfg.save(file); dirty = false; } catch (IOException ignored) { }
    }
}
