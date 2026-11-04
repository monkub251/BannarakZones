package com.bannarak.zones;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Asking a player a question without making them type a command.
 *
 * <p>On a phone, "/pay Somchai 5000" means opening the keyboard, remembering
 * the exact spelling of a friend's name, and getting the order of the
 * arguments right. This offers three ways round that: a grid of faces to pick
 * from, a typing window, and a row of ready-made amounts.
 *
 * <p>The typing window is an anvil, which is the only vanilla screen with a
 * text field in it. Whether that survives the trip through Geyser to a phone
 * is not something this code can know, so every prompt falls back to the chat
 * box the moment the anvil refuses to open, and {@code input.anvil: false} in
 * the config turns the anvil off everywhere if it turns out to be no good.
 */
public class Prompt implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final BannarakZones plugin;

    /** Who is being waited on, and what to do with what they say. */
    private final Map<UUID, Pending> waiting = new HashMap<>();

    public Prompt(BannarakZones plugin) { this.plugin = plugin; }

    private record Pending(String question, Consumer<String> then, long expiresAt) { }

    // ---------------------------------------------------------------
    // holders
    // ---------------------------------------------------------------

    /** A page of faces. */
    public class PickHolder implements InventoryHolder {
        private Inventory inv;
        final Consumer<String> then;
        final String question;
        PickHolder(String question, Consumer<String> then) { this.question = question; this.then = then; }
        void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    /** The anvil typing window. */
    public class TypeHolder implements InventoryHolder {
        private Inventory inv;
        final Consumer<String> then;
        final String question;
        TypeHolder(String question, Consumer<String> then) { this.question = question; this.then = then; }
        void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    /** A row of ready-made numbers. */
    public class AmountHolder implements InventoryHolder {
        private Inventory inv;
        final Consumer<Long> then;
        final String question;
        final long[] options;
        AmountHolder(String question, long[] options, Consumer<Long> then) {
            this.question = question; this.options = options; this.then = then;
        }
        void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------------------------------------------------------------
    // asking
    // ---------------------------------------------------------------

    /** Faces of everyone online, plus a way to type a name that is not there. */
    public void pickPlayer(Player viewer, String question, Consumer<String> then) {
        List<Player> others = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(viewer)) continue;
            if (plugin.staff() != null && plugin.staff().isVanished(t.getUniqueId())) continue;
            others.add(t);
        }

        int rows = Math.max(2, Math.min(6, 2 + (others.size() + 8) / 9));
        PickHolder holder = new PickHolder(question, then);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, MM.deserialize("<dark_gray>" + question));
        holder.setInventory(inv);

        int slot = 0;
        for (Player t : others) {
            if (slot >= (rows - 1) * 9) break;
            inv.setItem(slot++, head(t));
        }

        int last = (rows - 1) * 9;
        inv.setItem(last + 3, button(Material.NAME_TAG, "<yellow>พิมพ์ชื่อเอง",
                "<gray>ใช้ตอนคนนั้นไม่ได้ออนไลน์"));
        inv.setItem(last + 5, button(Material.BARRIER, "<red>ยกเลิก", null));
        if (others.isEmpty())
            inv.setItem(4, button(Material.GRAY_DYE, "<gray>ตอนนี้ไม่มีใครออนไลน์",
                    "<gray>กด \"พิมพ์ชื่อเอง\" ได้"));

        viewer.openInventory(inv);
    }

    /** A typing window, or the chat box when there cannot be one. */
    public void text(Player p, String question, String startWith, Consumer<String> then) {
        if (!plugin.getConfig().getBoolean("input.anvil", true)) { viaChat(p, question, then); return; }
        try {
            TypeHolder holder = new TypeHolder(question, then);
            Inventory inv = Bukkit.createInventory(holder, InventoryType.ANVIL,
                    MM.deserialize("<dark_gray>" + question));
            holder.setInventory(inv);
            inv.setItem(0, button(Material.PAPER,
                    startWith == null || startWith.isBlank() ? "พิมพ์ตรงนี้" : startWith,
                    "<gray>พิมพ์แล้วกดช่องขวาสุด"));
            p.openInventory(inv);
        } catch (RuntimeException e) {
            // Some versions refuse to hand out a working anvil. Not worth a
            // dead end for the player; ask in chat instead.
            plugin.getLogger().info("หน้าต่างพิมพ์เปิดไม่ได้ ใช้แชตแทน: " + e.getMessage());
            viaChat(p, question, then);
        }
    }

    /** Ready-made amounts, with "พิมพ์เอง" for anything else. */
    public void amount(Player p, String question, long[] options, Consumer<Long> then) {
        AmountHolder holder = new AmountHolder(question, options, then);
        Inventory inv = Bukkit.createInventory(holder, 27, MM.deserialize("<dark_gray>" + question));
        holder.setInventory(inv);
        for (int i = 0; i < options.length && i < 7; i++) {
            inv.setItem(10 + i, button(Material.GOLD_NUGGET,
                    "<gold>" + plugin.money().format(options[i]), null));
        }
        inv.setItem(21, button(Material.NAME_TAG, "<yellow>พิมพ์จำนวนเอง", null));
        inv.setItem(23, button(Material.BARRIER, "<red>ยกเลิก", null));
        p.openInventory(inv);
    }

    /** The fallback everything shares: ask, then listen for one chat line. */
    private void viaChat(Player p, String question, Consumer<String> then) {
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        Notify.chat(p, "<yellow>" + question);
        Notify.chat(p, "<gray>พิมพ์คำตอบในแชตได้เลย · พิมพ์ <white>ยกเลิก <gray>เพื่อออก");
        waiting.put(p.getUniqueId(),
                new Pending(question, then, System.currentTimeMillis() + 60_000));
    }

    // ---------------------------------------------------------------
    // answers
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pending = waiting.get(e.getPlayer().getUniqueId());
        if (pending == null) return;
        waiting.remove(e.getPlayer().getUniqueId());
        e.setCancelled(true);                       // it was an answer, not a message

        if (System.currentTimeMillis() > pending.expiresAt()) {
            Notify.chat(e.getPlayer(), "<gray>หมดเวลาแล้ว เปิดเมนูใหม่อีกครั้ง");
            return;
        }
        String said = PLAIN.serialize(e.message()).trim();
        if (said.isEmpty() || said.equalsIgnoreCase("ยกเลิก") || said.equalsIgnoreCase("cancel")) {
            Notify.chat(e.getPlayer(), "<gray>ยกเลิกแล้ว");
            return;
        }
        // The chat event is off the main thread; the callback must not be.
        Bukkit.getScheduler().runTask(plugin, () -> pending.then().accept(said));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof PickHolder || h instanceof TypeHolder || h instanceof AmountHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;

        if (h instanceof PickHolder pick) {
            int last = e.getView().getTopInventory().getSize() - 9;
            if (slot == last + 5) { p.closeInventory(); return; }
            if (slot == last + 3) {
                text(p, pick.question, null, pick.then);
                return;
            }
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType() != Material.PLAYER_HEAD) return;
            String name = nameOf(it);
            if (name == null) return;
            p.closeInventory();
            pick.then.accept(name);
            return;
        }

        if (h instanceof TypeHolder type) {
            // Slot 2 is the anvil's result: whatever was typed, on a new paper.
            if (slot != 2) return;
            ItemStack it = e.getCurrentItem();
            String typed = it == null ? null : nameOf(it);
            if (typed == null || typed.isBlank() || typed.equals("พิมพ์ตรงนี้")) {
                Notify.bar(p, "<red>ยังไม่ได้พิมพ์อะไร", null, 0.8f);
                return;
            }
            p.closeInventory();
            type.then.accept(typed.trim());
            return;
        }

        AmountHolder amt = (AmountHolder) h;
        if (slot == 23) { p.closeInventory(); return; }
        if (slot == 21) {
            text(p, amt.question, null, said -> {
                try {
                    long n = Long.parseLong(said.replace(",", "").trim());
                    if (n <= 0) throw new NumberFormatException();
                    amt.then.accept(n);
                } catch (NumberFormatException ex) {
                    Notify.chat(p, "<red>ใส่เป็นตัวเลขเท่านั้น");
                }
            });
            return;
        }
        int i = slot - 10;
        if (i < 0 || i >= amt.options.length) return;
        p.closeInventory();
        amt.then.accept(amt.options[i]);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { waiting.remove(e.getPlayer().getUniqueId()); }

    // ---------------------------------------------------------------
    // bits
    // ---------------------------------------------------------------

    private String nameOf(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        Component name = it.getItemMeta().displayName();
        if (name == null) return null;
        String plain = PLAIN.serialize(name).trim();
        return plain.isEmpty() ? null : plain;
    }

    private ItemStack head(OfflinePlayer who) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(who);
        // The name has to survive as plain text: it is what the click reads back.
        meta.displayName(Component.text(who.getName() == null ? "?" : who.getName())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(MM.deserialize("<green>▸ เลือกคนนี้")
                .decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack button(Material mat, String nameMini, String loreMini) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize(nameMini).decoration(TextDecoration.ITALIC, false));
        if (loreMini != null)
            meta.lore(List.of(MM.deserialize(loreMini).decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    // ---------------------------------------------------------------
    // the flows the menu buttons ask for
    // ---------------------------------------------------------------

    /** Routes a {@code prompt:<what>} menu action. */
    public void run(Player p, String what) {
        switch (what.toLowerCase(Locale.ROOT)) {
            case "tpa" -> pickPlayer(p, "ขอวาร์ปไปหาใคร",
                    name -> p.performCommand("tpa " + name));
            case "msg" -> pickPlayer(p, "ส่งข้อความหาใคร",
                    name -> text(p, "พิมพ์ข้อความถึง " + name, null,
                            msg -> p.performCommand("msg " + name + " " + msg)));
            case "ignore" -> pickPlayer(p, "ปิดแชตของใคร",
                    name -> p.performCommand("ignore " + name));
            case "pay" -> pickPlayer(p, "โอนเงินให้ใคร",
                    name -> amount(p, "โอนให้ " + name + " เท่าไหร่",
                            new long[]{100, 1000, 10_000, 100_000, 1_000_000},
                            n -> p.performCommand("pay " + name + " " + n)));
            default -> Notify.chat(p, "<red>ไม่รู้จักหน้าต่าง " + what);
        }
    }
}
