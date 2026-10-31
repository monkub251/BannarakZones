package com.bannarak.zones;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Fifty waves in a world of its own. One attempt, one loadout: whoever dies
 * is out, and the party only wins if someone is still standing after wave 50.
 */
public class DungeonManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;
    private final Map<UUID, Dungeon> runs = new HashMap<>();        // run id -> run
    private final Map<UUID, UUID> playerRun = new HashMap<>();      // player -> run id
    private final Map<UUID, Long> cooldowns = new HashMap<>();      // player -> ready at

    public DungeonManager(BannarakZones plugin) {
        this.plugin = plugin;
    }

    // ---------------- config helpers ----------------

    private String cfgString(String path, String def) { return plugin.getConfig().getString("dungeon." + path, def); }
    private int cfgInt(String path, int def) { return plugin.getConfig().getInt("dungeon." + path, def); }
    private double cfgDouble(String path, double def) { return plugin.getConfig().getDouble("dungeon." + path, def); }

    public World world() {
        World w = Bukkit.getWorld(cfgString("world", "dungeon"));
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    public Location arena() {
        return new Location(world(),
                cfgDouble("spawn.x", 0.5), cfgDouble("spawn.y", 64), cfgDouble("spawn.z", 0.5),
                (float) cfgDouble("spawn.yaw", 0), (float) cfgDouble("spawn.pitch", 0));
    }

    public int maxWave() { return cfgInt("waves", 50); }
    public int minParty() { return cfgInt("party.min", 3); }
    public int maxParty() { return cfgInt("party.max", 5); }

    public long cooldownLeft(UUID uuid) {
        long ready = cooldowns.getOrDefault(uuid, 0L);
        return Math.max(0, ready - System.currentTimeMillis());
    }

    public static String readable(long millis) {
        long minutes = millis / 60000;
        long h = minutes / 60, m = minutes % 60;
        return h > 0 ? h + " ชม. " + m + " นาที" : m + " นาที";
    }

    public void clearCooldown(UUID uuid) { cooldowns.remove(uuid); }

    public int activeRuns() { return runs.size(); }

    public Dungeon runOf(Player p) {
        UUID runId = playerRun.get(p.getUniqueId());
        return runId == null ? null : runs.get(runId);
    }

    public boolean inDungeon(Player p) { return runOf(p) != null; }

    // ---------------- starting ----------------

    public void start(Player leader, List<Player> members) {
        List<Player> party = new ArrayList<>();
        party.add(leader);
        for (Player m : members) if (!party.contains(m)) party.add(m);

        if (party.size() < minParty()) {
            Notify.chat(leader, "<red>ต้องมีอย่างน้อย <yellow>" + minParty() + " <red>คนถึงจะลงได้");
            return;
        }
        if (party.size() > maxParty()) {
            Notify.chat(leader, "<red>ลงได้ไม่เกิน <yellow>" + maxParty() + " <red>คน");
            return;
        }
        for (Player p : party) {
            if (inDungeon(p)) { Notify.chat(leader, "<red>" + p.getName() + " อยู่ในดันเจียนอยู่แล้ว"); return; }
            long left = cooldownLeft(p.getUniqueId());
            if (left > 0) {
                Notify.chat(leader, "<red>" + p.getName() + " ต้องรออีก <yellow>" + readable(left));
                return;
            }
        }

        List<UUID> ids = new ArrayList<>();
        for (Player p : party) ids.add(p.getUniqueId());
        Dungeon run = new Dungeon(leader.getUniqueId(), ids);
        run.bar = BossBar.bossBar(MM.deserialize("<gold>เตรียมตัว"), 1f,
                BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        runs.put(run.id, run);

        Location arena = arena();
        for (Player p : party) {
            playerRun.put(p.getUniqueId(), run.id);
            p.teleport(arena);
            p.showBossBar(run.bar);
            Notify.popup(p, "<gradient:#ff6b6b:#c92a2a>ดันเจียน</gradient>",
                    "<white>" + maxWave() + " เวฟ · ตายแล้วออก", "ui.toast.challenge_complete", 0.8f);
        }

        int count = cfgInt("countdown-seconds", 10);
        new BukkitRunnable() {
            int left = count;
            @Override public void run() {
                if (!runs.containsKey(run.id)) { cancel(); return; }
                if (left <= 0) {
                    cancel();
                    nextWave(run);
                    return;
                }
                for (Player p : run.onlineParty()) {
                    Notify.bar(p, "<yellow>เริ่มใน <white>" + left + " <yellow>วินาที — เตรียมของให้พร้อม",
                            "block.note_block.hat", 1.2f);
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    // ---------------- waves ----------------

    private void nextWave(Dungeon run) {
        run.wave++;
        run.state = Dungeon.State.FIGHTING;
        if (run.wave > maxWave()) { finish(run, true); return; }

        int partySize = Math.max(1, run.onlineAlive().size());
        boolean miniboss = run.wave == maxWave() - 1;
        boolean boss = run.wave == maxWave();

        String label = boss ? "<dark_red>บอสใหญ่"
                : miniboss ? "<red>บอสรอง"
                : "<gold>เวฟ <white>" + run.wave + "<gray>/" + maxWave();
        run.bar.name(MM.deserialize(label));
        run.bar.progress(Math.min(1f, run.wave / (float) maxWave()));
        run.bar.color(boss ? BossBar.Color.PURPLE : miniboss ? BossBar.Color.RED : BossBar.Color.YELLOW);

        for (Player p : run.onlineAlive()) {
            if (boss || miniboss)
                Notify.popup(p, label, "<gray>ระวังตัว", "entity.ender_dragon.growl", 0.8f);
            else
                Notify.bar(p, label, "block.note_block.pling", 1.0f);
        }

        run.mobs.clear();
        if (boss) spawnBoss(run, true);
        else if (miniboss) spawnBoss(run, false);
        else spawnPack(run, partySize);

        watch(run);
    }

    private List<EntityType> poolFor(int wave) {
        List<EntityType> out = new ArrayList<>();
        var section = plugin.getConfig().getConfigurationSection("dungeon.pools");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String range = section.getString(key + ".waves", "1-50");
                if (!inRange(wave, range)) continue;
                for (String name : section.getStringList(key + ".mobs")) {
                    try { out.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT))); }
                    catch (IllegalArgumentException ignored) { }
                }
            }
        }
        if (out.isEmpty()) out.add(EntityType.ZOMBIE);
        return out;
    }

    private static boolean inRange(int wave, String range) {
        try {
            String[] parts = range.split("-");
            int lo = Integer.parseInt(parts[0].trim());
            int hi = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : lo;
            return wave >= lo && wave <= hi;
        } catch (Exception ex) {
            return false;
        }
    }

    private void spawnPack(Dungeon run, int partySize) {
        List<EntityType> pool = poolFor(run.wave);
        int count = (int) Math.round(cfgDouble("mobs.base", 4)
                + cfgDouble("mobs.per-wave", 0.55) * run.wave
                + cfgDouble("mobs.per-player", 1.2) * partySize);
        Location centre = arena();
        Random rnd = new Random();
        double radius = cfgDouble("spawn-radius", 12);
        for (int i = 0; i < count; i++) {
            EntityType type = pool.get(rnd.nextInt(pool.size()));
            double angle = rnd.nextDouble() * Math.PI * 2;
            Location at = centre.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            at.setY(centre.getY());
            Entity e = centre.getWorld().spawnEntity(at, type);
            if (!(e instanceof LivingEntity le)) { e.remove(); continue; }
            scale(le, run.wave, false);
            run.mobs.add(le.getUniqueId());
        }
    }

    private void spawnBoss(Dungeon run, boolean main) {
        String path = main ? "boss" : "miniboss";
        EntityType type;
        try {
            type = EntityType.valueOf(cfgString(path + ".type", main ? "WITHER" : "WITHER_SKELETON")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            type = main ? EntityType.WITHER : EntityType.WITHER_SKELETON;
        }
        Location at = arena().clone().add(0, 1, 0);
        Entity e = at.getWorld().spawnEntity(at, type);
        if (!(e instanceof LivingEntity le)) return;

        double hp = cfgDouble(path + ".health", main ? 1200 : 500);
        setMaxHealth(le, hp);
        le.setHealth(Math.min(hp, le.getMaxHealth()));
        le.customName(MM.deserialize(cfgString(path + ".name",
                main ? "<dark_red>เจ้าแห่งเหวลึก" : "<red>ผู้เฝ้าประตู")));
        le.setCustomNameVisible(true);
        le.setRemoveWhenFarAway(false);
        AttributeInstance dmg = attribute(le, "GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE");
        if (dmg != null) dmg.setBaseValue(cfgDouble(path + ".damage", main ? 14 : 9));
        le.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE,
                main ? 1 : 0, true, false));

        run.boss = le;
        run.mobs.add(le.getUniqueId());

        // adds keep showing up while the boss is alive
        new BukkitRunnable() {
            @Override public void run() {
                if (!runs.containsKey(run.id) || run.boss == null || run.boss.isDead()) { cancel(); return; }
                spawnPack(run, Math.max(1, run.onlineAlive().size()));
            }
        }.runTaskTimer(plugin, 20L * 25, 20L * 25);
    }

    private void scale(LivingEntity le, int wave, boolean boss) {
        double hpMul = 1 + cfgDouble("scaling.health-per-wave", 0.06) * wave;
        double dmgMul = 1 + cfgDouble("scaling.damage-per-wave", 0.04) * wave;
        AttributeInstance hp = attribute(le, "GENERIC_MAX_HEALTH", "MAX_HEALTH");
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * hpMul);
            le.setHealth(hp.getValue());
        }
        AttributeInstance dmg = attribute(le, "GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE");
        if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * dmgMul);
        le.setRemoveWhenFarAway(false);
        if (wave > 30) le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, true, false));
    }

    /** Attribute names moved between versions, so try both spellings. */
    private AttributeInstance attribute(LivingEntity le, String oldName, String newName) {
        for (String name : new String[]{newName, oldName}) {
            try {
                Attribute a = Attribute.valueOf(name);
                AttributeInstance inst = le.getAttribute(a);
                if (inst != null) return inst;
            } catch (IllegalArgumentException | NoSuchFieldError ignored) { }
        }
        return null;
    }

    private void setMaxHealth(LivingEntity le, double hp) {
        AttributeInstance inst = attribute(le, "GENERIC_MAX_HEALTH", "MAX_HEALTH");
        if (inst != null) inst.setBaseValue(hp);
    }

    private void watch(Dungeon run) {
        new BukkitRunnable() {
            @Override public void run() {
                if (!runs.containsKey(run.id)) { cancel(); return; }
                if (run.onlineAlive().isEmpty()) { cancel(); finish(run, false); return; }
                run.mobs.removeIf(id -> {
                    Entity e = Bukkit.getEntity(id);
                    return e == null || e.isDead();
                });
                if (run.mobs.isEmpty() && run.state == Dungeon.State.FIGHTING) {
                    cancel();
                    clearedWave(run);
                }
            }
        }.runTaskTimer(plugin, 40L, 20L);
    }

    private void clearedWave(Dungeon run) {
        double pay = cfgDouble("rewards.per-wave", 2000)
                * Math.pow(cfgDouble("rewards.wave-multiplier", 1.08), run.wave - 1);
        for (Player p : run.onlineAlive()) {
            plugin.money().add(p.getUniqueId(), pay);
            run.earned += pay;
            Notify.bar(p, "<green>เคลียร์เวฟ <white>" + run.wave + " <gray>+ <gold>"
                    + plugin.money().format(pay), "entity.player.levelup", 1.4f);
        }
        if (run.wave >= maxWave()) { finish(run, true); return; }

        run.state = Dungeon.State.BREAK;
        int breakSeconds = cfgInt("wave-break-seconds", 6);
        new BukkitRunnable() {
            int left = breakSeconds;
            @Override public void run() {
                if (!runs.containsKey(run.id)) { cancel(); return; }
                if (left <= 0) { cancel(); nextWave(run); return; }
                for (Player p : run.onlineAlive())
                    Notify.bar(p, "<gray>เวฟถัดไปใน <white>" + left, null, 1f);
                left--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ---------------- ending ----------------

    private void finish(Dungeon run, boolean won) {
        if (run.state == Dungeon.State.FINISHED) return;
        run.state = Dungeon.State.FINISHED;

        for (UUID id : run.mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        run.mobs.clear();

        long cd = (long) (cfgDouble("cooldown-hours", 10) * 3600_000L);
        double clearPay = cfgDouble("rewards.clear", 500000);

        for (UUID id : run.party) {
            cooldowns.put(id, System.currentTimeMillis() + cd);
            playerRun.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.hideBossBar(run.bar);
            if (won && run.alive.contains(id)) {
                plugin.money().add(id, clearPay);
                Notify.popup(p, "<gradient:#ffe08a:#ffa600>ผ่านดันเจียน</gradient>",
                        "<white>+<gold>" + plugin.money().format(clearPay), "ui.toast.challenge_complete", 1.2f);
                for (String cmd : plugin.getConfig().getStringList("dungeon.rewards.clear-commands"))
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("<player>", p.getName()));
            } else {
                Notify.popup(p, "<red>ดันเจียนล้มเหลว",
                        "<gray>ไปถึงเวฟ <white>" + run.wave, "entity.wither.death", 0.7f);
            }
            p.teleport(plugin.locations().spawn());
        }

        if (won) {
            StringBuilder names = new StringBuilder();
            for (UUID id : run.party) {
                if (names.length() > 0) names.append("<gray>, <yellow>");
                names.append(plugin.money().nameOf(id));
            }
            Bukkit.broadcast(Notify.of("<dark_red>☠ <white>ดันเจียนถูกพิชิตโดย <yellow>" + names));
        }
        plugin.money().save(true);
        runs.remove(run.id);
    }

    public void leave(Player p, boolean died) {
        Dungeon run = runOf(p);
        if (run == null) return;
        run.alive.remove(p.getUniqueId());
        p.hideBossBar(run.bar);
        if (died) {
            for (Player other : run.onlineAlive())
                Notify.bar(other, "<red>" + p.getName() + " ตายแล้ว <gray>เหลือ "
                        + run.onlineAlive().size() + " คน", "entity.wither.hurt", 0.8f);
        }
        if (run.onlineAlive().isEmpty()) finish(run, false);
    }

    // ---------------- events ----------------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!inDungeon(p)) return;
        leave(p, true);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> p.teleport(plugin.locations().spawn()), 5L);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        for (Dungeon run : runs.values())
            if (run.mobs.remove(e.getEntity().getUniqueId())) {
                if (run.boss != null && run.boss.getUniqueId().equals(e.getEntity().getUniqueId()))
                    run.boss = null;
                return;
            }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (inDungeon(e.getPlayer())) leave(e.getPlayer(), false);
    }
}
