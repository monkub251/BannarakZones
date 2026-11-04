package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * สันทนาการ — the staff toybox: fetch and teleport, pranks, and the pieces
 * you need to run an event (countdown, prize draw, fireworks).
 *
 * <p>Nothing here takes a life or destroys a build. A prank wears off on its
 * own, and every one of them announces itself to the person it lands on, so
 * nobody is left wondering whether the server is broken.
 */
public class FunTools implements CommandExecutor, TabCompleter {

    private final BannarakZones plugin;
    private final Random rng = new Random();

    public FunTools(BannarakZones plugin) { this.plugin = plugin; }

    // ---------------------------------------------------------------
    // Registry lookups by reflection.
    //
    // Mojang renames these constants between versions (CONFUSION became
    // NAUSEA, EXPLOSION_LARGE became EXPLOSION). Naming one directly would
    // compile today and break on the next update, so we ask for a list of
    // names and take the first that this server actually has.
    // ---------------------------------------------------------------

    private static <T> T lookup(Class<T> type, String... names) {
        for (String n : names) {
            try {
                Field f = type.getField(n);
                Object v = f.get(null);
                if (type.isInstance(v)) return type.cast(v);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return null;
    }

    private static PotionEffectType effect(String... names) {
        return lookup(PotionEffectType.class, names);
    }

    private static final PotionEffectType NAUSEA     = effect("NAUSEA", "CONFUSION");
    private static final PotionEffectType BLINDNESS  = effect("BLINDNESS");
    private static final PotionEffectType LEVITATION = effect("LEVITATION");
    private static final PotionEffectType SLOWNESS   = effect("SLOWNESS", "SLOW");
    private static final PotionEffectType SPEED      = effect("SPEED");
    private static final PotionEffectType JUMP       = effect("JUMP_BOOST", "JUMP");
    private static final PotionEffectType GLOWING    = effect("GLOWING");
    private static final PotionEffectType INVIS      = effect("INVISIBILITY");
    private static final PotionEffectType DARKNESS   = effect("DARKNESS");

    private static final Particle P_FLAME  = lookup(Particle.class, "FLAME");
    private static final Particle P_HEART  = lookup(Particle.class, "HEART");
    private static final Particle P_HAPPY  = lookup(Particle.class, "HAPPY_VILLAGER", "VILLAGER_HAPPY");
    private static final Particle P_BOOM   = lookup(Particle.class, "EXPLOSION", "EXPLOSION_LARGE");
    private static final Particle P_TOTEM  = lookup(Particle.class, "TOTEM_OF_UNDYING", "TOTEM");
    private static final Particle P_NOTE   = lookup(Particle.class, "NOTE");

    /** The scale attribute, looked up the same way — it arrived in 1.20.5. */
    private static final Object ATTR_SCALE = lookup(org.bukkit.attribute.Attribute.class, "SCALE", "GENERIC_SCALE");

    // ---------------------------------------------------------------
    // Prank presets. Each one is a short name people can actually type.
    // ---------------------------------------------------------------

    /** Every preset {@code /prank} accepts, in the order the menu shows them. */
    public static final List<String> PRESETS = List.of(
            "หน้ามืด", "มืดสนิท", "เมา", "ลอย", "ช้า", "เร็ว", "ล่องหน", "เรืองแสง",
            "กระโดดสูง", "ตัวเล็ก", "ตัวใหญ่", "หมุน", "ระเบิดปลอม", "ผีหลอก",
            "ฟ้าผ่า", "ไฟลุก", "สลับของ", "ยิงขึ้นฟ้า", "มอบรุม", "แบนปลอม",
            "opปลอม", "เสียงดัง", "พลุ", "หัวใจ", "จั๊กจี้");

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player p)) {
            sender.sendMessage("คำสั่งนี้ใช้ในเกมเท่านั้น");
            return true;
        }
        if (!p.hasPermission("zones.fun")) {
            Notify.chat(p, "<red>คุณไม่มีสิทธิ์ใช้คำสั่งนี้");
            return true;
        }

        switch (name) {
            case "fun"       -> plugin.adminPanel().open(p, AdminPanel.Tab.FUN, null);
            case "bring"     -> bring(p, a);
            case "goto"      -> goTo(p, a);
            case "tpall"     -> tpAll(p);
            case "swap"      -> swap(p, a);
            case "scatter"   -> scatter(p, a);
            case "launch"    -> launch(p, a);
            case "freeze"    -> freeze(p, a);
            case "smite"     -> smite(p, a);
            case "burn"      -> burn(p, a);
            case "shuffle"   -> shuffle(p, a);
            case "spin"      -> spin(p, a);
            case "size"      -> size(p, a);
            case "potion"    -> potion(p, a);
            case "playsound" -> playSound(p, a);
            case "prank"     -> prankCmd(p, a);
            case "mobs"      -> mobs(p, a);
            case "ride"      -> ride(p, a);
            case "heal"      -> heal(p, a);
            case "feed"      -> feed(p, a);
            case "fakeban"   -> fakeBan(p, a);
            case "fakeop"    -> fakeOp(p, a);
            case "fakejoin"  -> fakeJoin(p, a, true);
            case "fakeleave" -> fakeJoin(p, a, false);
            case "jumpscare" -> jumpscare(p, a);
            case "party"     -> party(p);
            case "firework"  -> fireworkCmd(p, a);
            case "confetti"  -> confetti(p, a);
            case "countdown" -> countdown(p, a);
            case "roll"      -> roll(p, a);
            case "pick"      -> pick(p, a);
            case "announce"  -> announce(p, a);
            case "disco"     -> disco(p, a);
            default -> { return false; }
        }
        return true;
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** Finds an online player, telling the sender plainly when there isn't one. */
    private Player target(Player p, String[] a, int index) {
        if (a.length <= index) {
            Notify.chat(p, "<gray>ต้องใส่ชื่อผู้เล่นด้วย");
            return null;
        }
        Player t = Bukkit.getPlayerExact(a[index]);
        if (t == null) {
            Notify.chat(p, "<red>ไม่พบ <white>" + a[index] + " <red>ในเซิร์ฟ");
            return null;
        }
        return t;
    }

    private int intArg(String[] a, int index, int fallback, int min, int max) {
        if (a.length <= index) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(a[index])));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double doubleArg(String[] a, int index, double fallback, double min, double max) {
        if (a.length <= index) return fallback;
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(a[index])));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void apply(Player t, PotionEffectType type, int seconds, int amp) {
        if (type == null) return;
        t.addPotionEffect(new PotionEffect(type, Math.max(1, seconds) * 20, Math.max(0, amp), false, false, false));
    }

    private void particles(Player t, Particle which, int count, double spread) {
        if (which == null) return;
        t.getWorld().spawnParticle(which, t.getLocation().add(0, 1, 0), count, spread, spread, spread, 0.02);
    }

    /** Tells staff what happened, and the victim that it was a prank, not a bug. */
    private void told(Player staff, Player t, String what) {
        Notify.bar(staff, "<gold>" + what + " <gray>→ <white>" + t.getName(), "block.note_block.pling", 1.4f);
        Notify.chat(t, "<light_purple>ทีมงานแกล้งคุณ <gray>(" + what + ") <dark_gray>เดี๋ยวก็หาย");
    }

    private void toAll(String mini) {
        Bukkit.broadcast(Notify.of(mini));
    }

    // ---------------------------------------------------------------
    // teleport and fetch
    // ---------------------------------------------------------------

    private void bring(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        t.teleport(p.getLocation());
        Notify.bar(p, "<green>ดึง <white>" + t.getName() + " <green>มาแล้ว", "entity.enderman.teleport", 1.2f);
        Notify.chat(t, "<aqua>ทีมงานดึงคุณมา");
    }

    private void goTo(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        p.teleport(t.getLocation());
        Notify.bar(p, "<green>ไปหา <white>" + t.getName(), "entity.enderman.teleport", 1.2f);
    }

    private void tpAll(Player p) {
        int n = 0;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(p)) continue;
            t.teleport(p.getLocation());
            n++;
        }
        toAll("<gold>ทีมงานเรียกทุกคนมารวมกัน <gray>(" + n + " คน)");
    }

    private void swap(Player p, String[] a) {
        Player x = target(p, a, 0);
        if (x == null) return;
        Player y = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
        if (y == null) {
            Notify.chat(p, "<red>ไม่พบ <white>" + a[1]);
            return;
        }
        Location lx = x.getLocation();
        x.teleport(y.getLocation());
        y.teleport(lx);
        Notify.bar(p, "<green>สลับที่ <white>" + x.getName() + " <gray>↔ <white>" + y.getName(),
                "entity.enderman.teleport", 1f);
    }

    private void scatter(Player p, String[] a) {
        int radius = intArg(a, 0, 30, 5, 200);
        int n = 0;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(p)) continue;
            double ang = rng.nextDouble() * Math.PI * 2;
            double d = 5 + rng.nextDouble() * (radius - 5);
            Location l = p.getLocation().clone().add(Math.cos(ang) * d, 0, Math.sin(ang) * d);
            l.setY(l.getWorld().getHighestBlockYAt(l) + 1);
            t.teleport(l);
            n++;
        }
        toAll("<gold>กระจายผู้เล่น " + n + " คน <gray>ในรัศมี " + radius + " บล็อก");
    }

    private void launch(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        double power = doubleArg(a, 1, 2.0, 0.5, 6.0);
        t.setVelocity(new Vector(0, power, 0));
        t.getWorld().playSound(t.getLocation(), "entity.generic.explode", 0.6f, 1.6f);
        told(p, t, "ยิงขึ้นฟ้า");
    }

    private void freeze(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        UUID id = t.getUniqueId();
        boolean on = !plugin.adminPanel().frozen().remove(id);
        if (on) plugin.adminPanel().frozen().add(id);
        Notify.bar(p, (on ? "<aqua>แช่แข็ง " : "<green>ปลดแช่แข็ง ") + "<white>" + t.getName(),
                "block.glass.break", on ? 0.8f : 1.5f);
        Notify.chat(t, on ? "<aqua>คุณถูกแช่แข็งโดยทีมงาน" : "<green>ปลดแช่แข็งแล้ว");
    }

    private void ride(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        if (p.getVehicle() != null) {
            p.leaveVehicle();
            Notify.bar(p, "<gray>ลงแล้ว", null, 1f);
            return;
        }
        t.addPassenger(p);
        told(p, t, "ขี่หัว");
    }

    // ---------------------------------------------------------------
    // pranks
    // ---------------------------------------------------------------

    private void smite(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        // The effect-only strike: all the noise and the flash, no damage and
        // nothing catches fire. A prank should not cost somebody their stuff.
        t.getWorld().strikeLightningEffect(t.getLocation());
        told(p, t, "ฟ้าผ่า");
    }

    private void burn(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        int sec = intArg(a, 1, 3, 1, 15);
        t.setFireTicks(sec * 20);
        told(p, t, "ไฟลุก " + sec + " วิ");
    }

    private void shuffle(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        ItemStack[] c = t.getInventory().getStorageContents();
        List<ItemStack> list = new ArrayList<>(java.util.Arrays.asList(c));
        Collections.shuffle(list, rng);
        t.getInventory().setStorageContents(list.toArray(new ItemStack[0]));
        t.playSound(t.getLocation(), "entity.item.pickup", 1f, 0.7f);
        told(p, t, "สลับของในกระเป๋า");
    }

    private void spin(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        final int[] step = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!t.isOnline() || step[0] >= 20) { task.cancel(); return; }
            Location l = t.getLocation();
            l.setYaw(l.getYaw() + 72);
            t.teleport(l);
            step[0]++;
        }, 1L, 2L);
        apply(t, NAUSEA, 6, 1);
        told(p, t, "หมุนตัว");
    }

    private void size(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        double scale = doubleArg(a, 1, 1.0, 0.25, 3.0);
        if (!setScale(t, scale)) {
            Notify.chat(p, "<gray>เซิร์ฟนี้ไม่มีค่าขนาดตัว (ต้อง 1.20.5 ขึ้นไป)");
            return;
        }
        told(p, t, "ปรับขนาดตัวเป็น " + scale + "x");
    }

    /** Returns false when this server has no scale attribute to set. */
    private boolean setScale(Player t, double scale) {
        if (ATTR_SCALE == null) return false;
        try {
            var inst = t.getAttribute((org.bukkit.attribute.Attribute) ATTR_SCALE);
            if (inst == null) return false;
            inst.setBaseValue(scale);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void potion(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        if (a.length < 2) {
            Notify.chat(p, "<gray>ใช้ <white>/potion <ชื่อ> <effect> [วินาที] [ระดับ]");
            return;
        }
        PotionEffectType type = effect(a[1].toUpperCase(Locale.ROOT));
        if (type == null) {
            Notify.chat(p, "<red>ไม่รู้จักสถานะ <white>" + a[1]
                    + " <gray>· ลอง NAUSEA BLINDNESS LEVITATION SLOWNESS SPEED GLOWING");
            return;
        }
        int sec = intArg(a, 2, 10, 1, 600);
        int amp = intArg(a, 3, 1, 1, 5) - 1;
        apply(t, type, sec, amp);
        told(p, t, a[1] + " " + sec + " วิ");
    }

    private void playSound(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        String key = a.length > 1 ? a[1] : "entity.enderman.scream";
        float pitch = (float) doubleArg(a, 2, 1.0, 0.5, 2.0);
        t.playSound(t.getLocation(), key, 1f, pitch);
        Notify.bar(p, "<green>เล่นเสียง <white>" + key, null, 1f);
    }

    private void mobs(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        String kind = a.length > 1 ? a[1].toUpperCase(Locale.ROOT) : "CHICKEN";
        int count = intArg(a, 2, 10, 1, 40);
        EntityType type;
        try {
            type = EntityType.valueOf(kind);
        } catch (IllegalArgumentException e) {
            Notify.chat(p, "<red>ไม่รู้จักมอบ <white>" + kind);
            return;
        }
        for (int i = 0; i < count; i++) {
            Location l = t.getLocation().clone().add(rng.nextDouble() * 4 - 2, 0.5, rng.nextDouble() * 4 - 2);
            var e = t.getWorld().spawnEntity(l, type);
            // A prank, not an ambush: they crowd round and make noise, but
            // nothing in this pile is allowed to hurt anybody.
            if (e instanceof LivingEntity le) {
                le.setRemoveWhenFarAway(true);
                le.setPersistent(false);
            }
        }
        told(p, t, count + " " + kind + " รุม");
    }

    private void jumpscare(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        t.playSound(t.getLocation(), "entity.enderman.scream", 1f, 0.6f);
        apply(t, DARKNESS, 4, 0);
        apply(t, BLINDNESS, 2, 0);
        particles(t, P_BOOM, 6, 0.6);
        told(p, t, "ผีหลอก");
    }

    private void fakeBan(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        t.sendMessage(Notify.of("<red><bold>คุณถูกแบนจากเซิร์ฟเวอร์"));
        t.sendMessage(Notify.of("<gray>เหตุผล: <white>โกง"));
        t.playSound(t.getLocation(), "entity.wither.spawn", 0.5f, 1.4f);
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                Notify.chat(t, "<green>ล้อเล่น <gray>ไม่ได้แบนจริง"), 60L);
        Notify.bar(p, "<gold>แบนปลอม <gray>→ <white>" + t.getName(), null, 1.4f);
    }

    private void fakeOp(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        t.sendMessage(Notify.of("<gray>Made " + t.getName() + " a server operator"));
        Bukkit.getScheduler().runTaskLater(plugin, () ->
                Notify.chat(t, "<green>ล้อเล่น <gray>ไม่ได้ให้ op จริง"), 60L);
        Notify.bar(p, "<gold>op ปลอม <gray>→ <white>" + t.getName(), null, 1.4f);
    }

    private void fakeJoin(Player p, String[] a, boolean joining) {
        if (a.length == 0) {
            Notify.chat(p, "<gray>ใส่ชื่อที่จะให้ประกาศด้วย");
            return;
        }
        toAll((joining ? "<yellow>" : "<yellow>") + a[0] + (joining ? " เข้าเซิร์ฟแล้ว" : " ออกจากเซิร์ฟ"));
        Notify.bar(p, "<green>ประกาศปลอมแล้ว", null, 1.2f);
    }

    /** The one place a preset name turns into something happening. */
    public void prank(Player staff, Player t, String preset) {
        switch (preset) {
            case "หน้ามืด"    -> { apply(t, BLINDNESS, 8, 0); told(staff, t, preset); }
            case "มืดสนิท"    -> { apply(t, DARKNESS, 15, 0); told(staff, t, preset); }
            case "เมา"        -> { apply(t, NAUSEA, 15, 1); told(staff, t, preset); }
            case "ลอย"        -> { apply(t, LEVITATION, 6, 1); told(staff, t, preset); }
            case "ช้า"        -> { apply(t, SLOWNESS, 15, 2); told(staff, t, preset); }
            case "เร็ว"       -> { apply(t, SPEED, 20, 4); told(staff, t, preset); }
            case "ล่องหน"     -> { apply(t, INVIS, 20, 0); told(staff, t, preset); }
            case "เรืองแสง"   -> { apply(t, GLOWING, 30, 0); told(staff, t, preset); }
            case "กระโดดสูง"  -> { apply(t, JUMP, 20, 4); told(staff, t, preset); }
            case "ตัวเล็ก"    -> size(staff, new String[]{t.getName(), "0.4"});
            case "ตัวใหญ่"    -> size(staff, new String[]{t.getName(), "2.2"});
            case "หมุน"       -> spin(staff, new String[]{t.getName()});
            case "ฟ้าผ่า"     -> smite(staff, new String[]{t.getName()});
            case "ไฟลุก"      -> burn(staff, new String[]{t.getName(), "4"});
            case "สลับของ"    -> shuffle(staff, new String[]{t.getName()});
            case "ยิงขึ้นฟ้า" -> launch(staff, new String[]{t.getName(), "2.5"});
            case "มอบรุม"     -> mobs(staff, new String[]{t.getName(), "CHICKEN", "15"});
            case "แบนปลอม"    -> fakeBan(staff, new String[]{t.getName()});
            case "opปลอม"     -> fakeOp(staff, new String[]{t.getName()});
            case "ผีหลอก"     -> jumpscare(staff, new String[]{t.getName()});
            case "ระเบิดปลอม" -> {
                t.getWorld().playSound(t.getLocation(), "entity.generic.explode", 1f, 1f);
                particles(t, P_BOOM, 8, 1.0);
                told(staff, t, preset);
            }
            case "เสียงดัง"   -> {
                t.playSound(t.getLocation(), "block.note_block.pling", 1f, 2f);
                particles(t, P_NOTE, 20, 0.8);
                told(staff, t, preset);
            }
            case "พลุ"        -> { firework(t.getLocation()); told(staff, t, preset); }
            case "หัวใจ"      -> { particles(t, P_HEART, 40, 1.0); told(staff, t, preset); }
            case "จั๊กจี้"     -> {
                apply(t, JUMP, 6, 3);
                apply(t, NAUSEA, 6, 0);
                particles(t, P_HAPPY, 30, 0.8);
                told(staff, t, preset);
            }
            default -> Notify.chat(staff, "<red>ไม่รู้จักแบบ <white>" + preset);
        }
    }

    private void prankCmd(Player p, String[] a) {
        Player t = target(p, a, 0);
        if (t == null) return;
        if (a.length < 2) {
            Notify.chat(p, "<gray>แบบที่มี: <white>" + String.join(" ", PRESETS));
            return;
        }
        prank(p, t, a[1]);
    }

    // ---------------------------------------------------------------
    // care
    // ---------------------------------------------------------------

    private void heal(Player p, String[] a) {
        Player t = a.length == 0 ? p : target(p, a, 0);
        if (t == null) return;
        var attr = lookup(org.bukkit.attribute.Attribute.class, "MAX_HEALTH", "GENERIC_MAX_HEALTH");
        var max = attr == null ? null : t.getAttribute(attr);
        t.setHealth(max == null ? 20 : max.getValue());
        t.setFireTicks(0);
        t.setFoodLevel(20);
        t.setSaturation(20);
        for (PotionEffect e : new ArrayList<>(t.getActivePotionEffects())) t.removePotionEffect(e.getType());
        particles(t, P_TOTEM, 20, 0.6);
        Notify.bar(p, "<green>รักษา <white>" + t.getName() + " <green>แล้ว", "entity.player.levelup", 1.4f);
        if (!t.equals(p)) Notify.chat(t, "<green>ทีมงานรักษาคุณแล้ว");
    }

    private void feed(Player p, String[] a) {
        Player t = a.length == 0 ? p : target(p, a, 0);
        if (t == null) return;
        t.setFoodLevel(20);
        t.setSaturation(20);
        Notify.bar(p, "<green>เติมอาหาร <white>" + t.getName(), "entity.generic.eat", 1.2f);
    }

    // ---------------------------------------------------------------
    // events
    // ---------------------------------------------------------------

    private void firework(Location l) {
        Firework fw = l.getWorld().spawn(l, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        Color[] palette = {Color.AQUA, Color.FUCHSIA, Color.LIME, Color.ORANGE, Color.YELLOW, Color.WHITE};
        FireworkEffect.Type[] shapes = FireworkEffect.Type.values();
        meta.addEffect(FireworkEffect.builder()
                .with(shapes[rng.nextInt(shapes.length)])
                .withColor(palette[rng.nextInt(palette.length)])
                .withFade(palette[rng.nextInt(palette.length)])
                .trail(rng.nextBoolean())
                .flicker(rng.nextBoolean())
                .build());
        meta.setPower(1 + rng.nextInt(2));
        fw.setFireworkMeta(meta);
    }

    private void fireworkCmd(Player p, String[] a) {
        Player t = a.length == 0 ? p : target(p, a, 0);
        if (t == null) return;
        for (int i = 0; i < 5; i++) {
            final int d = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (t.isOnline()) firework(t.getLocation().clone()
                        .add(rng.nextDouble() * 4 - 2, 0, rng.nextDouble() * 4 - 2));
            }, d * 6L);
        }
        Notify.bar(p, "<gold>จุดพลุแล้ว", null, 1.4f);
    }

    private void confetti(Player p, String[] a) {
        Player t = a.length == 0 ? p : target(p, a, 0);
        if (t == null) return;
        particles(t, P_TOTEM, 60, 1.2);
        particles(t, P_HAPPY, 40, 1.2);
        t.playSound(t.getLocation(), "entity.player.levelup", 0.8f, 1.6f);
        Notify.bar(p, "<gold>โปรยกระดาษแล้ว", null, 1.4f);
    }

    private void party(Player p) {
        toAll("<gradient:#f0cf8a:#b8863a><bold>🎉 ปาร์ตี้เริ่มแล้ว!</bold></gradient>");
        for (Player t : Bukkit.getOnlinePlayers()) {
            t.playSound(t.getLocation(), "entity.player.levelup", 1f, 1.3f);
            particles(t, P_TOTEM, 40, 1.0);
            for (int i = 0; i < 3; i++) {
                final int d = i;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (t.isOnline()) firework(t.getLocation().clone()
                            .add(rng.nextDouble() * 6 - 3, 0, rng.nextDouble() * 6 - 3));
                }, d * 10L);
            }
        }
    }

    private void disco(Player p, String[] a) {
        int sec = intArg(a, 0, 10, 1, 60);
        final int[] left = {sec * 4};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (left[0]-- <= 0) { task.cancel(); return; }
            for (Player t : Bukkit.getOnlinePlayers()) {
                particles(t, rng.nextBoolean() ? P_FLAME : P_HEART, 15, 1.2);
                t.playSound(t.getLocation(), "block.note_block.bit", 0.4f, 0.5f + rng.nextFloat() * 1.5f);
            }
        }, 0L, 5L);
        Notify.bar(p, "<light_purple>เปิดดิสโก้ " + sec + " วิ", null, 1.6f);
    }

    private void countdown(Player p, String[] a) {
        int sec = intArg(a, 0, 10, 1, 60);
        String what = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "เริ่ม!";
        final int[] left = {sec};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = left[0]--;
            if (n <= 0) {
                task.cancel();
                toAll("<gold><bold>" + what);
                for (Player t : Bukkit.getOnlinePlayers())
                    t.playSound(t.getLocation(), "entity.player.levelup", 1f, 1.2f);
                return;
            }
            for (Player t : Bukkit.getOnlinePlayers()) {
                Notify.bar(t, "<gold><bold>" + n);
                t.playSound(t.getLocation(), "block.note_block.pling", 0.8f, n <= 3 ? 1.8f : 1.2f);
            }
        }, 0L, 20L);
        Notify.bar(p, "<green>นับถอยหลัง " + sec + " วิ", null, 1.2f);
    }

    private void roll(Player p, String[] a) {
        int max = intArg(a, 0, 100, 2, 1000000);
        int n = 1 + rng.nextInt(max);
        toAll("<gray>สุ่มเลข 1–" + max + " ได้ <gold><bold>" + n);
    }

    private void pick(Player p, String[] a) {
        int want = intArg(a, 0, 1, 1, 20);
        List<Player> pool = new ArrayList<>(Bukkit.getOnlinePlayers());
        pool.removeIf(t -> plugin.staff().isVanished(t.getUniqueId()));
        if (pool.isEmpty()) {
            Notify.chat(p, "<gray>ไม่มีใครออนไลน์");
            return;
        }
        Collections.shuffle(pool, rng);
        List<String> won = new ArrayList<>();
        for (int i = 0; i < Math.min(want, pool.size()); i++) won.add(pool.get(i).getName());
        toAll("<gold><bold>🎁 ผู้โชคดี <white>" + String.join("<gray>, <white>", won));
        for (String nm : won) {
            Player t = Bukkit.getPlayerExact(nm);
            if (t != null) { firework(t.getLocation()); confettiQuiet(t); }
        }
    }

    private void confettiQuiet(Player t) {
        particles(t, P_TOTEM, 50, 1.0);
        t.playSound(t.getLocation(), "entity.player.levelup", 1f, 1.5f);
    }

    private void announce(Player p, String[] a) {
        if (a.length == 0) {
            Notify.chat(p, "<gray>ใช้ <white>/announce <ข้อความ>");
            return;
        }
        String msg = String.join(" ", a);
        for (Player t : Bukkit.getOnlinePlayers()) {
            Notify.popup(t, "<gold><bold>ประกาศ", "<white>" + msg, "entity.experience_orb.pickup", 1.2f);
        }
        toAll("<gold>[ประกาศ] <white>" + msg);
    }

    // ---------------------------------------------------------------
    // tab completion
    // ---------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player t : Bukkit.getOnlinePlayers()) names.add(t.getName());
            return names;
        }
        if (a.length == 2 && name.equals("prank")) return PRESETS;
        if (a.length == 2 && name.equals("mobs")) return List.of("CHICKEN", "PIG", "COW", "BAT", "SILVERFISH", "SHEEP");
        if (a.length == 2 && name.equals("potion"))
            return List.of("NAUSEA", "BLINDNESS", "LEVITATION", "SLOWNESS", "SPEED", "GLOWING", "INVISIBILITY", "JUMP_BOOST");
        return Collections.emptyList();
    }
}
