package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Jobs. A player picks one or two, then earns coins and job experience
 * for doing that kind of work. Levels raise the payout and hand out
 * rewards along the way. All of it is described in jobs.yml.
 */
public class JobManager implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ---------------- definitions ----------------

    public static class Reward {
        public int level;
        public double money;
        public List<String> commands = new ArrayList<>();
        public String note = "";
    }

    public static class Job {
        public String id;
        public String display;
        public String icon = "IRON_PICKAXE";
        public List<String> lore = new ArrayList<>();
        public int maxLevel = 50;
        public double baseXp = 120;      // xp needed for level 2
        public double curve = 1.18;      // each level costs this much more
        public double bonusPerLevel = 0.012;  // +1.2% pay per level by default

        /** action key -> pay. Keys look like "break:DIAMOND_ORE" or "kill:ZOMBIE". */
        public final Map<String, double[]> pay = new HashMap<>();   // {money, xp}
        public final List<Reward> rewards = new ArrayList<>();

        public double xpFor(int level) {
            if (level < 1) return 0;
            return baseXp * Math.pow(curve, level - 1);
        }
    }

    public static class Progress {
        public int level = 1;
        public double xp = 0;
        public double earned = 0;
    }

    // ---------------- state ----------------

    private final BannarakZones plugin;
    private final File file;

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    /** player -> job id -> progress */
    private final Map<UUID, Map<String, Progress>> held = new HashMap<>();
    /** blocks a player put down, so breaking them again pays nothing */
    private final Set<String> placed = new HashSet<>();

    private boolean dirty = false;

    public JobManager(BannarakZones plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "jobs-progress.yml");
    }

    public Collection<Job> jobs() { return jobs.values(); }

    public Job job(String id) { return jobs.get(id); }

    public Map<String, Progress> of(UUID id) {
        return held.computeIfAbsent(id, k -> new LinkedHashMap<>());
    }

    private int maxJobs() { return plugin.getConfig().getInt("jobs.max-per-player", 2); }

    // ---------------- loading ----------------

    public void load() {
        jobs.clear();
        File f = new File(plugin.getDataFolder(), "jobs.yml");
        if (!f.exists()) plugin.saveResource("jobs.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = cfg.getConfigurationSection("jobs");
        if (root == null) {
            plugin.getLogger().warning("jobs.yml ไม่มีหัวข้อ jobs:");
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Job j = new Job();
            j.id = id;
            j.display = s.getString("display", id);
            j.icon = s.getString("icon", "IRON_PICKAXE");
            j.lore = s.getStringList("lore");
            j.maxLevel = s.getInt("max-level", 50);
            j.baseXp = s.getDouble("base-xp", 120);
            j.curve = s.getDouble("curve", 1.18);
            j.bonusPerLevel = s.getDouble("bonus-per-level", 0.012);

            for (String kind : new String[]{"break", "place", "kill", "craft", "fish"}) {
                ConfigurationSection sec = s.getConfigurationSection("pay." + kind);
                if (sec == null) continue;
                for (String what : sec.getKeys(false)) {
                    double money = sec.getDouble(what + ".money", 0);
                    double xp = sec.getDouble(what + ".xp", 0);
                    j.pay.put(kind + ":" + what.toUpperCase(Locale.ROOT), new double[]{money, xp});
                }
            }

            ConfigurationSection rew = s.getConfigurationSection("rewards");
            if (rew != null) for (String lvl : rew.getKeys(false)) {
                Reward r = new Reward();
                try { r.level = Integer.parseInt(lvl); }
                catch (NumberFormatException ex) {
                    plugin.getLogger().warning("jobs.yml: รางวัลของ " + id + " ใช้เลเวล " + lvl + " ที่ไม่ใช่ตัวเลข");
                    continue;
                }
                r.money = rew.getDouble(lvl + ".money", 0);
                r.commands = rew.getStringList(lvl + ".commands");
                r.note = rew.getString(lvl + ".note", "");
                j.rewards.add(r);
            }
            j.rewards.sort(Comparator.comparingInt(a -> a.level));

            jobs.put(id, j);
        }
        plugin.getLogger().info("โหลดอาชีพ " + jobs.size() + " อย่าง");
    }

    // ---------------- earning ----------------

    private void award(Player p, String key, Location where) {
        Map<String, Progress> mine = held.get(p.getUniqueId());
        if (mine == null || mine.isEmpty()) return;

        for (var entry : mine.entrySet()) {
            Job j = jobs.get(entry.getKey());
            if (j == null) continue;
            double[] rate = j.pay.get(key);
            if (rate == null) continue;

            Progress pr = entry.getValue();
            double multiplier = 1 + j.bonusPerLevel * (pr.level - 1);
            double money = rate[0] * multiplier;
            double xp = rate[1];
            if (money <= 0 && xp <= 0) continue;

            if (money > 0) {
                plugin.money().add(p.getUniqueId(), money);
                pr.earned += money;
            }
            pr.xp += xp;
            dirty = true;

            levelUp(p, j, pr);
            showProgress(p, j, pr, money);
        }
    }

    private void levelUp(Player p, Job j, Progress pr) {
        boolean rose = false;
        while (pr.level < j.maxLevel && pr.xp >= j.xpFor(pr.level)) {
            pr.xp -= j.xpFor(pr.level);
            pr.level++;
            rose = true;

            for (Reward r : j.rewards) {
                if (r.level != pr.level) continue;
                if (r.money > 0) plugin.money().add(p.getUniqueId(), r.money);
                for (String cmd : r.commands)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            cmd.replace("<player>", p.getName()));
                if (!r.note.isBlank()) Notify.chat(p, "<gold>รางวัล: <white>" + r.note);
            }
        }
        if (!rose) return;

        Notify.popup(p, "<gradient:#f0cf8a:#b8863a>" + j.display + " เลเวล " + pr.level + "</gradient>",
                "<gray>ค่าตอบแทนเพิ่มขึ้น", "ui.toast.challenge_complete", 1.0f);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0),
                30, 0.3, 0.6, 0.3, 0.02);
    }

    /** A quiet action-bar line, only as often as the config allows. */
    private final Map<UUID, Long> lastBar = new HashMap<>();

    private void showProgress(Player p, Job j, Progress pr, double money) {
        if (!plugin.getConfig().getBoolean("jobs.show-action-bar", true)) return;
        long gap = plugin.getConfig().getLong("jobs.action-bar-gap-ms", 2500);
        long now = System.currentTimeMillis();
        Long before = lastBar.get(p.getUniqueId());
        if (before != null && now - before < gap) return;
        lastBar.put(p.getUniqueId(), now);

        double need = j.xpFor(pr.level);
        int filled = need <= 0 ? 10 : (int) Math.min(10, Math.round(pr.xp / need * 10));
        String bar = "<gold>" + "▮".repeat(filled) + "<dark_gray>" + "▮".repeat(10 - filled);
        Notify.bar(p, "<gray>" + j.display + " <dark_gray>lv<white>" + pr.level + " "
                + bar + (money > 0 ? " <green>+" + Math.round(money) : ""));
    }

    // ---------------- events ----------------

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.getConfig().getBoolean("jobs.block-placed-farming", true)) {
            if (placed.size() > 200_000) placed.clear();   // keep memory sane
            placed.add(key(e.getBlock()));
        }
        award(e.getPlayer(), "place:" + e.getBlock().getType().name(), e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        String k = key(e.getBlock());
        if (placed.remove(k)) return;        // they placed it themselves, no pay
        award(e.getPlayer(), "break:" + e.getBlock().getType().name(), e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        award(killer, "kill:" + e.getEntityType().name(), e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        award(p, "craft:" + result.getType().name(), p.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        String what = e.getCaught() instanceof org.bukkit.entity.Item item
                ? item.getItemStack().getType().name() : "ANY";
        award(e.getPlayer(), "fish:" + what, e.getPlayer().getLocation());
        award(e.getPlayer(), "fish:ANY", e.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastBar.remove(e.getPlayer().getUniqueId());
    }

    // ---------------- menu ----------------

    public class Holder implements InventoryHolder {
        public final String page;
        public Holder(String page) { this.page = page; }
        @Override public Inventory getInventory() { return null; }
    }

    public void open(Player p) {
        Inventory inv = Bukkit.createInventory(new Holder("list"), 54,
                MM.deserialize("<dark_gray>อาชีพ"));

        Map<String, Progress> mine = of(p.getUniqueId());
        int slot = 10;
        for (Job j : jobs.values()) {
            if (slot % 9 == 8) slot += 3;
            if (slot >= 44) break;

            Progress pr = mine.get(j.id);
            Material icon = Material.matchMaterial(j.icon);
            ItemStack it = new ItemStack(icon == null ? Material.IRON_PICKAXE : icon);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(MM.deserialize((pr != null ? "<gold>" : "<gray>") + j.display)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            for (String line : j.lore)
                lore.add(MM.deserialize("<gray>" + line).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            if (pr != null) {
                double need = j.xpFor(pr.level);
                int filled = need <= 0 ? 20 : (int) Math.min(20, Math.round(pr.xp / need * 20));
                lore.add(MM.deserialize("<gray>เลเวล <white>" + pr.level + "<dark_gray>/" + j.maxLevel)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<gold>" + "▮".repeat(filled) + "<dark_gray>" + "▮".repeat(20 - filled))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<gray>ค่าตอบแทน <green>+"
                        + Math.round(j.bonusPerLevel * (pr.level - 1) * 100) + "%")
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<gray>หาได้ทั้งหมด <gold>" + Math.round(pr.earned))
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(MM.deserialize("<red>คลิกขวาเพื่อลาออก")
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(MM.deserialize("<gray>ยังไม่ได้ทำอาชีพนี้")
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<yellow>คลิกเพื่อสมัคร")
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
            slot++;
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta im = info.getItemMeta();
        im.displayName(MM.deserialize("<gold>ทำอาชีพได้ " + maxJobs() + " อย่างพร้อมกัน")
                .decoration(TextDecoration.ITALIC, false));
        im.lore(List.of(
                MM.deserialize("<gray>ทำอยู่ <white>" + mine.size() + "<dark_gray>/" + maxJobs())
                        .decoration(TextDecoration.ITALIC, false),
                MM.deserialize("<dark_gray>ลาออกแล้วเลเวลยังอยู่")
                        .decoration(TextDecoration.ITALIC, false)));
        info.setItemMeta(im);
        inv.setItem(49, info);

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return;

        String clicked = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(it.getItemMeta().displayName() == null
                        ? Component.empty() : it.getItemMeta().displayName());

        Job match = null;
        for (Job j : jobs.values())
            if (clicked.equals(PlainName.of(j.display))) { match = j; break; }
        if (match == null) return;

        Map<String, Progress> mine = of(p.getUniqueId());
        if (e.getClick() == ClickType.RIGHT) {
            if (mine.remove(match.id) != null) {
                dirty = true;
                Notify.chat(p, "<yellow>ลาออกจาก <white>" + match.display + " <yellow>แล้ว");
                p.playSound(p.getLocation(), "block.note_block.bass", 0.7f, 0.8f);
            }
            open(p);
            return;
        }

        if (mine.containsKey(match.id)) { open(p); return; }
        if (mine.size() >= maxJobs()) {
            Notify.chat(p, "<red>ทำได้แค่ " + maxJobs() + " อาชีพ ลาออกก่อน (คลิกขวา)");
            return;
        }
        mine.put(match.id, new Progress());
        dirty = true;
        Notify.popup(p, "<gold>" + match.display, "<gray>สมัครเรียบร้อย",
                "ui.toast.challenge_complete", 1.2f);
        open(p);
    }

    /** Strips MiniMessage tags so the clicked name can be compared. */
    static class PlainName {
        static String of(String mini) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(MM.deserialize(mini));
        }
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length >= 2 && args[0].equalsIgnoreCase("top")) {
            Job j = jobs.get(args[1].toLowerCase(Locale.ROOT));
            if (j == null) { Notify.chat(p, "<red>ไม่มีอาชีพนี้"); return true; }
            List<Map.Entry<UUID, Progress>> rows = new ArrayList<>();
            for (var e : held.entrySet()) {
                Progress pr = e.getValue().get(j.id);
                if (pr != null) rows.add(Map.entry(e.getKey(), pr));
            }
            rows.sort((a, b) -> Integer.compare(b.getValue().level, a.getValue().level));
            Notify.chat(p, "<gold>อันดับ " + j.display);
            for (int i = 0; i < Math.min(10, rows.size()); i++) {
                var row = rows.get(i);
                Notify.chat(p, "<dark_gray>" + (i + 1) + ". <white>"
                        + plugin.money().nameOf(row.getKey())
                        + " <gray>เลเวล <yellow>" + row.getValue().level);
            }
            return true;
        }
        open(p);
        return true;
    }

    // ---------------- persistence ----------------

    public void loadProgress() {
        held.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("players");
        if (root == null) return;
        for (String raw : root.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(raw); }
            catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("jobs-progress.yml: ข้าม " + raw);
                continue;
            }
            Map<String, Progress> mine = new LinkedHashMap<>();
            ConfigurationSection sec = root.getConfigurationSection(raw);
            if (sec == null) continue;
            for (String jobId : sec.getKeys(false)) {
                Progress pr = new Progress();
                pr.level = sec.getInt(jobId + ".level", 1);
                pr.xp = sec.getDouble(jobId + ".xp", 0);
                pr.earned = sec.getDouble(jobId + ".earned", 0);
                mine.put(jobId, pr);
            }
            if (!mine.isEmpty()) held.put(id, mine);
        }
    }

    public void save(boolean force) {
        if (!dirty && !force) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (var e : held.entrySet()) {
            for (var j : e.getValue().entrySet()) {
                String path = "players." + e.getKey() + "." + j.getKey();
                yml.set(path + ".level", j.getValue().level);
                yml.set(path + ".xp", j.getValue().xp);
                yml.set(path + ".earned", j.getValue().earned);
            }
        }
        try {
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "บันทึก jobs-progress.yml ไม่ได้", ex);
        }
    }
}
