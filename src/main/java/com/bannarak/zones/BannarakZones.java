package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class BannarakZones extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ZoneStore store;
    private Economy economy;
    private EconomyManager money;
    private MissionManager missions;
    private LocStore locations;
    private MenuManager menus;
    private ShopManager shop;
    private DiscordRanks ranks;
    private DungeonManager dungeons;
    private CrateManager crates;
    private DailyRewards daily;
    private MarketManager market;
    private CustomItems customItems;
    private AdminHud adminHud;
    private PlayerHud playerHud;
    private AdminTools adminTools;
    private BackupManager backups;
    private Punishments punishments;
    private AdminPanel adminPanel;
    private WebPanel webPanel;
    private ChatManager chat;
    private NpcManager npcs;
    private Holograms holos;
    private ServerBlocks blocks;
    private StaffTools staff;
    private FunTools fun;
    private Prompt prompt;
    private MiningWatch mining;
    private PointsManager points;
    private Cosmetics cosmetics;
    private PointsShop pointsShop;
    private WorldManager worlds;
    private Material adminBlock = Material.REINFORCED_DEEPSLATE;
    private int adminRadius = 64;
    private final Set<String> lockedWorlds = new HashSet<>();
    private final Set<String> safeWorlds = new HashSet<>();
    private ZoneGui gui;

    private Material zoneBlock = Material.BEACON;
    private int defaultRadius = 16;
    private int maxRadius = 48;
    private boolean fullHeight = true;
    private int verticalRange = 32;
    private boolean firstFree = true;
    private int minGap = 4;
    private Set<String> blockedWorlds = new HashSet<>();
    private final TreeMap<Integer, Double> radiusPrices = new TreeMap<>();
    private double slotBase = 20000;
    private double slotMultiplier = 2.0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Notify.init(this);
        store = new ZoneStore(this);
        money = new EconomyManager(this);
        readConfig();
        store.load();
        money.load();
        missions = new MissionManager(this);
        missions.loadDefinitions();
        missions.load();
        locations = new LocStore(this);
        locations.load();
        gui = new ZoneGui(this);

        registerEconomy();
        hookEconomy();

        MoneyCommands moneyCommands = new MoneyCommands(this);
        for (String name : new String[]{"money", "pay", "baltop", "eco"}) {
            var cmd = getCommand(name);
            if (cmd != null) { cmd.setExecutor(moneyCommands); cmd.setTabCompleter(moneyCommands); }
        }

        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new MoveListener(this), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(moneyCommands, this);

        MissionGui missionGui = new MissionGui(this);
        var missionCmd = getCommand("mission");
        if (missionCmd != null) missionCmd.setExecutor(missionGui);
        getServer().getPluginManager().registerEvents(missionGui, this);
        missions.startTracking();

        TravelCommands travel = new TravelCommands(this);
        for (String name : new String[]{"spawn", "setspawn", "home", "sethome", "delhome",
                "warp", "setwarp", "delwarp", "tpa", "tpaccept", "tpdeny"}) {
            var c2 = getCommand(name);
            if (c2 != null) { c2.setExecutor(travel); c2.setTabCompleter(travel); }
        }
        getServer().getPluginManager().registerEvents(travel, this);

        menus = new MenuManager(this);
        menus.load();
        var menuCmd = getCommand("menu");
        if (menuCmd != null) menuCmd.setExecutor(menus);
        getServer().getPluginManager().registerEvents(menus, this);

        shop = new ShopManager(this);
        shop.load();
        var shopCmd = getCommand("shop");
        if (shopCmd != null) shopCmd.setExecutor(shop);
        getServer().getPluginManager().registerEvents(shop, this);

        ranks = new DiscordRanks(this);
        ranks.load();
        var rankCmd = getCommand("rank");
        if (rankCmd != null) rankCmd.setExecutor(ranks);
        getServer().getPluginManager().registerEvents(ranks, this);

        dungeons = new DungeonManager(this);
        DungeonGui dungeonGui = new DungeonGui(this);
        var dungeonCmd = getCommand("dungeon");
        if (dungeonCmd != null) dungeonCmd.setExecutor(dungeonGui);
        getServer().getPluginManager().registerEvents(dungeons, this);
        getServer().getPluginManager().registerEvents(dungeonGui, this);

        crates = new CrateManager(this);
        crates.load();
        var crateCmd = getCommand("crate");
        if (crateCmd != null) crateCmd.setExecutor(crates);
        getServer().getPluginManager().registerEvents(crates, this);

        daily = new DailyRewards(this);
        daily.load();
        var dailyCmd = getCommand("daily");
        if (dailyCmd != null) dailyCmd.setExecutor(daily);
        getServer().getPluginManager().registerEvents(daily, this);

        market = new MarketManager(this);
        market.load();
        for (String name : new String[]{"market", "sell"}) {
            var c3 = getCommand(name);
            if (c3 != null) c3.setExecutor(market);
        }
        getServer().getPluginManager().registerEvents(market, this);
        getServer().getScheduler().runTaskTimer(this, () -> market.expire(), 1200L, 72000L);

        customItems = new CustomItems(this);
        customItems.load();
        var itemCmd = getCommand("citem");
        if (itemCmd != null) itemCmd.setExecutor(customItems);
        getServer().getPluginManager().registerEvents(customItems, this);

        adminHud = new AdminHud(this);
        var hudCmd = getCommand("adminhud");
        if (hudCmd != null) hudCmd.setExecutor(adminHud);
        getServer().getPluginManager().registerEvents(adminHud, this);
        adminHud.start();

        adminTools = new AdminTools(this);
        adminTools.load();
        for (String n : new String[]{"astick", "jail", "unjail", "setjail", "spectate"}) {
            var c7 = getCommand(n);
            if (c7 != null) { c7.setExecutor(adminTools); c7.setTabCompleter(adminTools); }
        }
        getServer().getPluginManager().registerEvents(adminTools, this);

        playerHud = new PlayerHud(this);
        getServer().getPluginManager().registerEvents(playerHud, this);
        playerHud.start();

        backups = new BackupManager(this);
        var backupCmd = getCommand("backup");
        if (backupCmd != null) backupCmd.setExecutor(backups);
        backups.schedule();

        punishments = new Punishments(this);
        punishments.load();
        for (String name : new String[]{"ban", "unban", "mute", "unmute", "kick", "warn", "history"}) {
            var c4 = getCommand(name);
            if (c4 != null) { c4.setExecutor(punishments); c4.setTabCompleter(punishments); }
        }
        getServer().getPluginManager().registerEvents(punishments, this);

        adminPanel = new AdminPanel(this);
        var panelCmd = getCommand("admin");
        if (panelCmd != null) panelCmd.setExecutor(adminPanel);
        getServer().getPluginManager().registerEvents(adminPanel, this);

        worlds = new WorldManager(this);
        worlds.prepareWorlds();
        worlds.applyBorders();
        for (String name : new String[]{"rtp", "world"}) {
            var c6 = getCommand(name);
            if (c6 != null) { c6.setExecutor(worlds); c6.setTabCompleter(worlds); }
        }

        var helpCmd = getCommand("commands");
        if (helpCmd != null) helpCmd.setExecutor(new HelpCommand());

        chat = new ChatManager(this);
        chat.load();
        for (String name : new String[]{"msg", "r", "ignore", "chat"}) {
            var c5 = getCommand(name);
            if (c5 != null) { c5.setExecutor(chat); c5.setTabCompleter(chat); }
        }
        getServer().getPluginManager().registerEvents(chat, this);
        chat.startTabTimer();

        points = new PointsManager(this);
        points.load();
        for (String n : new String[]{"points", "topup"}) {
            var c9 = getCommand(n);
            if (c9 != null) { c9.setExecutor(points); c9.setTabCompleter(points); }
        }

        cosmetics = new Cosmetics(this);
        cosmetics.load();
        getServer().getPluginManager().registerEvents(cosmetics, this);
        getServer().getScheduler().runTaskTimer(this, () -> cosmetics.followTick(), 40L, 10L);

        pointsShop = new PointsShop(this);
        var pshopCmd = getCommand("pshop");
        if (pshopCmd != null) pshopCmd.setExecutor(pointsShop);
        getServer().getPluginManager().registerEvents(pointsShop, this);

        staff = new StaffTools(this);
        for (String n : new String[]{"vanish", "god", "speed", "nv", "invsee", "ec", "head"}) {
            var c8 = getCommand(n);
            if (c8 != null) { c8.setExecutor(staff); c8.setTabCompleter(staff); }
        }
        getServer().getPluginManager().registerEvents(staff, this);
        getServer().getPluginManager().registerEvents(new GuiGuard(), this);

        prompt = new Prompt(this);
        getServer().getPluginManager().registerEvents(prompt, this);

        fun = new FunTools(this);
        for (String n : new String[]{
                "fun", "bring", "goto", "tpall", "swap", "scatter", "launch", "freeze",
                "smite", "burn", "shuffle", "spin", "size", "potion", "playsound", "prank",
                "mobs", "ride", "heal", "feed", "fakeban", "fakeop", "fakejoin", "fakeleave",
                "jumpscare", "party", "firework", "confetti", "countdown", "roll", "pick",
                "announce", "disco"}) {
            var cf = getCommand(n);
            if (cf != null) { cf.setExecutor(fun); cf.setTabCompleter(fun); }
        }

        mining = new MiningWatch(this);
        mining.load();
        var miningCmd = getCommand("mining");
        if (miningCmd != null) { miningCmd.setExecutor(mining); miningCmd.setTabCompleter(mining); }
        getServer().getPluginManager().registerEvents(mining, this);

        blocks = new ServerBlocks(this);
        getServer().getPluginManager().registerEvents(blocks, this);

        holos = new Holograms(this);
        holos.load();
        var holoCmd = getCommand("holo");
        if (holoCmd != null) { holoCmd.setExecutor(holos); holoCmd.setTabCompleter(holos); }
        getServer().getPluginManager().registerEvents(holos, this);
        getServer().getScheduler().runTask(this, () -> { holos.load(); holos.respawnAll(); });
        getServer().getScheduler().runTaskTimer(this, () -> holos.heal(), 220L, 200L);

        npcs = new NpcManager(this);
        npcs.load();
        var npcCmd = getCommand("npc");
        if (npcCmd != null) { npcCmd.setExecutor(npcs); npcCmd.setTabCompleter(npcs); }
        getServer().getPluginManager().registerEvents(npcs, this);
        // worlds finish loading after onEnable, so put the NPCs out on the next tick
        getServer().getScheduler().runTask(this, () -> { npcs.load(); npcs.respawnAll(); });
        getServer().getScheduler().runTaskTimer(this, () -> npcs.heal(), 200L, 200L);

        webPanel = new WebPanel(this);
        webPanel.start();

        // Every 5 minutes, write everything that holds player data. A crash or a
        // power cut then costs at most five minutes, not the whole session.
        getServer().getScheduler().runTaskTimer(this, this::saveAll, 6000L, 6000L);
        startRestartClock();
        getLogger().info("BannarakZones enabled.");
    }

    /**
     * Daily restart. Paper's heap creeps up over a long uptime, so a server
     * that is meant to hold a hundred people is better off restarting once a
     * night than being left to crawl.
     *
     * <p>We warn, save, and then stop. systemd (or the start script) brings it
     * back. If nothing restarts the process this would just stop the server,
     * so it stays off until someone sets {@code restart.enabled}.
     */
    private void startRestartClock() {
        if (!getConfig().getBoolean("restart.enabled", false)) return;
        String at = getConfig().getString("restart.at", "04:00");
        java.time.LocalTime when;
        try {
            when = java.time.LocalTime.parse(at);
        } catch (RuntimeException e) {
            getLogger().warning("restart.at อ่านไม่ออก (" + at + ") ต้องเป็นแบบ 04:00 — ปิดรีสตาร์ตอัตโนมัติ");
            return;
        }
        List<Integer> warnAt = getConfig().getIntegerList("restart.warn-seconds");
        if (warnAt.isEmpty()) warnAt = List.of(300, 120, 60, 30, 10, 5, 4, 3, 2, 1);
        final Set<Integer> warned = new HashSet<>();
        final java.time.LocalTime target = when;
        final List<Integer> warns = warnAt;

        getServer().getScheduler().runTaskTimer(this, () -> {
            java.time.LocalTime now = java.time.LocalTime.now();
            long secs = java.time.Duration.between(now, target).getSeconds();
            if (secs < 0) secs += 86400;                 // target is tomorrow
            if (secs > 600) { warned.clear(); return; }   // nowhere near it yet

            for (int mark : warns) {
                if (secs <= mark && secs > mark - 1 && warned.add(mark)) {
                    String left = mark >= 60 ? (mark / 60) + " นาที" : mark + " วินาที";
                    getServer().broadcast(Notify.of(
                            "<gold>เซิร์ฟจะรีสตาร์ตในอีก <bold>" + left + "</bold> <gray>· เก็บของให้เรียบร้อย"));
                }
            }
            if (secs <= 1) {
                getServer().broadcast(Notify.of("<gold>กำลังรีสตาร์ต เจอกันอีกไม่กี่นาที"));
                saveAll();
                getServer().getScheduler().runTaskLater(this, () -> getServer().shutdown(), 40L);
            }
        }, 20L, 20L);
        getLogger().info("ตั้งรีสตาร์ตอัตโนมัติไว้ " + at + " ทุกวัน");
    }

    /** Writes every store that holds player data. Called on a timer and at shutdown. */
    private void saveAll() {
        if (store != null) store.save();
        if (money != null) money.save(false);
        if (missions != null) missions.save(false);
        if (locations != null) locations.save(false);
        if (mining != null) mining.save(false);
        if (points != null) points.save(false);
        if (cosmetics != null) cosmetics.save(false);
        if (ranks != null) ranks.save(false);
        if (daily != null) daily.save(false);
        if (market != null) market.save(false);
        if (punishments != null) punishments.save(false);
        if (chat != null) chat.save(false);
        if (adminTools != null) adminTools.save(false);
        if (npcs != null) npcs.save(false);
        if (holos != null) holos.save(false);
    }

    @Override
    public void onDisable() {
        if (webPanel != null) webPanel.stop();
        if (store != null) store.save();
        if (money != null) money.save(true);
        if (missions != null) missions.save(true);
        if (locations != null) locations.save(true);
        if (ranks != null) ranks.save(true);
        if (daily != null) daily.save(true);
        if (market != null) market.save(true);
        if (punishments != null) punishments.save(true);
        if (chat != null) chat.save(true);
        if (adminTools != null) adminTools.save(true);
        if (npcs != null) npcs.shutdown();
        if (holos != null) holos.shutdown();
        if (mining != null) mining.save(true);
        if (points != null) points.save(true);
        if (cosmetics != null) cosmetics.shutdown();
    }

    /** Offers our own money to Vault so other plugins see one balance, not two. */
    private void registerEconomy() {
        if (!getConfig().getBoolean("economy.register-with-vault", true)) return;
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not installed - our economy stays internal.");
            return;
        }
        try {
            getServer().getServicesManager().register(Economy.class, new VaultBridge(this, money),
                    this, org.bukkit.plugin.ServicePriority.Highest);
            getLogger().info("Registered our economy with Vault.");
        } catch (Throwable t) {
            getLogger().warning("Could not register our economy with Vault: " + t.getMessage());
        }
    }

    // ---------------- config ----------------

    public void readConfig() {
        reloadConfig();
        var c = getConfig();
        Material m = Material.matchMaterial(c.getString("zone.block", "BEACON"));
        zoneBlock = m == null ? Material.BEACON : m;
        Material am = Material.matchMaterial(c.getString("zone.admin-block", "REINFORCED_DEEPSLATE"));
        adminBlock = am == null ? Material.REINFORCED_DEEPSLATE : am;
        adminRadius = c.getInt("zone.admin-radius", 64);
        lockedWorlds.clear();
        for (String w : c.getStringList("protection.locked-worlds"))
            lockedWorlds.add(w.toLowerCase(Locale.ROOT));
        safeWorlds.clear();
        for (String w : c.getStringList("protection.no-mob-damage-worlds"))
            safeWorlds.add(w.toLowerCase(Locale.ROOT));

        defaultRadius = c.getInt("zone.default-radius", 16);
        maxRadius = c.getInt("zone.max-radius", 48);
        fullHeight = "full".equalsIgnoreCase(c.getString("zone.outsider-vertical", "full"));
        verticalRange = c.getInt("zone.vertical-range", 32);
        firstFree = c.getBoolean("zone.first-zone-free", true);
        minGap = c.getInt("zone.min-gap", 4);
        blockedWorlds = new HashSet<>(c.getStringList("zone.blocked-worlds"));
        radiusPrices.clear();
        var sec = c.getConfigurationSection("upgrades.radius");
        if (sec != null)
            for (String k : sec.getKeys(false))
                try { radiusPrices.put(Integer.parseInt(k), sec.getDouble(k)); } catch (NumberFormatException ignored) { }
        slotBase = c.getDouble("upgrades.slots.base-price", 20000);
        slotMultiplier = c.getDouble("upgrades.slots.multiplier", 2.0);
    }

    private void hookEconomy() {
        try {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
                getLogger().info("Economy hooked: " + economy.getName());
            } else {
                getLogger().warning("No Vault economy found - upgrades will be disabled.");
            }
        } catch (Throwable t) {
            getLogger().warning("Vault not present - upgrades will be disabled.");
        }
    }

    public ZoneStore store() { return store; }
    public EconomyManager money() { return money; }
    public MissionManager missions() { return missions; }
    public LocStore locations() { return locations; }
    public MenuManager menus() { return menus; }
    public ShopManager shop() { return shop; }
    public DiscordRanks ranks() { return ranks; }
    public DungeonManager dungeons() { return dungeons; }
    public CrateManager crates() { return crates; }
    public DailyRewards daily() { return daily; }
    public MarketManager market() { return market; }
    public CustomItems customItems() { return customItems; }
    public BackupManager backups() { return backups; }
    public Punishments punishments() { return punishments; }
    public AdminPanel adminPanel() { return adminPanel; }
    public WebPanel webPanel() { return webPanel; }
    public ChatManager chat() { return chat; }
    public NpcManager npcs() { return npcs; }
    public Holograms holos() { return holos; }
    public ServerBlocks blocks() { return blocks; }
    public StaffTools staff() { return staff; }

    public FunTools fun() { return fun; }

    public Prompt prompt() { return prompt; }
    public MiningWatch mining() { return mining; }
    public PointsManager points() { return points; }
    public Cosmetics cosmetics() { return cosmetics; }
    public PointsShop pointsShop() { return pointsShop; }
    public WorldManager worlds() { return worlds; }
    public Material adminBlock() { return adminBlock; }
    public int adminRadius() { return adminRadius; }

    /** True when ordinary players may not touch anything in this world. */
    public boolean worldLocked(String world) {
        return world != null && lockedWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    /** A world where hostile mobs cannot hurt anyone. */
    public boolean safeWorld(String world) {
        return world != null && safeWorlds.contains(world.toLowerCase(Locale.ROOT));
    }
    public AdminHud adminHud() { return adminHud; }
    public PlayerHud playerHud() { return playerHud; }
    public AdminTools adminTools() { return adminTools; }
    public ZoneGui gui() { return gui; }
    public Economy economy() { return economy; }
    public Material zoneBlock() { return zoneBlock; }
    public int defaultRadius() { return defaultRadius; }
    public int maxRadius() { return maxRadius; }
    public boolean outsiderFullHeight() { return fullHeight; }
    public int verticalRange() { return verticalRange; }
    public boolean firstZoneFree() { return firstFree; }
    public int minGap() { return minGap; }
    public boolean worldBlocked(String w) { return blockedWorlds.contains(w); }
    public boolean protect(String key) { return getConfig().getBoolean("protection." + key, true); }

    /** Next radius step above the given radius, or -1 if already maxed. */
    public int nextRadius(int current) {
        Integer next = radiusPrices.higherKey(current);
        if (next == null || next > maxRadius) return -1;
        return next;
    }

    public double radiusPrice(int targetRadius) {
        return radiusPrices.getOrDefault(targetRadius, Double.MAX_VALUE);
    }

    public double slotPrice(int currentSlots) {
        return slotBase * Math.pow(slotMultiplier, Math.max(0, currentSlots - 1));
    }

    // ---------------- messages ----------------

    public Component mm(String path, TagResolver... tags) {
        String raw = getConfig().getString("messages." + path, path);
        return MM.deserialize(raw, tags);
    }

    public void msg(CommandSender to, String path, TagResolver... tags) {
        String prefix = getConfig().getString("messages.prefix", "");
        to.sendMessage(MM.deserialize(prefix).append(mm(path, tags)));
    }

    public Component effect(String path, TagResolver... tags) {
        String raw = getConfig().getString("effects." + path, "");
        return MM.deserialize(raw, tags);
    }

    public static TagResolver p(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static String money(double amount) {
        if (amount >= 1_000_000) return String.format(Locale.US, "%,.0f", amount);
        return String.format(Locale.US, "%,.0f", amount);
    }

    // ---------------- helpers ----------------

    public boolean canBypass(Player p) {
        return p.hasPermission("zones.admin");
    }

    public void showBorder(Player viewer, Zone zone) {
        if (!getConfig().getBoolean("effects.show-border", true)) return;
        World w = Bukkit.getWorld(zone.world);
        if (w == null) return;

        final int pulses = Math.max(2, getConfig().getInt("effects.border-pulses", 12));
        final long period = Math.max(2L, getConfig().getLong("effects.border-period-ticks", 7L));
        final double edge = zone.radius + 0.5;

        new org.bukkit.scheduler.BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!viewer.isOnline() || step >= pulses) { cancel(); return; }
                double life = 1.0 - (double) step / pulses;   // 1.0 -> 0.0, the fade
                double spacing = 0.45 + (1.0 - life) * 2.2;   // dots drift apart as it fades
                int layers = life > 0.66 ? 3 : (life > 0.33 ? 2 : 1);

                for (double d = -edge; d <= edge; d += spacing) {
                    drawPost(w, zone.x + d, zone.z - edge, layers, life);
                    drawPost(w, zone.x + d, zone.z + edge, layers, life);
                    drawPost(w, zone.x - edge, zone.z + d, layers, life);
                    drawPost(w, zone.x + edge, zone.z + d, layers, life);
                }
                if (life > 0.25) {
                    corner(w, zone.x - edge, zone.z - edge, life);
                    corner(w, zone.x + edge, zone.z - edge, life);
                    corner(w, zone.x - edge, zone.z + edge, life);
                    corner(w, zone.x + edge, zone.z + edge, life);
                }
                step++;
            }

            private void drawPost(World world, double x, double z, int layers, double life) {
                int ground = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
                for (int i = 0; i < layers; i++) {
                    Location loc = new Location(world, x, ground + 1.1 + i * 1.1, z);
                    viewer.spawnParticle(Particle.END_ROD, loc, 1, 0.02, 0.02, 0.02, 0);
                }
                if (life > 0.6 && Math.random() < 0.08) {
                    Location spark = new Location(world, x, ground + 1.4, z);
                    viewer.spawnParticle(Particle.HAPPY_VILLAGER, spark, 1, 0.1, 0.1, 0.1, 0);
                }
            }

            private void corner(World world, double x, double z, double life) {
                int ground = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
                int height = (int) Math.ceil(6 * life);
                for (int i = 0; i < height; i++) {
                    Location loc = new Location(world, x, ground + 1.1 + i * 0.9, z);
                    viewer.spawnParticle(Particle.END_ROD, loc, 1, 0.02, 0.02, 0.02, 0);
                }
            }
        }.runTaskTimer(this, 0L, period);
    }

    public void playSound(Player p, String key, float pitch) {
        float vol = (float) getConfig().getDouble("effects.sound-volume", 0.7);
        p.playSound(p.getLocation(), key, vol, pitch);
    }

    // ---------------- command ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("zones.admin")) { msg(sender, "not-owner"); return true; }
            readConfig();
            if (missions != null) missions.loadDefinitions();
            if (menus != null) menus.load();
            if (shop != null) shop.load();
            if (ranks != null) ranks.load();
            if (crates != null) crates.load();
            if (customItems != null) customItems.load();
            msg(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            Zone here = store.zoneAt(player.getLocation());
            if (here != null && (here.isTrusted(player.getUniqueId()) || canBypass(player))) {
                gui.openMain(player, here);
            } else {
                sendHelp(player);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "admin" -> {
                if (!player.hasPermission("zones.admin")) {
                    Notify.chat(player, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น");
                    return true;
                }
                Zone here = store.zoneAt(player.getLocation());
                if (here == null) {
                    Notify.chat(player, "<red>ยืนในเขตก่อน แล้วค่อยสั่ง");
                    Notify.chat(player, "<gray>หรือวาง <white>" + adminBlock.name()
                            + " <gray>เพื่อสร้างเขตทีมงานใหม่ รัศมี " + adminRadius);
                    return true;
                }
                here.admin = !here.admin;
                here.showMessages = !here.admin;
                if (args.length > 1) {
                    try {
                        int r = Math.max(1, Math.min(512, Integer.parseInt(args[1])));
                        here.radius = r;
                    } catch (NumberFormatException ex) {
                        Notify.chat(player, "<red>รัศมีต้องเป็นตัวเลข");
                    }
                }
                store.save();
                Notify.popup(player,
                        here.admin ? "<gold>เป็นเขตทีมงานแล้ว" : "<yellow>กลับเป็นเขตธรรมดา",
                        "<gray>รัศมี " + here.radius + " บล็อก", "block.beacon.power_select", 1.0f);
                showBorder(player, here);
                return true;
            }
            case "lockworld" -> {
                if (!player.hasPermission("zones.admin")) {
                    Notify.chat(player, "<red>คำสั่งนี้สำหรับทีมงานเท่านั้น");
                    return true;
                }
                String w = args.length > 1 ? args[1] : player.getWorld().getName();
                List<String> locked = new ArrayList<>(getConfig().getStringList("protection.locked-worlds"));
                boolean on = locked.stream().noneMatch(x -> x.equalsIgnoreCase(w));
                if (on) locked.add(w);
                else locked.removeIf(x -> x.equalsIgnoreCase(w));
                getConfig().set("protection.locked-worlds", locked);
                saveConfig();
                readConfig();
                Notify.chat(player, on
                        ? "<green>ล็อกโลก <yellow>" + w + " <green>แล้ว คนธรรมดาแตะอะไรไม่ได้เลย"
                        : "<yellow>ปลดล็อกโลก <white>" + w + " <yellow>แล้ว");
                return true;
            }
            case "info" -> {
                Zone here = store.zoneAt(player.getLocation());
                if (here == null) {
                    player.sendMessage(MM.deserialize("<gray>ตรงนี้ไม่มีเขตของใคร"));
                } else {
                    player.sendMessage(MM.deserialize(
                            "<gold>เขตของ <yellow>" + here.ownerName
                                    + " <gray>| รัศมี <white>" + here.radius
                                    + " <gray>| สมาชิก <white>" + here.members.size()));
                }
            }
            case "list" -> {
                List<Zone> mine = store.zonesOf(player.getUniqueId());
                player.sendMessage(MM.deserialize("<gold>เขตของคุณ: <white>" + mine.size()
                        + "<gray>/<white>" + store.slotsOf(player.getUniqueId())));
                for (Zone z : mine)
                    player.sendMessage(MM.deserialize("<gray> - <white>" + z.world + " <gray>("
                            + z.x + ", " + z.y + ", " + z.z + ") <gray>รัศมี <white>" + z.radius));
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(MM.deserialize("<gold>=== เขตป้องกัน ==="));
        p.sendMessage(MM.deserialize(blocks != null && blocks.requireTag()
                ? "<white>วาง <aqua>บล็อกจับจอง <white>เพื่อสร้างเขต <dark_gray>(/blocks)"
                : "<white>วาง <yellow>" + zoneBlock.name().toLowerCase(Locale.ROOT)
                  + " <white>เพื่อสร้างเขต"));
        p.sendMessage(MM.deserialize("<white>คลิกขวาที่บล็อกนั้นเพื่อเปิดเมนูตั้งค่า"));
        p.sendMessage(MM.deserialize("<gray>/zone info <white>- ดูว่าตรงนี้เป็นเขตของใคร"));
        p.sendMessage(MM.deserialize("<gray>/zone list <white>- ดูเขตทั้งหมดของคุณ"));
    }
}
