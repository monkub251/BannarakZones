package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/** Party building and the run button. */
public class DungeonGui implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BannarakZones plugin;
    /** leader -> chosen members */
    private final Map<UUID, Set<UUID>> parties = new HashMap<>();

    public DungeonGui(BannarakZones plugin) { this.plugin = plugin; }

    public static class Holder implements InventoryHolder {
        private Inventory inv;
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("setspawn")) {
            if (!p.hasPermission("zones.admin")) { Notify.chat(p, "<red>ไม่มีสิทธิ์"); return true; }
            var c = plugin.getConfig();
            c.set("dungeon.world", p.getWorld().getName());
            c.set("dungeon.spawn.x", p.getLocation().getX());
            c.set("dungeon.spawn.y", p.getLocation().getY());
            c.set("dungeon.spawn.z", p.getLocation().getZ());
            c.set("dungeon.spawn.yaw", p.getLocation().getYaw());
            c.set("dungeon.spawn.pitch", p.getLocation().getPitch());
            plugin.saveConfig();
            plugin.readConfig();
            Notify.chat(p, "<green>ตั้งจุดเริ่มดันเจียนตรงนี้แล้ว");
            return true;
        }
        open(p);
        return true;
    }

    public void open(Player p) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize("<dark_gray>ดันเจียน"));
        holder.setInventory(inv);

        DungeonManager dm = plugin.dungeons();
        Set<UUID> chosen = parties.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>());
        chosen.removeIf(id -> Bukkit.getPlayer(id) == null);

        long cd = dm.cooldownLeft(p.getUniqueId());
        List<String> info = new ArrayList<>();
        info.add("<gray>" + dm.maxWave() + " เวฟ ยิ่งลึกยิ่งโหด");
        info.add("<gray>เวฟ " + (dm.maxWave() - 1) + " บอสรอง · เวฟ " + dm.maxWave() + " บอสใหญ่");
        info.add("<gray>ลงได้ <white>" + dm.minParty() + "-" + dm.maxParty() + " <gray>คน");
        info.add("<red>ตายแล้วออกทันที เตรียมของได้รอบเดียว");
        info.add("");
        info.add(cd > 0 ? "<red>รออีก " + DungeonManager.readable(cd) : "<green>พร้อมลง");
        inv.setItem(4, item(Material.WITHER_SKELETON_SKULL, "<dark_red>ดันเจียน", info, false));

        int slot = 19;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p) || slot > 34) continue;
            boolean picked = chosen.contains(other.getUniqueId());
            long otherCd = dm.cooldownLeft(other.getUniqueId());
            List<String> lore = new ArrayList<>();
            if (otherCd > 0) lore.add("<red>ติดคูลดาวน์ " + DungeonManager.readable(otherCd));
            else lore.add(picked ? "<green>อยู่ในทีมแล้ว" : "<gray>คลิกเพื่อชวน");
            inv.setItem(slot++, head(other, (picked ? "<green>" : "<yellow>") + other.getName(), lore, picked));
        }

        int size = chosen.size() + 1;
        boolean ready = cd <= 0 && size >= dm.minParty() && size <= dm.maxParty();
        inv.setItem(49, item(ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                ready ? "<green>เริ่มดันเจียน" : "<red>ยังเริ่มไม่ได้",
                List.of("<gray>ทีมตอนนี้ <white>" + size + "<gray>/<white>" + dm.maxParty(),
                        ready ? "<green>คลิกเพื่อเริ่ม" : "<gray>ต้องมี " + dm.minParty() + "-" + dm.maxParty() + " คน และไม่ติดคูลดาวน์"),
                ready));
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

    private ItemStack head(Player who, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(who);
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
        Set<UUID> chosen = parties.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashSet<>());

        if (slot >= 19 && slot <= 34) {
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType() != Material.PLAYER_HEAD) return;
            SkullMeta meta = (SkullMeta) it.getItemMeta();
            var owner = meta.getOwningPlayer();
            if (owner == null) return;
            UUID id = owner.getUniqueId();
            if (chosen.contains(id)) chosen.remove(id);
            else if (chosen.size() + 1 < plugin.dungeons().maxParty()
                    || chosen.size() + 1 == plugin.dungeons().maxParty()) {
                if (chosen.size() + 1 >= plugin.dungeons().maxParty()) {
                    Notify.bar(p, "<red>ทีมเต็มแล้ว", "block.note_block.bass", 0.8f);
                } else chosen.add(id);
            }
            open(p);
            return;
        }
        if (slot == 49) {
            List<Player> members = new ArrayList<>();
            for (UUID id : chosen) {
                Player m = Bukkit.getPlayer(id);
                if (m != null) members.add(m);
            }
            p.closeInventory();
            plugin.dungeons().start(p, members);
            chosen.clear();
        }
    }
}
