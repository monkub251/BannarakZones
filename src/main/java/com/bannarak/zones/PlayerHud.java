package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.*;

import java.util.List;

/**
 * The sidebar every player sees: their name, their title and their money,
 * refreshed on a timer. Nothing here is a command; it is just always there.
 */
public class PlayerHud implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BannarakZones plugin;

    public PlayerHud(BannarakZones plugin) {
        this.plugin = plugin;
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("hud.enabled", true);
    }

    public void start() {
        long every = Math.max(10L, plugin.getConfig().getLong("hud.refresh-ticks", 20));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!on()) return;
            for (Player p : Bukkit.getOnlinePlayers()) draw(p);
        }, 40L, every);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!on()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) draw(e.getPlayer());
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // The board belongs to the player object, so it goes with them.
    }

    /**
     * Draws straight onto the player's own scoreboard. Lines are entries on
     * a sidebar objective; rewriting them in place avoids the flicker you
     * get from throwing the board away every tick.
     */
    private void draw(Player p) {
        // The admin sidebar is a different view of the same corner. Let it win.
        if (plugin.adminHud() != null && plugin.adminHud().isOn(p.getUniqueId())) return;

        Scoreboard board = p.getScoreboard();
        if (board == null || board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(board);
        }

        Objective obj = board.getObjective("bzhud");
        if (obj == null) {
            obj = board.registerNewObjective("bzhud", Criteria.DUMMY,
                    MM.deserialize(plugin.getConfig().getString("hud.title",
                            "<gradient:#f0cf8a:#b8863a><bold>BANNARAKX</bold></gradient>")));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        List<String> template = plugin.getConfig().getStringList("hud.lines");
        if (template.isEmpty()) template = List.of(
                "<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬",
                "<gray>ชื่อ <white><player>",
                "<gray>ยศ <yellow><rank>",
                "<gray>เงิน <gold><money>",
                "",
                "<gray>ออนไลน์ <white><online>",
                "<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬");

        // Entries must be unique, so pad shorter repeats with hidden colour codes.
        for (int i = 0; i < template.size(); i++) {
            String text = fill(template.get(i), p) + "§r".repeat(i);
            int score = template.size() - i;
            String teamName = "bz" + i;
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            String holder = "§" + "0123456789abcdef".charAt(i % 16) + "§r";
            if (!team.hasEntry(holder)) team.addEntry(holder);
            team.prefix(MM.deserialize(text));
            team.suffix(Component.empty());
            obj.getScore(holder).setScore(score);
        }
        // Drop anything left over from a longer template.
        for (int i = template.size(); i < 16; i++) {
            Team old = board.getTeam("bz" + i);
            if (old == null) continue;
            for (String entry : old.getEntries()) board.resetScores(entry);
            old.unregister();
        }
    }

    /** Rotates through the tips in config so the sidebar teaches commands on its own. */
    private String tip() {
        List<String> tips = plugin.getConfig().getStringList("hud.tips");
        if (tips.isEmpty()) return "";
        long every = Math.max(1L, plugin.getConfig().getLong("hud.tip-seconds", 6));
        int i = (int) ((System.currentTimeMillis() / 1000L / every) % tips.size());
        return tips.get(i);
    }

    private String fill(String s, Player p) {
        var rank = plugin.ranks() == null ? null : plugin.ranks().rankOf(p.getUniqueId());
        return s.replace("<player>", p.getName())
                .replace("<rank>", rank == null ? "-" : rank.display)
                .replace("<money>", plugin.money().format(plugin.money().get(p.getUniqueId())))
                .replace("<points>", plugin.points() == null ? "0"
                        : plugin.points().format(plugin.points().get(p.getUniqueId())))
                .replace("<tip>", tip())
                .replace("<online>", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("<max>", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("<world>", p.getWorld().getName())
                .replace("<zones>", String.valueOf(plugin.store().zonesOf(p.getUniqueId()).size()));
    }
}
