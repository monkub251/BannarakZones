package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.*;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** A sidebar for whoever is running the server. Players never see it. */
public class AdminHud implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BannarakZones plugin;
    private final Set<UUID> showing = new HashSet<>();

    public AdminHud(BannarakZones plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(20L, plugin.getConfig().getLong("admin-hud.refresh-ticks", 40L));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 100L, period);
    }

    private void refreshAll() {
        for (UUID id : Set.copyOf(showing)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !p.hasPermission("zones.admin")) {
                showing.remove(id);
                if (p != null) clear(p);
                continue;
            }
            render(p);
        }
    }

    private static String colourTps(double tps) {
        String c = tps >= 19 ? "<green>" : tps >= 16 ? "<yellow>" : "<red>";
        return c + String.format(Locale.US, "%.1f", Math.min(20, tps));
    }

    private void render(Player p) {
        ScoreboardManager man = Bukkit.getScoreboardManager();
        if (man == null) return;
        Scoreboard board = p.getScoreboard();
        if (board == man.getMainScoreboard()) {
            board = man.getNewScoreboard();
            p.setScoreboard(board);
        }
        Objective obj = board.getObjective("bzadmin");
        if (obj == null)
            obj = board.registerNewObjective("bzadmin", Criteria.DUMMY,
                    MM.deserialize(plugin.getConfig().getString("admin-hud.title", "<gold>ผู้ดูแล")));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        double[] tps = Bukkit.getTPS();
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L;
        long max = Runtime.getRuntime().maxMemory() / 1048576L;
        int entities = 0, chunks = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            entities += w.getEntityCount();
            chunks += w.getLoadedChunks().length;
        }

        String[] lines = {
                "<gray>TPS " + colourTps(tps[0]) + " <dark_gray>/ " + colourTps(tps[1]),
                "<gray>แรม <white>" + used + "<dark_gray>/" + max + " MB",
                "<gray>ผู้เล่น <white>" + Bukkit.getOnlinePlayers().size(),
                "<gray>เอนทิตี <white>" + entities,
                "<gray>ชังก์ <white>" + chunks,
                "<dark_gray>—",
                "<gray>เขตทั้งหมด <white>" + plugin.store().all().size(),
                "<gray>ประกาศขาย <white>" + plugin.market().listingCount(),
                "<gray>ตำแหน่ง <white>" + p.getLocation().getBlockX() + " "
                        + p.getLocation().getBlockY() + " " + p.getLocation().getBlockZ(),
        };

        for (String entry : board.getEntries()) board.resetScores(entry);
        int score = lines.length;
        for (String line : lines) {
            Team team = board.getTeam("bz" + score);
            if (team == null) team = board.registerNewTeam("bz" + score);
            String key = unique(score);
            team.addEntry(key);
            team.prefix(MM.deserialize(line));
            obj.getScore(key).setScore(score);
            score--;
        }
    }

    /** Scoreboard entries have to be distinct, so hide an index in colour codes. */
    private static String unique(int index) {
        return "§" + Integer.toHexString(index % 16) + "§r";
    }

    private void clear(Player p) {
        ScoreboardManager man = Bukkit.getScoreboardManager();
        if (man != null) p.setScoreboard(man.getNewScoreboard());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        showing.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (!p.hasPermission("zones.admin")) { Notify.chat(p, "<red>ไม่มีสิทธิ์"); return true; }
        if (showing.remove(p.getUniqueId())) {
            clear(p);
            Notify.bar(p, "<gray>ปิดแผงผู้ดูแลแล้ว", null, 1f);
        } else {
            showing.add(p.getUniqueId());
            render(p);
            Notify.bar(p, "<green>เปิดแผงผู้ดูแลแล้ว", "block.note_block.pling", 1.4f);
        }
        return true;
    }

    public Component title() {
        return MM.deserialize("<gold>ผู้ดูแล");
    }
}
