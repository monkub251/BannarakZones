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

import java.util.ArrayList;
import java.util.List;

public class RankGui implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BannarakZones plugin;

    public RankGui(BannarakZones plugin) { this.plugin = plugin; }

    public static class Holder implements InventoryHolder {
        private Inventory inv;
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        open(p);
        return true;
    }

    public void open(Player p) {
        Holder holder = new Holder();
        List<RankManager.Rank> ranks = plugin.ranks().ranks();
        int rows = Math.max(3, (int) Math.ceil(ranks.size() / 9.0) + 2);
        Inventory inv = Bukkit.createInventory(holder, Math.min(54, rows * 9),
                MM.deserialize("<dark_gray>ยศในเซิร์ฟ"));
        holder.setInventory(inv);

        RankManager.Rank mine = plugin.ranks().rankOf(p.getUniqueId());
        int slot = 10;
        for (RankManager.Rank r : ranks) {
            if (slot >= inv.getSize() - 1) break;
            boolean have = mine != null && mine.order >= r.order;
            List<String> lore = new ArrayList<>();
            if (r.needHours > 0) lore.add("<gray>เล่นครบ <white>" + r.needHours + " <gray>ชั่วโมง");
            if (r.needMoney > 0) lore.add("<gray>มีเงิน <gold>" + plugin.money().format(r.needMoney));
            if (r.needMissions > 0) lore.add("<gray>ทำภารกิจครบ <white>" + r.needMissions + " <gray>อย่าง");
            if (r.price >= 0) lore.add("<gray>ซื้อได้ <gold>" + plugin.money().format(r.price));
            lore.add("");
            if (have) lore.add("<green>✔ คุณมียศนี้แล้ว");
            else if (r.price >= 0) lore.add("<yellow>คลิกเพื่อซื้อ");
            else if (plugin.ranks().qualifies(p, r)) lore.add("<green>ครบเงื่อนไขแล้ว เดี๋ยวเลื่อนให้");
            else lore.add("<red>ยังไม่ครบเงื่อนไข");

            inv.setItem(slot++, item(have ? Material.LIME_DYE : Material.GRAY_DYE,
                    r.display, lore, have));
        }
        inv.setItem(inv.getSize() - 5, item(Material.GOLD_INGOT, "<gold>เงินของคุณ",
                List.of("<yellow>" + plugin.money().format(plugin.money().get(p.getUniqueId()))), false));
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
        int index = e.getRawSlot() - 10;
        List<RankManager.Rank> ranks = plugin.ranks().ranks();
        if (index < 0 || index >= ranks.size()) return;
        RankManager.Rank r = ranks.get(index);
        if (r.price < 0) return;
        if (plugin.ranks().buy(p, r)) open(p);
    }
}
