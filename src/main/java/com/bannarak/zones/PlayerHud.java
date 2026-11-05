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

        Component title = MM.deserialize(plugin.getConfig().getString("hud.title",
                "<gradient:#f0cf8a:#b8863a><bold>BANNARAKX</bold></gradient>"));
        Objective obj = board.getObjective("bzhud");
        if (obj == null) {
            obj = board.registerNewObjective("bzhud", Criteria.DUMMY, title);
        } else {
            obj.displayName(title);
        }
        // Only one objective can hold the sidebar, and another plugin claiming
        // it used to push ours out for good: the slot was set once, at
        // creation, and never asked for again. Take it back on every refresh.
        if (obj.getDisplaySlot() != DisplaySlot.SIDEBAR) obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> template = plugin.getConfig().getStringList("hud.lines");
        if (template.isEmpty()) template = List.of(
                "<gray>ยศ <yellow><rank>",
                "<gray>เงิน <gold><money>",
                "<gray>พ้อย <yellow><points>",
                "<gray>ที่นี่ <white><here>",
                "<gray>ออนไลน์ <white><online>");

        for (int i = 0; i < template.size(); i++) {
            // The text used to carry a run of §r to keep repeated lines apart.
            // Adventure now refuses a § inside MiniMessage, so every line after
            // the first threw and the sidebar showed one row. Uniqueness comes
            // from the holder below; the padding was never needed.
            String text = fill(template.get(i), p);
            int score = template.size() - i;
            String teamName = "bz" + i;
            Team team = board.getTeam(teamName);
            if (team == null) team = board.registerNewTeam(teamName);
            String holder = "§" + "0123456789abcdef".charAt(i % 16) + "§r";
            if (!team.hasEntry(holder)) team.addEntry(holder);
            team.prefix(render(text));
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

    /** Whether we have already complained about a line that will not parse. */
    private final java.util.Set<String> moaned = new java.util.HashSet<>();

    /**
     * Turns a configured line into a component without letting one bad line
     * take the whole sidebar down with it. A typo in config.yml should cost
     * that line, not every line under it.
     */
    private Component render(String text) {
        try {
            return MM.deserialize(text);
        } catch (RuntimeException ex) {
            if (moaned.add(text))
                plugin.getLogger().warning("แถบข้างมีบรรทัดที่อ่านไม่ออก: " + text
                        + " (" + ex.getMessage() + ")");
            return Component.text(text.replaceAll("<[^>]*>", ""));
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

    /**
     * Whose land you are standing on — the one thing a sidebar can tell you
     * that you cannot work out by looking around.
     */
    private String here(Player p) {
        Zone z = plugin.store().zoneAt(p.getLocation());
        if (z == null) return "<green>ที่โล่ง";
        if (p.getUniqueId().equals(z.owner)) return "<aqua>เขตของคุณ";
        if (z.members.contains(p.getUniqueId())) return "<aqua>เขตที่คุณเข้าได้";
        return "<red>เขตของ " + (z.ownerName == null ? "?" : z.ownerName);
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
                .replace("<zones>", String.valueOf(plugin.store().zonesOf(p.getUniqueId()).size()))
                .replace("<here>", here(p));
    }
}
