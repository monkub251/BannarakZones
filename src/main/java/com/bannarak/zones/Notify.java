package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * One place for every message the server shows a player, so the whole
 * plugin speaks with the same voice. Styles stay deliberately quiet.
 */
public final class Notify {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static BannarakZones plugin;

    private Notify() { }

    public static void init(BannarakZones p) { plugin = p; }

    private static boolean sounds() {
        return plugin == null || plugin.getConfig().getBoolean("notify.sound", true);
    }

    /** Quiet line above the hotbar. The default for anything routine. */
    public static void bar(Player p, String mini) {
        p.sendActionBar(MM.deserialize(mini));
    }

    public static void bar(Player p, String mini, String sound, float pitch) {
        bar(p, mini);
        if (sounds() && sound != null) p.playSound(p.getLocation(), sound, 0.7f, pitch);
    }

    /** Fading centre-screen text. Reserved for milestones. */
    public static void popup(Player p, String mainMini, String subMini, String sound, float pitch) {
        Component main = MM.deserialize(mainMini == null ? "" : mainMini);
        Component sub = MM.deserialize(subMini == null ? "" : subMini);
        p.showTitle(Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(600))));
        if (sounds() && sound != null) p.playSound(p.getLocation(), sound, 0.8f, pitch);
    }

    /** Chat line with the server prefix. */
    public static void chat(Player p, String mini) {
        String prefix = plugin == null ? "" : plugin.getConfig().getString("messages.prefix", "");
        p.sendMessage(MM.deserialize(prefix + mini));
    }

    public static Component of(String mini) {
        return MM.deserialize(mini);
    }
}
