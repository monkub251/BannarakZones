package com.bannarak.zones;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zips the worlds and our own data on a timer, and throws away the oldest. */
public class BackupManager implements CommandExecutor {

    private final BannarakZones plugin;
    private volatile boolean running = false;

    public BackupManager(BannarakZones plugin) {
        this.plugin = plugin;
    }

    private File folder() {
        String path = plugin.getConfig().getString("backup.folder", "backups");
        File f = new File(path);
        if (!f.isAbsolute()) f = new File(plugin.getServer().getWorldContainer(), path);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public void schedule() {
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            plugin.getLogger().warning("Backups are switched off in the config.");
            return;
        }
        double hours = Math.max(0.25, plugin.getConfig().getDouble("backup.every-hours", 6));
        long ticks = (long) (hours * 72000L);
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> run(Bukkit.getConsoleSender(), false), ticks, ticks);
        plugin.getLogger().info("Backups run every " + hours + " hour(s) into " + folder().getPath());
    }

    /** Saves worlds on the main thread, then zips in the background. */
    public void run(CommandSender who, boolean announce) {
        if (running) {
            who.sendMessage(Notify.of("<yellow>กำลังสำรองข้อมูลอยู่แล้ว"));
            return;
        }
        running = true;

        plugin.store().save();
        plugin.money().save(true);
        plugin.missions().save(true);
        plugin.locations().save(true);
        plugin.ranks().save(true);
        plugin.daily().save(true);
        plugin.market().save(true);

        List<File> targets = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            w.save();
            targets.add(w.getWorldFolder());
        }
        if (plugin.getConfig().getBoolean("backup.include-plugin-data", true))
            targets.add(plugin.getDataFolder());

        if (announce)
            Bukkit.broadcast(Notify.of("<gray>[ระบบ] กำลังสำรองข้อมูล อาจกระตุกเล็กน้อย"));

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date());
        File out = new File(folder(), "backup_" + stamp + ".zip");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            long started = System.currentTimeMillis();
            boolean ok = zip(targets, out);
            long seconds = (System.currentTimeMillis() - started) / 1000;
            prune();
            running = false;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (ok) {
                    String size = String.format(Locale.US, "%.1f", out.length() / 1048576.0);
                    plugin.getLogger().info("Backup written: " + out.getName() + " (" + size + " MB, " + seconds + "s)");
                    who.sendMessage(Notify.of("<green>สำรองข้อมูลเสร็จ <gray>" + out.getName()
                            + " <dark_gray>(" + size + " MB, " + seconds + " วินาที)"));
                } else {
                    who.sendMessage(Notify.of("<red>สำรองข้อมูลไม่สำเร็จ ดูรายละเอียดใน console"));
                }
            });
        });
    }

    private boolean zip(List<File> targets, File out) {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            zos.setLevel(plugin.getConfig().getInt("backup.compression", 4));
            for (File target : targets) {
                if (target == null || !target.exists()) continue;
                Path root = target.toPath();
                String prefix = target.getName();
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.equals("session.lock") || name.endsWith(".zip")) return FileVisitResult.CONTINUE;
                        try {
                            zos.putNextEntry(new ZipEntry(prefix + "/" + root.relativize(file).toString().replace('\\', '/')));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException ignored) {
                            // a file being written while we read it is not worth aborting for
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Backup failed: " + ex.getMessage());
            out.delete();
            return false;
        }
    }

    private void prune() {
        int keep = plugin.getConfig().getInt("backup.keep", 10);
        if (keep <= 0) return;
        File[] files = folder().listFiles((d, n) -> n.startsWith("backup_") && n.endsWith(".zip"));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - keep; i++) {
            if (files[i].delete()) plugin.getLogger().info("Removed old backup " + files[i].getName());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("zones.admin")) {
            sender.sendMessage(Notify.of("<red>ไม่มีสิทธิ์"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            File[] files = folder().listFiles((d, n) -> n.endsWith(".zip"));
            if (files == null || files.length == 0) {
                sender.sendMessage(Notify.of("<gray>ยังไม่มีไฟล์สำรอง"));
                return true;
            }
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            sender.sendMessage(Notify.of("<gold>ไฟล์สำรอง <gray>(" + files.length + ")"));
            for (int i = 0; i < Math.min(10, files.length); i++)
                sender.sendMessage(Notify.of("<gray> - <white>" + files[i].getName() + " <dark_gray>"
                        + String.format(Locale.US, "%.1f", files[i].length() / 1048576.0) + " MB"));
            return true;
        }
        run(sender, plugin.getConfig().getBoolean("backup.announce", true));
        return true;
    }
}
