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

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class MissionGui implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DecimalFormat NUM = new DecimalFormat("#,##0");
    private final BannarakZones plugin;

    public MissionGui(BannarakZones plugin) {
        this.plugin = plugin;
    }

    public static class Holder implements InventoryHolder {
        private Inventory inv;
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        open(p, 0);
        return true;
    }

    /** Ten-cell bar; full cells first, then the remainder. */
    private static String bar(long have, long need) {
        int filled = need <= 0 ? 10 : (int) Math.min(10, (have * 10) / need);
        StringBuilder sb = new StringBuilder("<green>");
        sb.append("▬".repeat(filled));
        sb.append("<dark_gray>").append("▬".repeat(10 - filled));
        return sb.toString();
    }

    public void open(Player p, int page) {
        List<Mission> all = plugin.missions().missions();
        int perPage = 45;
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pages - 1));

        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54,
                MM.deserialize("<dark_gray>ภารกิจ <gray>(" + (page + 1) + "/" + pages + ")"));
        holder.setInventory(inv);

        int slot = 0;
        for (int i = page * perPage; i < all.size() && slot < perPage; i++) {
            Mission m = all.get(i);
            long have = m.progress(p);
            int done = plugin.missions().claimedTier(p.getUniqueId(), m.id);
            Mission.Tier next = m.tier(done + 1);

            List<String> lore = new ArrayList<>();
            lore.add("<gray>ผ่านแล้ว <white>" + (done + 1) + "<gray>/<white>" + m.tierCount() + " <gray>ขั้น");
            lore.add("");
            if (next == null) {
                lore.add("<gold>✔ ครบทุกขั้นแล้ว");
            } else {
                lore.add("<gray>ตอนนี้ <white>" + NUM.format(have) + " <gray>/ <white>"
                        + NUM.format(next.amount) + " " + m.unit);
                lore.add(bar(have, next.amount));
                lore.add("<gray>รางวัลขั้นถัดไป <gold>"
                        + plugin.money().format(next.reward));
            }
            lore.add("");
            for (int t = 0; t < m.tierCount(); t++) {
                Mission.Tier tier = m.tier(t);
                String mark = t <= done ? "<green>✔ " : "<dark_gray>✗ ";
                lore.add(mark + "<gray>" + NUM.format(tier.amount) + " " + m.unit
                        + " <dark_gray>- <gold>" + plugin.money().format(tier.reward));
            }

            inv.setItem(slot++, item(m.icon, "<yellow>" + m.display, lore, next == null));
        }

        if (pages > 1) {
            if (page > 0) inv.setItem(45, item(Material.ARROW, "<white>หน้าก่อน", List.of(), false));
            if (page < pages - 1) inv.setItem(53, item(Material.ARROW, "<white>หน้าถัดไป", List.of(), false));
        }
        inv.setItem(49, item(Material.GOLD_INGOT, "<gold>เงินของคุณ",
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
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(e.getView().title());
        int page = 0;
        try {
            int open = title.indexOf('(');
            if (open >= 0) page = Integer.parseInt(title.substring(open + 1, title.indexOf('/'))) - 1;
        } catch (Exception ignored) { }
        if (e.getRawSlot() == 45) open(p, page - 1);
        else if (e.getRawSlot() == 53) open(p, page + 1);
    }
}
