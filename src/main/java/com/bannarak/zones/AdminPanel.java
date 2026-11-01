package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/** The staff console, in game: tabs across the top, controls underneath. */
public class AdminPanel implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BannarakZones plugin;
    private final Set<UUID> frozen = new HashSet<>();

    public AdminPanel(BannarakZones plugin) { this.plugin = plugin; }

    public enum Tab { DASHBOARD, PLAYERS, WORLD, ECONOMY, SYSTEMS, BANS, PLAYER_DETAIL }

    public class Holder implements InventoryHolder {
        public final Tab tab;
        public final UUID subject;   // for PLAYER_DETAIL
        private Inventory inv;
        public Holder(Tab tab, UUID subject) { this.tab = tab; this.subject = subject; }
        public void setInventory(Inventory i) { this.inv = i; }
        @Override public Inventory getInventory() { return inv; }
    }

    public Set<UUID> frozen() { return frozen; }

    // ---------------- shell ----------------

    private Inventory shell(Player viewer, Tab tab, UUID subject, String title) {
        Holder holder = new Holder(tab, subject);
        Inventory inv = Bukkit.createInventory(holder, 54, MM.deserialize(title));
        holder.setInventory(inv);

        ItemStack divider = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 9; i < 18; i++) inv.setItem(i, divider);

        tabButton(inv, 0, Material.COMPARATOR, "แดชบอร์ด", tab == Tab.DASHBOARD);
        tabButton(inv, 1, Material.PLAYER_HEAD, "ผู้เล่น", tab == Tab.PLAYERS);
        tabButton(inv, 2, Material.GRASS_BLOCK, "โลก", tab == Tab.WORLD);
        tabButton(inv, 3, Material.GOLD_INGOT, "เศรษฐกิจ", tab == Tab.ECONOMY);
        tabButton(inv, 4, Material.BEACON, "ระบบ", tab == Tab.SYSTEMS);
        tabButton(inv, 5, Material.BARRIER, "รายชื่อลงโทษ", tab == Tab.BANS);
        inv.setItem(8, item(Material.CLOCK, "<gray>รีเฟรช", List.of("<dark_gray>กดเพื่อโหลดใหม่"), false));
        return inv;
    }

    private void tabButton(Inventory inv, int slot, Material icon, String name, boolean active) {
        inv.setItem(slot, item(icon, (active ? "<gold><bold>" : "<gray>") + name,
                active ? List.of("<dark_gray>กำลังดูอยู่") : List.of("<dark_gray>คลิกเพื่อเปิด"), active));
    }

    private ItemStack item(Material mat, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(mat == null ? Material.PAPER : mat);
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

    /** The key we stamp a head with, so a click knows exactly who it meant. */
    private org.bukkit.NamespacedKey targetKey() {
        return new org.bukkit.NamespacedKey(plugin, "target");
    }

    /** Reads the id off a clicked head. Falls back to the skull profile. */
    private UUID targetOf(ItemStack it) {
        if (it == null || it.getItemMeta() == null) return null;
        String raw = it.getItemMeta().getPersistentDataContainer()
                .get(targetKey(), org.bukkit.persistence.PersistentDataType.STRING);
        if (raw != null) {
            try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { }
        }
        if (!(it.getItemMeta() instanceof SkullMeta sm)) return null;
        OfflinePlayer owner = sm.getOwningPlayer();
        return owner == null ? null : owner.getUniqueId();
    }

    private ItemStack head(OfflinePlayer who, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(who);
        // A Bedrock player's head has no profile to resolve, so the skull can
        // come back ownerless and the click would quietly do nothing.
        if (who != null) meta.getPersistentDataContainer()
                .set(targetKey(), org.bukkit.persistence.PersistentDataType.STRING,
                        who.getUniqueId().toString());
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

    // ---------------- tabs ----------------

    public void open(Player p, Tab tab, UUID subject) {
        switch (tab) {
            case DASHBOARD -> dashboard(p);
            case PLAYERS -> players(p);
            case WORLD -> world(p);
            case ECONOMY -> economy(p);
            case SYSTEMS -> systems(p);
            case BANS -> bans(p);
            case PLAYER_DETAIL -> playerDetail(p, subject);
        }
    }

    private void dashboard(Player p) {
        Inventory inv = shell(p, Tab.DASHBOARD, null, "<dark_gray>แผงแอดมิน <gray>· แดชบอร์ด");
        double[] tps = Bukkit.getTPS();
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L;
        long max = Runtime.getRuntime().maxMemory() / 1048576L;
        int entities = 0, chunks = 0;
        for (World w : Bukkit.getWorlds()) { entities += w.getEntityCount(); chunks += w.getLoadedChunks().length; }

        inv.setItem(22, item(Material.COMPARATOR, "<gold>สถานะเซิร์ฟ", List.of(
                "<gray>TPS <white>" + String.format(Locale.US, "%.1f", Math.min(20, tps[0])),
                "<gray>แรม <white>" + used + "<dark_gray>/" + max + " MB",
                "<gray>ผู้เล่น <white>" + Bukkit.getOnlinePlayers().size(),
                "<gray>เอนทิตี <white>" + entities,
                "<gray>ชังก์ <white>" + chunks,
                "<gray>ดันเจียนที่กำลังเล่น <white>" + plugin.dungeons().activeRuns()), true));

        inv.setItem(19, item(Material.BELL, "<yellow>ประกาศ", List.of("<gray>พิมพ์ /say <ข้อความ>"), false));
        inv.setItem(20, item(Material.GOLDEN_APPLE, "<green>รักษาทุกคน", List.of("<gray>เต็มเลือดและความอิ่ม"), false));
        inv.setItem(21, item(Material.COOKED_BEEF, "<green>เติมความอิ่มทุกคน", List.of(), false));
        inv.setItem(23, item(Material.ICE, "<aqua>แช่แข็งทุกคน", List.of("<gray>สลับเปิด/ปิด ขยับไม่ได้"), false));
        inv.setItem(24, item(Material.FEATHER, "<white>บินให้ตัวเอง", List.of("<gray>สลับเปิด/ปิด"), p.getAllowFlight()));
        inv.setItem(25, item(Material.TOTEM_OF_UNDYING, "<gold>อมตะให้ตัวเอง",
                List.of("<gray>สลับเปิด/ปิด"), p.isInvulnerable()));

        inv.setItem(28, item(Material.ENDER_CHEST, "<green>สำรองข้อมูลเดี๋ยวนี้", List.of(), false));
        inv.setItem(29, item(Material.PAPER, "<yellow>โหลดค่าใหม่", List.of("<gray>อ่านไฟล์ตั้งค่าทุกไฟล์ใหม่"), false));
        inv.setItem(30, item(Material.ITEM_FRAME, "<aqua>แผงข้างจอ", List.of("<gray>สลับเปิด/ปิด"), false));
        inv.setItem(31, item(Material.CLOCK, "<white>กลางวัน", List.of(), false));
        inv.setItem(32, item(Material.BLACK_BED, "<blue>กลางคืน", List.of(), false));
        inv.setItem(33, item(Material.SUNFLOWER, "<yellow>ฟ้าใส", List.of(), false));
        inv.setItem(34, item(Material.WATER_BUCKET, "<aqua>ฝนตก", List.of(), false));
        p.openInventory(inv);
    }

    private void players(Player p) {
        Inventory inv = shell(p, Tab.PLAYERS, null, "<dark_gray>แผงแอดมิน <gray>· ผู้เล่น ("
                + Bukkit.getOnlinePlayers().size() + ")");
        int slot = 18;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (slot > 53) break;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>เลือด <white>" + (int) other.getHealth() + "<dark_gray>/"
                    + (int) other.getAttribute(attributeMaxHealth()).getValue());
            lore.add("<gray>โหมด <white>" + other.getGameMode().name().toLowerCase(Locale.ROOT));
            lore.add("<gray>เงิน <gold>" + plugin.money().format(plugin.money().get(other.getUniqueId())));
            var rank = plugin.ranks().rankOf(other.getUniqueId());
            lore.add("<gray>ยศ " + (rank == null ? "<white>-" : rank.display));
            lore.add("<gray>โลก <white>" + other.getWorld().getName());
            if (frozen.contains(other.getUniqueId())) lore.add("<aqua>ถูกแช่แข็งอยู่");
            if (plugin.punishments().isMuted(other.getUniqueId())) lore.add("<red>ถูกปิดปากอยู่");
            lore.add("");
            lore.add("<yellow>คลิกเพื่อเปิดเมนูจัดการ");
            inv.setItem(slot++, head(other, "<yellow>" + other.getName(), lore, false));
        }
        if (slot == 18) inv.setItem(31, item(Material.BARRIER, "<gray>ไม่มีใครออนไลน์", List.of(), false));
        p.openInventory(inv);
    }

    private org.bukkit.attribute.Attribute attributeMaxHealth() {
        for (String n : new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"}) {
            try { return org.bukkit.attribute.Attribute.valueOf(n); }
            catch (IllegalArgumentException | NoSuchFieldError ignored) { }
        }
        return null;
    }

    private void playerDetail(Player viewer, UUID subject) {
        Player t = Bukkit.getPlayer(subject);
        if (t == null) { players(viewer); return; }
        Inventory inv = shell(viewer, Tab.PLAYER_DETAIL, subject, "<dark_gray>จัดการ <gray>· " + t.getName());

        inv.setItem(22, head(t, "<yellow>" + t.getName(), List.of(
                "<gray>เงิน <gold>" + plugin.money().format(plugin.money().get(subject)),
                "<gray>เขต <white>" + plugin.store().zonesOf(subject).size(),
                "<gray>บ้าน <white>" + plugin.locations().homesOf(subject).size()), true));

        inv.setItem(19, item(Material.ENDER_PEARL, "<aqua>วาร์ปไปหา", List.of(), false));
        inv.setItem(20, item(Material.ENDER_EYE, "<aqua>เรียกมาหา", List.of(), false));
        inv.setItem(21, item(Material.GOLDEN_APPLE, "<green>รักษา", List.of(), false));
        inv.setItem(23, item(Material.ICE, "<aqua>แช่แข็ง", List.of("<gray>สลับเปิด/ปิด"),
                frozen.contains(subject)));
        inv.setItem(24, item(Material.CHEST, "<yellow>ดูกระเป๋า", List.of("<gray>ดูอย่างเดียว"), false));
        inv.setItem(25, item(Material.GRASS_BLOCK, "<white>สลับโหมดเกม",
                List.of("<gray>ตอนนี้ <white>" + t.getGameMode().name().toLowerCase(Locale.ROOT)), false));

        inv.setItem(28, item(Material.GOLD_INGOT, "<gold>ให้เงิน 10,000",
                List.of("<gray>คลิกขวา = หัก 10,000"), false));
        inv.setItem(29, item(Material.NAME_TAG, "<light_purple>ดูยศ", List.of("<gray>เปิดเมนูยศของเขา"), false));
        inv.setItem(30, item(Material.WITHER_SKELETON_SKULL, "<dark_red>รีเซ็ตคูลดาวน์ดันเจียน", List.of(), false));
        inv.setItem(31, item(Material.BOOK, "<yellow>ดูประวัติ", List.of("<gray>แสดงในแชท"), false));

        inv.setItem(39, item(Material.PAPER, "<yellow>เตือน", List.of("<gray>ส่งคำเตือน"), false));
        inv.setItem(40, item(Material.IRON_DOOR, "<gold>เตะ", List.of(), false));
        inv.setItem(41, item(Material.MUSIC_DISC_11, "<red>ปิดปาก 1 ชม.",
                List.of("<gray>คลิกขวา = ปลดปิดปาก"), false));
        inv.setItem(42, item(Material.BARRIER, "<dark_red>แบน 1 วัน",
                List.of("<gray>ชิฟต์+คลิก = แบนถาวร"), false));
        inv.setItem(18, item(Material.COOKED_BEEF, "<green>เติมความอิ่ม", List.of(), false));
        inv.setItem(26, item(Material.WOODEN_SWORD, "<red>ฆ่า",
                List.of("<gray>ชิฟต์+คลิกเพื่อยืนยัน"), false));
        inv.setItem(27, item(Material.COMPASS, "<aqua>ส่งกลับจุดเกิด", List.of(), false));
        inv.setItem(32, item(Material.FEATHER, "<aqua>บิน",
                List.of("<gray>สลับเปิด/ปิด"), t.getAllowFlight()));
        inv.setItem(33, item(Material.TOTEM_OF_UNDYING, "<gold>อมตะ",
                List.of("<gray>สลับเปิด/ปิด"), t.isInvulnerable()));
        inv.setItem(34, item(Material.CAULDRON, "<red>ล้างกระเป๋า",
                List.of("<gray>ชิฟต์+คลิกเพื่อยืนยัน"), false));
        inv.setItem(43, item(Material.LIME_DYE, "<green>ล้างบทลงโทษทั้งหมด",
                List.of("<gray>ปลดทุกอย่างที่ค้างอยู่ของคนนี้"), false));

        inv.setItem(45, item(Material.ARROW, "<white>กลับไปรายชื่อ", List.of(), false));
        viewer.openInventory(inv);
    }

    private void world(Player p) {
        Inventory inv = shell(p, Tab.WORLD, null, "<dark_gray>แผงแอดมิน <gray>· โลก");
        int slot = 19;
        for (World w : Bukkit.getWorlds()) {
            if (slot > 43) break;
            Material icon = switch (w.getEnvironment()) {
                case NETHER -> Material.NETHERRACK;
                case THE_END -> Material.END_STONE;
                default -> Material.GRASS_BLOCK;
            };
            inv.setItem(slot++, item(icon, "<yellow>" + w.getName(), List.of(
                    "<gray>ผู้เล่น <white>" + w.getPlayers().size(),
                    "<gray>เอนทิตี <white>" + w.getEntityCount(),
                    "<gray>ชังก์ <white>" + w.getLoadedChunks().length,
                    "<gray>เวลา <white>" + w.getTime(),
                    "<gray>ขอบโลก <white>" + (int) w.getWorldBorder().getSize(),
                    "",
                    "<yellow>คลิกเพื่อวาร์ปไป"), false));
        }
        p.openInventory(inv);
    }

    private void economy(Player p) {
        Inventory inv = shell(p, Tab.ECONOMY, null, "<dark_gray>แผงแอดมิน <gray>· เศรษฐกิจ");
        inv.setItem(22, item(Material.GOLD_BLOCK, "<gold>ภาพรวม", List.of(
                "<gray>เงินรวมในเซิร์ฟ <gold>" + plugin.money().format(plugin.money().totalMoney()),
                "<gray>จำนวนบัญชี <white>" + plugin.money().accountCount(),
                "<gray>ของวางขายในตลาด <white>" + plugin.market().listingCount(),
                "<dark_gray>เงินรวมโตเร็วคือสัญญาณเงินเฟ้อ"), true));
        List<Map.Entry<UUID, Double>> top = plugin.money().top(10);
        int slot = 28;
        int rank = 1;
        for (Map.Entry<UUID, Double> e : top) {
            if (slot > 43) break;
            inv.setItem(slot++, head(Bukkit.getOfflinePlayer(e.getKey()),
                    "<yellow>" + rank + ". " + plugin.money().nameOf(e.getKey()),
                    List.of("<gold>" + plugin.money().format(e.getValue())), rank <= 3));
            rank++;
        }
        p.openInventory(inv);
    }

    private void systems(Player p) {
        Inventory inv = shell(p, Tab.SYSTEMS, null, "<dark_gray>แผงแอดมิน <gray>· ระบบ");
        inv.setItem(20, item(Material.BEACON, "<light_purple>เขตป้องกัน", List.of(
                "<gray>ทั้งหมด <white>" + plugin.store().all().size() + " <gray>เขต"), false));
        inv.setItem(21, item(Material.DIAMOND, "<aqua>ภารกิจ", List.of(
                "<gray>มี <white>" + plugin.missions().missions().size() + " <gray>อย่าง"), false));
        inv.setItem(22, item(Material.CHEST, "<yellow>กล่องสุ่ม",
                List.of("<gray>/crate key <กล่อง> <ผู้เล่น> [จำนวน]"), false));
        inv.setItem(23, item(Material.NETHERITE_SWORD, "<gold>ไอเทมของเซิร์ฟ",
                List.of("<gray>เปิดรายการและหยิบมาทดสอบ"), false));
        inv.setItem(24, item(Material.WITHER_SKELETON_SKULL, "<dark_red>ดันเจียน", List.of(
                "<gray>กำลังเล่น <white>" + plugin.dungeons().activeRuns() + " <gray>รอบ",
                "<gray>คลิกเพื่อตั้งจุดเริ่มตรงนี้"), false));
        inv.setItem(30, item(Material.ENDER_PEARL, "<aqua>วาร์ป",
                List.of("<gray>/setwarp <ชื่อ> [ICON]"), false));
        inv.setItem(31, item(Material.COMPASS, "<yellow>ตั้งจุดเกิดตรงนี้", List.of(), false));
        inv.setItem(32, item(Material.VILLAGER_SPAWN_EGG, "<green>ตลาดกลาง",
                List.of("<gray>ของวางขาย <white>" + plugin.market().listingCount()), false));
        int drops = 0, mobs = 0;
        for (World w : Bukkit.getWorlds())
            for (var en : w.getEntities()) {
                if (en instanceof org.bukkit.entity.Item) drops++;
                else if (en instanceof org.bukkit.entity.Monster) mobs++;
            }

        inv.setItem(38, item(Material.HOPPER, "<yellow>เก็บของที่ตกอยู่",
                List.of("<gray>ตอนนี้มี <white>" + drops + " <gray>ชิ้น",
                        "<gray>ชิฟต์+คลิกเพื่อยืนยัน"), false));
        inv.setItem(39, item(Material.ZOMBIE_HEAD, "<red>ล้างมอนสเตอร์",
                List.of("<gray>ตอนนี้มี <white>" + mobs + " <gray>ตัว",
                        "<gray>ชิฟต์+คลิกเพื่อยืนยัน"), false));
        inv.setItem(40, item(Material.WRITABLE_BOOK, "<aqua>ล้างแชต", List.of(), false));
        inv.setItem(41, item(Material.IRON_BARS, "<gold>ล็อก / ปลดล็อกแชต", List.of(), false));
        inv.setItem(42, item(Material.PAPER, "<yellow>ไวต์ลิสต์",
                List.of("<gray>ตอนนี้ <white>" + (Bukkit.hasWhitelist() ? "เปิด" : "ปิด"),
                        "<gray>คลิกเพื่อสลับ"), Bukkit.hasWhitelist()));
        inv.setItem(43, item(Material.ENDER_CHEST, "<green>บันทึกทุกอย่าง", List.of(), false));
        inv.setItem(44, item(Material.STRUCTURE_BLOCK, "<gold>สำรองข้อมูลเดี๋ยวนี้",
                List.of("<gray>ชิฟต์+คลิกเพื่อยืนยัน"), false));
        p.openInventory(inv);
    }

    private void bans(Player p) {
        Inventory inv = shell(p, Tab.BANS, null, "<dark_gray>แผงแอดมิน <gray>· รายชื่อลงโทษ");
        int slot = 18;
        List<Punishments.Entry> list = plugin.punishments().history();
        for (int i = list.size() - 1; i >= 0 && slot <= 53; i--) {
            Punishments.Entry e = list.get(i);
            if (e.lifted) continue;
            if (e.type != Punishments.Type.BAN && e.type != Punishments.Type.MUTE) continue;
            if (e.until != 0 && e.until < System.currentTimeMillis()) continue;
            inv.setItem(slot++, head(Bukkit.getOfflinePlayer(e.target),
                    (e.type == Punishments.Type.BAN ? "<red>" : "<gold>") + e.targetName,
                    List.of("<gray>ประเภท <white>" + (e.type == Punishments.Type.BAN ? "แบน" : "ปิดปาก"),
                            "<gray>เหตุผล <white>" + e.reason,
                            "<gray>โดย <white>" + e.staff,
                            "<gray>เหลือ <white>" + Punishments.readable(e.until),
                            "",
                            "<yellow>คลิกเพื่อปลด"), false));
        }
        if (slot == 18) inv.setItem(31, item(Material.LIME_DYE, "<green>ไม่มีใครโดนลงโทษอยู่", List.of(), false));
        p.openInventory(inv);
    }

    // ---------------- clicks ----------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("zones.admin")) return;
        int slot = e.getRawSlot();

        if (slot >= 0 && slot <= 5) {
            open(p, Tab.values()[slot], null);
            return;
        }
        if (slot == 8) { open(p, holder.tab, holder.subject); return; }

        switch (holder.tab) {
            case DASHBOARD -> dashboardClick(p, slot);
            case PLAYERS -> {
                UUID who = targetOf(e.getCurrentItem());
                if (who != null) playerDetail(p, who);
            }
            case PLAYER_DETAIL -> detailClick(p, holder.subject, slot, e.getClick());
            case WORLD -> {
                ItemStack it = e.getCurrentItem();
                if (it == null || it.getItemMeta() == null) return;
                String name = plain(it);
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    p.closeInventory();
                    p.teleport(w.getSpawnLocation());
                    Notify.bar(p, "<green>วาร์ปไปโลก " + name, "entity.enderman.teleport", 1.3f);
                }
            }
            case SYSTEMS -> systemsClick(p, slot, e.getClick().isShiftClick());
            case BANS -> {
                UUID who = targetOf(e.getCurrentItem());
                if (who == null) return;
                boolean done = plugin.punishments().lift(who, Punishments.Type.BAN);
                done |= plugin.punishments().lift(who, Punishments.Type.MUTE);
                String name = plugin.punishments().nameOf(who);
                Notify.bar(p, done
                        ? "<green>ปลด " + (name == null ? "" : name) + " แล้ว"
                        : "<gray>ไม่มีอะไรให้ปลด", "block.note_block.pling", 1.3f);
                bans(p);
            }
            default -> { }
        }
    }

    private String plain(ItemStack it) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Objects.requireNonNull(it.getItemMeta().displayName()));
    }

    private void dashboardClick(Player p, int slot) {
        switch (slot) {
            case 19 -> { p.closeInventory(); Notify.chat(p, "<gray>พิมพ์ <white>/say <ข้อความ> <gray>เพื่อประกาศ"); }
            case 20 -> {
                for (Player t : Bukkit.getOnlinePlayers()) {
                    t.setHealth(t.getAttribute(attributeMaxHealth()).getValue());
                    t.setFoodLevel(20);
                    Notify.bar(t, "<green>ทีมงานรักษาให้ทุกคนแล้ว", "entity.player.levelup", 1.2f);
                }
                Notify.bar(p, "<green>รักษาทุกคนแล้ว", null, 1f);
            }
            case 21 -> {
                for (Player t : Bukkit.getOnlinePlayers()) t.setFoodLevel(20);
                Notify.bar(p, "<green>เติมความอิ่มทุกคนแล้ว", null, 1f);
            }
            case 23 -> {
                boolean freezing = frozen.isEmpty();
                frozen.clear();
                if (freezing) for (Player t : Bukkit.getOnlinePlayers())
                    if (!t.hasPermission("zones.admin")) frozen.add(t.getUniqueId());
                Bukkit.broadcast(Notify.of(freezing ? "<aqua>ทีมงานแช่แข็งทุกคนชั่วคราว" : "<green>ปลดการแช่แข็งแล้ว"));
                dashboard(p);
            }
            case 24 -> {
                p.setAllowFlight(!p.getAllowFlight());
                p.setFlying(p.getAllowFlight());
                dashboard(p);
            }
            case 25 -> { p.setInvulnerable(!p.isInvulnerable()); dashboard(p); }
            case 28 -> { p.closeInventory(); plugin.backups().run(p, true); }
            case 29 -> {
                plugin.readConfig();
                plugin.missions().loadDefinitions();
                plugin.menus().load();
                plugin.shop().load();
                plugin.ranks().load();
                plugin.crates().load();
                plugin.customItems().load();
                Notify.bar(p, "<green>โหลดค่าใหม่แล้ว", "block.note_block.pling", 1.4f);
                dashboard(p);
            }
            case 30 -> { p.closeInventory(); p.performCommand("adminhud"); }
            case 31 -> { p.getWorld().setTime(1000); Notify.bar(p, "<green>ตั้งเป็นกลางวันแล้ว", null, 1f); }
            case 32 -> { p.getWorld().setTime(14000); Notify.bar(p, "<green>ตั้งเป็นกลางคืนแล้ว", null, 1f); }
            case 33 -> { p.getWorld().setStorm(false); p.getWorld().setThundering(false);
                         Notify.bar(p, "<green>ฟ้าใสแล้ว", null, 1f); }
            case 34 -> { p.getWorld().setStorm(true); Notify.bar(p, "<green>ให้ฝนตกแล้ว", null, 1f); }
            default -> { }
        }
    }

    private void detailClick(Player p, UUID subject, int slot, ClickType click) {
        Player t = Bukkit.getPlayer(subject);
        if (t == null) { players(p); return; }
        switch (slot) {
            case 19 -> { p.closeInventory(); p.teleport(t.getLocation()); }
            case 20 -> { p.closeInventory(); t.teleport(p.getLocation());
                         Notify.bar(t, "<yellow>ทีมงานเรียกคุณมา", "entity.enderman.teleport", 1.2f); }
            case 21 -> { t.setHealth(t.getAttribute(attributeMaxHealth()).getValue()); t.setFoodLevel(20);
                         Notify.bar(p, "<green>รักษาแล้ว", null, 1f); }
            case 23 -> {
                if (!frozen.remove(subject)) frozen.add(subject);
                Notify.bar(t, frozen.contains(subject) ? "<aqua>คุณถูกแช่แข็งโดยทีมงาน" : "<green>ปลดแช่แข็งแล้ว",
                        "block.note_block.bass", 1f);
                playerDetail(p, subject);
            }
            case 24 -> p.openInventory(t.getInventory());
            case 25 -> {
                GameMode next = switch (t.getGameMode()) {
                    case SURVIVAL -> GameMode.CREATIVE;
                    case CREATIVE -> GameMode.ADVENTURE;
                    case ADVENTURE -> GameMode.SPECTATOR;
                    case SPECTATOR -> GameMode.SURVIVAL;
                };
                t.setGameMode(next);
                playerDetail(p, subject);
            }
            case 28 -> {
                if (click.isRightClick()) plugin.money().take(subject, 10000);
                else plugin.money().add(subject, 10000);
                plugin.money().save(false);
                playerDetail(p, subject);
            }
            case 29 -> { p.closeInventory(); p.performCommand("rank"); }
            case 30 -> { plugin.dungeons().clearCooldown(subject);
                         Notify.bar(p, "<green>รีเซ็ตคูลดาวน์ดันเจียนแล้ว", null, 1f); }
            case 31 -> { p.closeInventory(); p.performCommand("history " + t.getName()); }
            case 39 -> { plugin.punishments().punish(subject, t.getName(), Punishments.Type.WARN,
                            "ทีมงานเตือน", p.getName(), 0); playerDetail(p, subject); }
            case 40 -> { plugin.punishments().punish(subject, t.getName(), Punishments.Type.KICK,
                            "ทีมงานเตะ", p.getName(), 0); players(p); }
            case 41 -> {
                if (click.isRightClick()) plugin.punishments().lift(subject, Punishments.Type.MUTE);
                else plugin.punishments().punish(subject, t.getName(), Punishments.Type.MUTE,
                        "ทีมงานปิดปาก", p.getName(), 3_600_000L);
                playerDetail(p, subject);
            }
            case 42 -> {
                long dur = click.isShiftClick() ? 0 : 86_400_000L;
                plugin.punishments().punish(subject, t.getName(), Punishments.Type.BAN,
                        "ทีมงานแบน", p.getName(), dur);
                players(p);
            }
            case 18 -> { t.setFoodLevel(20); t.setSaturation(20f);
                         Notify.bar(p, "<green>เติมความอิ่มแล้ว", null, 1f); }
            case 26 -> {
                if (!click.isShiftClick()) { Notify.bar(p, "<yellow>ชิฟต์+คลิกเพื่อยืนยัน", null, 1f); return; }
                t.setHealth(0);
                Notify.bar(p, "<red>ฆ่า " + t.getName() + " แล้ว", null, 1f);
                players(p);
            }
            case 27 -> {
                Location spawn = plugin.locations().spawn();
                if (spawn == null) spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
                t.teleportAsync(spawn);
                Notify.bar(p, "<green>ส่งกลับจุดเกิดแล้ว", null, 1f);
            }
            case 32 -> {
                boolean on = !t.getAllowFlight();
                t.setAllowFlight(on);
                if (!on) t.setFlying(false);
                Notify.bar(t, on ? "<aqua>เปิดโหมดบินให้แล้ว" : "<gray>ปิดโหมดบินแล้ว", null, 1f);
                playerDetail(p, subject);
            }
            case 33 -> {
                t.setInvulnerable(!t.isInvulnerable());
                Notify.bar(t, t.isInvulnerable() ? "<gold>อมตะแล้ว" : "<gray>เลิกอมตะ", null, 1f);
                playerDetail(p, subject);
            }
            case 34 -> {
                if (!click.isShiftClick()) { Notify.bar(p, "<yellow>ชิฟต์+คลิกเพื่อยืนยัน", null, 1f); return; }
                t.getInventory().clear();
                Notify.chat(t, "<red>ทีมงานล้างกระเป๋าของคุณ");
                Notify.bar(p, "<green>ล้างกระเป๋าแล้ว", null, 1f);
            }
            case 43 -> {
                int n = 0;
                for (Punishments.Entry pe : plugin.punishments().history())
                    if (pe.target.equals(subject) && !pe.lifted) { pe.lifted = true; n++; }
                plugin.punishments().save(true);
                Notify.bar(p, "<green>ล้างบทลงโทษไป " + n + " รายการ", "block.note_block.pling", 1.3f);
                playerDetail(p, subject);
            }
            case 45 -> players(p);
            default -> { }
        }
    }

    private void systemsClick(Player p, int slot, boolean shift) {
        switch (slot) {
            case 20 -> { p.closeInventory(); p.performCommand("zone list"); }
            case 21 -> { p.closeInventory(); p.performCommand("mission"); }
            case 22 -> { p.closeInventory(); p.performCommand("crate list"); }
            case 23 -> { p.closeInventory(); p.performCommand("citem list"); }
            case 24 -> { p.closeInventory(); p.performCommand("dungeon setspawn"); }
            case 30 -> { p.closeInventory(); Notify.chat(p, "<gray>ใช้ <white>/setwarp <ชื่อ> [ICON]"); }
            case 31 -> { p.closeInventory(); p.performCommand("setspawn"); }
            case 32 -> { p.closeInventory(); p.performCommand("market"); }
            case 38 -> {
                if (!shift) { Notify.bar(p, "<yellow>ชิฟต์+คลิกเพื่อยืนยัน", null, 1f); return; }
                int n = 0;
                for (World w : Bukkit.getWorlds())
                    for (var en : w.getEntities())
                        if (en instanceof org.bukkit.entity.Item) { en.remove(); n++; }
                Notify.bar(p, "<green>เก็บของตกพื้น " + n + " ชิ้น", "entity.item.pickup", 1.2f);
                systems(p);
            }
            case 39 -> {
                if (!shift) { Notify.bar(p, "<yellow>ชิฟต์+คลิกเพื่อยืนยัน", null, 1f); return; }
                int n = 0;
                for (World w : Bukkit.getWorlds())
                    for (var en : w.getEntities())
                        if (en instanceof org.bukkit.entity.Monster) { en.remove(); n++; }
                Notify.bar(p, "<green>ล้างมอนสเตอร์ " + n + " ตัว", null, 1f);
                systems(p);
            }
            case 40 -> { p.closeInventory(); p.performCommand("chat clear"); }
            case 41 -> { p.closeInventory(); p.performCommand("chat lock"); }
            case 42 -> {
                boolean on = !Bukkit.hasWhitelist();
                Bukkit.setWhitelist(on);
                Bukkit.setWhitelistEnforced(on);
                Notify.bar(p, on ? "<green>เปิดไวต์ลิสต์แล้ว" : "<yellow>ปิดไวต์ลิสต์แล้ว", null, 1f);
                systems(p);
            }
            case 43 -> {
                plugin.store().save();
                plugin.money().save(true);
                plugin.missions().save(true);
                plugin.punishments().save(true);
                plugin.chat().save(true);
                plugin.jobs().save(true);
                for (World w : Bukkit.getWorlds()) w.save();
                Notify.bar(p, "<green>บันทึกทุกอย่างแล้ว", "block.note_block.pling", 1.4f);
            }
            case 44 -> {
                if (!shift) { Notify.bar(p, "<yellow>ชิฟต์+คลิกเพื่อยืนยัน", null, 1f); return; }
                p.closeInventory();
                plugin.backups().run(p, true);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        e.setTo(e.getFrom());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("zones.admin.admin")) {
            Notify.chat(p, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น"); return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("web")) {
            if (!plugin.getConfig().getBoolean("web.enabled", false)) {
                Notify.chat(p, "<red>แผงหน้าเว็บปิดอยู่ <gray>เปิดได้ที่ config.yml → web.enabled");
                return true;
            }
            String code = plugin.webPanel().issueCode(p);
            long minutes = plugin.getConfig().getLong("web.code-minutes", 5);
            String url = plugin.getConfig().getString("web.public-url", "");
            if (url == null || url.isBlank())
                url = "http://<ไอพีเซิร์ฟ>:" + plugin.getConfig().getInt("web.port", 8765);

            Notify.chat(p, "<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
            Notify.chat(p, "<gold>แผงควบคุมหน้าเว็บ");
            Notify.chat(p, "<gray>ที่อยู่ <white>" + url);
            p.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gray>รหัสของคุณ <bold><yellow>" + code
                            + "</yellow></bold> <dark_gray>(กดเพื่อคัดลอก)")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(code))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                            MiniMessage.miniMessage().deserialize("<yellow>กดเพื่อคัดลอกรหัส"))));
            Notify.chat(p, "<dark_gray>ใช้ได้ครั้งเดียว หมดอายุใน " + minutes + " นาที");
            Notify.chat(p, "<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
            p.playSound(p.getLocation(), "block.note_block.bell", 0.7f, 1.4f);
            return true;
        }

        open(p, Tab.DASHBOARD, null);
        return true;
    }
}
