package com.bannarak.zones;

import com.sun.net.httpserver.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * A small web console for staff. Nothing here trusts the browser: every
 * request carries a session that was handed out in exchange for a one-shot
 * code printed in game, and every Bukkit call hops back to the main thread.
 */
public class WebPanel {

    private final BannarakZones plugin;
    private final SecureRandom random = new SecureRandom();

    private HttpServer server;
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();   // code -> who asked
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public WebPanel(BannarakZones plugin) {
        this.plugin = plugin;
    }

    private record Pending(UUID player, String name, long expires) { }

    private record Session(UUID player, String name, long expires) { }

    private static class Attempts {
        int failures;
        long lockedUntil;
    }

    // ---------------- lifecycle ----------------

    public void start() {
        if (!plugin.getConfig().getBoolean("web.enabled", false)) {
            plugin.getLogger().info("Web panel is off (web.enabled: false).");
            return;
        }
        int port = plugin.getConfig().getInt("web.port", 8765);
        String bind = plugin.getConfig().getString("web.bind", "0.0.0.0");
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::serveStatic);
            server.createContext("/api/login", this::apiLogin);
            server.createContext("/api/status", secure(this::apiStatus));
            server.createContext("/api/players", secure(this::apiPlayers));
            server.createContext("/api/bans", secure(this::apiBans));
            server.createContext("/api/economy", secure(this::apiEconomy));
            server.createContext("/api/worlds", secure(this::apiWorlds));
            server.createContext("/api/action", secure(this::apiAction));
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            plugin.getLogger().info("Web panel listening on " + bind + ":" + port);
            plugin.getLogger().warning("The web panel speaks plain HTTP. Only expose it on a network you trust, "
                    + "or put it behind a tunnel that adds TLS.");
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not start the web panel: " + ex.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    // ---------------- codes and sessions ----------------

    public String issueCode(Player p) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        long ttl = plugin.getConfig().getLong("web.code-minutes", 5) * 60_000L;
        codes.values().removeIf(c -> c.expires() < System.currentTimeMillis());
        codes.put(code, new Pending(p.getUniqueId(), p.getName(), System.currentTimeMillis() + ttl));
        return code;
    }

    private String newSession(Pending who) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long ttl = plugin.getConfig().getLong("web.session-hours", 2) * 3_600_000L;
        sessions.put(token, new Session(who.player(), who.name(), System.currentTimeMillis() + ttl));
        return token;
    }

    private Session sessionOf(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("bzsession")) {
                Session s = sessions.get(kv[1]);
                if (s == null) return null;
                if (s.expires() < System.currentTimeMillis()) { sessions.remove(kv[1]); return null; }
                return s;
            }
        }
        return null;
    }

    private HttpHandler secure(HttpHandler inner) {
        return ex -> {
            if (sessionOf(ex) == null) {
                send(ex, 401, "application/json", "{\"error\":\"not logged in\"}");
                return;
            }
            inner.handle(ex);
        };
    }

    // ---------------- helpers ----------------

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Pulls "key" out of a flat JSON object without dragging in a parser. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return end < 0 ? null : json.substring(i + 1, end);
        }
        int end = i;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Runs the body on the server thread and waits briefly for the answer. */
    private <T> T sync(Callable<T> call, T fallback) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, call).get(4, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String clientIp(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    // ---------------- endpoints ----------------

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..")) { send(ex, 400, "text/plain", "no"); return; }
        try (InputStream in = plugin.getResource("web" + path)) {
            if (in == null) { send(ex, 404, "text/plain", "not found"); return; }
            byte[] data = in.readAllBytes();
            String type = path.endsWith(".css") ? "text/css"
                    : path.endsWith(".js") ? "application/javascript"
                    : path.endsWith(".svg") ? "image/svg+xml" : "text/html";
            ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        }
    }

    private void apiLogin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "application/json", "{}"); return; }
        String ip = clientIp(ex);
        Attempts a = attempts.computeIfAbsent(ip, k -> new Attempts());
        if (a.lockedUntil > System.currentTimeMillis()) {
            send(ex, 429, "application/json", "{\"error\":\"ลองผิดหลายครั้ง รออีกสักพัก\"}");
            return;
        }
        String code = field(body(ex), "code");
        Pending pending = code == null ? null : codes.get(code);
        if (pending == null || pending.expires() < System.currentTimeMillis()) {
            a.failures++;
            if (a.failures >= plugin.getConfig().getInt("web.max-attempts", 5)) {
                a.lockedUntil = System.currentTimeMillis()
                        + plugin.getConfig().getLong("web.lock-minutes", 10) * 60_000L;
                a.failures = 0;
                plugin.getLogger().warning("Web panel: too many bad codes from " + ip + ", locked out.");
            }
            send(ex, 403, "application/json", "{\"error\":\"รหัสไม่ถูกต้องหรือหมดอายุ\"}");
            return;
        }
        codes.remove(code);
        a.failures = 0;
        String token = newSession(pending);
        ex.getResponseHeaders().add("Set-Cookie",
                "bzsession=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                        + plugin.getConfig().getLong("web.session-hours", 2) * 3600);
        plugin.getLogger().info("Web panel: " + pending.name() + " signed in from " + ip);
        send(ex, 200, "application/json", "{\"ok\":true,\"name\":\"" + esc(pending.name()) + "\"}");
    }

    private void apiStatus(HttpExchange ex) throws IOException {
        String json = sync(() -> {
            double[] tps = Bukkit.getTPS();
            long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L;
            long max = Runtime.getRuntime().maxMemory() / 1048576L;
            int entities = 0, chunks = 0;
            for (World w : Bukkit.getWorlds()) { entities += w.getEntityCount(); chunks += w.getLoadedChunks().length; }
            return "{"
                    + "\"tps\":" + String.format(Locale.US, "%.2f", Math.min(20, tps[0]))
                    + ",\"ramUsed\":" + used
                    + ",\"ramMax\":" + max
                    + ",\"players\":" + Bukkit.getOnlinePlayers().size()
                    + ",\"maxPlayers\":" + Bukkit.getMaxPlayers()
                    + ",\"entities\":" + entities
                    + ",\"chunks\":" + chunks
                    + ",\"zones\":" + plugin.store().all().size()
                    + ",\"listings\":" + plugin.market().listingCount()
                    + ",\"dungeons\":" + plugin.dungeons().activeRuns()
                    + ",\"totalMoney\":" + Math.round(plugin.money().totalMoney())
                    + ",\"version\":\"" + esc(Bukkit.getVersion()) + "\""
                    + "}";
        }, "{}");
        send(ex, 200, "application/json", json);
    }

    private void apiPlayers(HttpExchange ex) throws IOException {
        String json = sync(() -> {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!first) sb.append(',');
                first = false;
                var rank = plugin.ranks().rankOf(p.getUniqueId());
                sb.append("{\"name\":\"").append(esc(p.getName()))
                        .append("\",\"uuid\":\"").append(p.getUniqueId())
                        .append("\",\"health\":").append((int) p.getHealth())
                        .append(",\"food\":").append(p.getFoodLevel())
                        .append(",\"world\":\"").append(esc(p.getWorld().getName()))
                        .append("\",\"x\":").append(p.getLocation().getBlockX())
                        .append(",\"y\":").append(p.getLocation().getBlockY())
                        .append(",\"z\":").append(p.getLocation().getBlockZ())
                        .append(",\"gamemode\":\"").append(p.getGameMode().name().toLowerCase(Locale.ROOT))
                        .append("\",\"money\":").append(Math.round(plugin.money().get(p.getUniqueId())))
                        .append(",\"rank\":\"").append(esc(rank == null ? "-" : rank.id))
                        .append("\",\"ping\":").append(p.getPing())
                        .append(",\"muted\":").append(plugin.punishments().isMuted(p.getUniqueId()))
                        .append(",\"frozen\":").append(plugin.adminPanel().frozen().contains(p.getUniqueId()))
                        .append(",\"op\":").append(p.hasPermission("zones.admin"))
                        .append('}');
            }
            return sb.append(']').toString();
        }, "[]");
        send(ex, 200, "application/json", json);
    }

    private void apiBans(HttpExchange ex) throws IOException {
        String json = sync(() -> {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            List<Punishments.Entry> list = plugin.punishments().history();
            for (int i = list.size() - 1; i >= 0; i--) {
                Punishments.Entry e = list.get(i);
                if (e.lifted) continue;
                if (e.type != Punishments.Type.BAN && e.type != Punishments.Type.MUTE) continue;
                if (e.until != 0 && e.until < System.currentTimeMillis()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(esc(e.targetName))
                        .append("\",\"uuid\":\"").append(e.target)
                        .append("\",\"type\":\"").append(e.type.name())
                        .append("\",\"reason\":\"").append(esc(e.reason))
                        .append("\",\"staff\":\"").append(esc(e.staff))
                        .append("\",\"left\":\"").append(esc(Punishments.readable(e.until)))
                        .append("\"}");
            }
            return sb.append(']').toString();
        }, "[]");
        send(ex, 200, "application/json", json);
    }

    private void apiEconomy(HttpExchange ex) throws IOException {
        String json = sync(() -> {
            StringBuilder sb = new StringBuilder("{\"total\":")
                    .append(Math.round(plugin.money().totalMoney()))
                    .append(",\"accounts\":").append(plugin.money().accountCount())
                    .append(",\"top\":[");
            boolean first = true;
            for (Map.Entry<UUID, Double> e : plugin.money().top(10)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(esc(plugin.money().nameOf(e.getKey())))
                        .append("\",\"money\":").append(Math.round(e.getValue())).append('}');
            }
            return sb.append("]}").toString();
        }, "{}");
        send(ex, 200, "application/json", json);
    }

    private void apiWorlds(HttpExchange ex) throws IOException {
        String json = sync(() -> {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (World w : Bukkit.getWorlds()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(esc(w.getName()))
                        .append("\",\"players\":").append(w.getPlayers().size())
                        .append(",\"entities\":").append(w.getEntityCount())
                        .append(",\"chunks\":").append(w.getLoadedChunks().length)
                        .append(",\"time\":").append(w.getTime())
                        .append(",\"storm\":").append(w.hasStorm())
                        .append('}');
            }
            return sb.append(']').toString();
        }, "[]");
        send(ex, 200, "application/json", json);
    }

    private void apiAction(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "application/json", "{}"); return; }
        Session session = sessionOf(ex);
        String raw = body(ex);
        String action = field(raw, "action");
        String target = field(raw, "target");
        String value = field(raw, "value");
        if (action == null) { send(ex, 400, "application/json", "{\"error\":\"no action\"}"); return; }

        String result = sync(() -> perform(session, action, target, value), "เรียกใช้ไม่สำเร็จ");
        plugin.getLogger().info("Web panel: " + (session == null ? "?" : session.name())
                + " ran " + action + (target == null ? "" : " on " + target));
        send(ex, 200, "application/json", "{\"ok\":true,\"message\":\"" + esc(result) + "\"}");
    }

    private String perform(Session session, String action, String target, String value) {
        String staff = (session == null ? "web" : session.name()) + " (เว็บ)";
        Player t = target == null ? null : Bukkit.getPlayerExact(target);

        switch (action) {
            case "announce" -> {
                if (value == null || value.isBlank()) return "ไม่มีข้อความ";
                Bukkit.broadcast(Notify.of("<gold>[ประกาศ] <white>" + value));
                return "ประกาศแล้ว";
            }
            case "backup" -> {
                plugin.backups().run(Bukkit.getConsoleSender(), true);
                return "เริ่มสำรองข้อมูลแล้ว";
            }
            case "reload" -> {
                plugin.readConfig();
                plugin.missions().loadDefinitions();
                plugin.menus().load();
                plugin.shop().load();
                plugin.ranks().load();
                plugin.crates().load();
                plugin.customItems().load();
                return "โหลดค่าใหม่แล้ว";
            }
            case "healAll" -> {
                for (Player p : Bukkit.getOnlinePlayers()) { p.setHealth(20); p.setFoodLevel(20); }
                return "รักษาทุกคนแล้ว";
            }
            case "time" -> {
                long ticks = switch (value == null ? "day" : value) {
                    case "night" -> 14000L;
                    case "noon" -> 6000L;
                    case "midnight" -> 18000L;
                    default -> 1000L;
                };
                for (World world : Bukkit.getWorlds()) world.setTime(ticks);
                return "ตั้งเวลาเป็น " + (value == null ? "day" : value) + " แล้ว";
            }
            case "weather" -> {
                boolean rain = "rain".equals(value);
                for (World world : Bukkit.getWorlds()) { world.setStorm(rain); world.setThundering(false); }
                return rain ? "ให้ฝนตกแล้ว" : "ฟ้าใสแล้ว";
            }
            default -> { }
        }

        if (target == null) return "ไม่ได้ระบุผู้เล่น";
        UUID id = t != null ? t.getUniqueId() : Bukkit.getOfflinePlayer(target).getUniqueId();
        String name = t != null ? t.getName() : target;

        switch (action) {
            case "kick" -> {
                if (t == null) return "คนนี้ไม่ได้ออนไลน์";
                plugin.punishments().punish(id, name, Punishments.Type.KICK,
                        value == null ? "ทีมงานเตะ" : value, staff, 0);
                return "เตะ " + name + " แล้ว";
            }
            case "ban" -> {
                long dur = Punishments.parseDuration(value == null ? "perm" : value);
                plugin.punishments().punish(id, name, Punishments.Type.BAN, "ทีมงานแบน", staff, Math.max(0, dur));
                return "แบน " + name + " แล้ว";
            }
            case "unban" -> {
                return plugin.punishments().lift(id, Punishments.Type.BAN)
                        ? "ปลดแบน " + name + " แล้ว" : "คนนี้ไม่ได้ถูกแบน";
            }
            case "mute" -> {
                long dur = Punishments.parseDuration(value == null ? "1h" : value);
                plugin.punishments().punish(id, name, Punishments.Type.MUTE, "ทีมงานปิดปาก", staff, Math.max(0, dur));
                return "ปิดปาก " + name + " แล้ว";
            }
            case "unmute" -> {
                return plugin.punishments().lift(id, Punishments.Type.MUTE)
                        ? "ปลดปิดปาก " + name + " แล้ว" : "คนนี้ไม่ได้ถูกปิดปาก";
            }
            case "warn" -> {
                plugin.punishments().punish(id, name, Punishments.Type.WARN,
                        value == null ? "ทีมงานเตือน" : value, staff, 0);
                return "เตือน " + name + " แล้ว";
            }
            case "heal" -> {
                if (t == null) return "คนนี้ไม่ได้ออนไลน์";
                t.setHealth(20);
                t.setFoodLevel(20);
                return "รักษา " + name + " แล้ว";
            }
            case "freeze" -> {
                if (!plugin.adminPanel().frozen().remove(id)) {
                    plugin.adminPanel().frozen().add(id);
                    if (t != null) Notify.bar(t, "<aqua>คุณถูกแช่แข็งโดยทีมงาน", "block.note_block.bass", 1f);
                    return "แช่แข็ง " + name + " แล้ว";
                }
                if (t != null) Notify.bar(t, "<green>ปลดแช่แข็งแล้ว", null, 1f);
                return "ปลดแช่แข็ง " + name + " แล้ว";
            }
            case "gamemode" -> {
                if (t == null) return "คนนี้ไม่ได้ออนไลน์";
                try { t.setGameMode(GameMode.valueOf(value == null ? "SURVIVAL" : value.toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException ex) { return "ไม่รู้จักโหมดนี้"; }
                return "เปลี่ยนโหมดของ " + name + " แล้ว";
            }
            case "money" -> {
                double amount;
                try { amount = Double.parseDouble(value == null ? "0" : value); }
                catch (NumberFormatException ex) { return "จำนวนเงินไม่ถูกต้อง"; }
                plugin.money().add(id, amount);
                plugin.money().save(false);
                return (amount >= 0 ? "ให้เงิน " : "หักเงิน ") + name + " แล้ว";
            }
            case "resetDungeon" -> {
                plugin.dungeons().clearCooldown(id);
                return "รีเซ็ตคูลดาวน์ของ " + name + " แล้ว";
            }
            default -> {
                return "ไม่รู้จักคำสั่งนี้";
            }
        }
    }
}
