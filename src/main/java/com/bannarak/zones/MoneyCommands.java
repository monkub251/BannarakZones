package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MoneyCommands implements CommandExecutor, TabCompleter, Listener {

    private final BannarakZones plugin;
    private final EconomyManager eco;

    public MoneyCommands(BannarakZones plugin) {
        this.plugin = plugin;
        this.eco = plugin.money();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!eco.hasAccount(p.getUniqueId())) {
            eco.createAccount(p.getUniqueId(), p.getName());
            Notify.chat(p, "<gray>เปิดบัญชีให้แล้ว เงินเริ่มต้น <gold>"
                    + eco.format(eco.startingBalance()));
        }
        eco.rememberName(p.getUniqueId(), p.getName());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "money" -> money(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "eco" -> eco(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private void money(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
            sender.sendMessage(Notify.of("<gold>เงินของคุณ: <yellow>" + eco.format(eco.get(p.getUniqueId()))));
            return;
        }
        UUID target = eco.lookup(args[0]);
        if (target == null) {
            sender.sendMessage(Notify.of("<red>ไม่พบผู้เล่นชื่อนี้"));
            return;
        }
        sender.sendMessage(Notify.of("<gold>เงินของ <yellow>" + eco.nameOf(target)
                + "<gold>: <yellow>" + eco.format(eco.get(target))));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (args.length < 2) {
            sender.sendMessage(Notify.of("<gray>ใช้: /pay <ชื่อ> <จำนวน>"));
            return;
        }
        UUID target = eco.lookup(args[0]);
        if (target == null) { Notify.chat(p, "<red>ไม่พบผู้เล่นชื่อนี้"); return; }
        if (target.equals(p.getUniqueId())) { Notify.chat(p, "<red>โอนให้ตัวเองไม่ได้"); return; }

        double amount;
        try { amount = Double.parseDouble(args[1]); }
        catch (NumberFormatException ex) { Notify.chat(p, "<red>จำนวนเงินไม่ถูกต้อง"); return; }

        double min = plugin.getConfig().getDouble("economy.pay-minimum", 1);
        if (amount < min) { Notify.chat(p, "<red>โอนขั้นต่ำ <yellow>" + eco.format(min)); return; }

        if (!eco.transfer(p.getUniqueId(), target, amount)) {
            Notify.chat(p, "<red>เงินไม่พอ");
            return;
        }
        eco.save(false);
        Notify.bar(p, "<green>โอน <gold>" + eco.format(amount) + " <green>ให้ <yellow>"
                + eco.nameOf(target), "entity.experience_orb.pickup", 1.2f);
        Player receiver = Bukkit.getPlayer(target);
        if (receiver != null)
            Notify.bar(receiver, "<green>ได้รับ <gold>" + eco.format(amount) + " <green>จาก <yellow>"
                    + p.getName(), "entity.experience_orb.pickup", 1.6f);
    }

    private void baltop(CommandSender sender) {
        List<Map.Entry<UUID, Double>> top = eco.top(10);
        sender.sendMessage(Notify.of("<gold>=== คนรวยที่สุด ==="));
        int rank = 1;
        for (Map.Entry<UUID, Double> e : top) {
            String colour = switch (rank) {
                case 1 -> "<gold>";
                case 2 -> "<white>";
                case 3 -> "<#cd7f32>";
                default -> "<gray>";
            };
            sender.sendMessage(Notify.of(colour + rank + ". <yellow>" + eco.nameOf(e.getKey())
                    + " <gray>- <gold>" + eco.format(e.getValue())));
            rank++;
        }
        if (top.isEmpty()) sender.sendMessage(Notify.of("<gray>ยังไม่มีใครมีเงินเลย"));
    }

    private void eco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zones.admin.eco")) {
            sender.sendMessage(Notify.of("<red>ไม่มีสิทธิ์"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Notify.of("<gray>ใช้: /eco <give|take|set> <ชื่อ> <จำนวน>"));
            return;
        }
        UUID target = eco.lookup(args[1]);
        if (target == null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
            target = op.getUniqueId();
            eco.createAccount(target, args[1]);
        }
        double amount;
        try { amount = Double.parseDouble(args[2]); }
        catch (NumberFormatException ex) { sender.sendMessage(Notify.of("<red>จำนวนเงินไม่ถูกต้อง")); return; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> eco.add(target, amount);
            case "take" -> eco.take(target, amount);
            case "set" -> eco.set(target, amount);
            default -> {
                sender.sendMessage(Notify.of("<gray>ใช้: /eco <give|take|set> <ชื่อ> <จำนวน>"));
                return;
            }
        }
        eco.save(true);
        sender.sendMessage(Notify.of("<green>ยอดของ <yellow>" + eco.nameOf(target)
                + " <green>ตอนนี้ <gold>" + eco.format(eco.get(target))));
        Player online = Bukkit.getPlayer(target);
        if (online != null)
            Notify.bar(online, "<gold>ยอดเงินของคุณเปลี่ยนเป็น <yellow>" + eco.format(eco.get(target)),
                    "entity.experience_orb.pickup", 1.0f);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("eco") && args.length == 1) {
            for (String s : List.of("give", "take", "set"))
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        int nameIndex = cmd.getName().equalsIgnoreCase("eco") ? 1 : 0;
        if (args.length == nameIndex + 1) {
            String prefix = args[nameIndex].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        return out;
    }
}
