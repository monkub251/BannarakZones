package com.bannarak.zones;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * One place to see what you can type. Staff commands are only printed to
 * someone who actually holds the permission, so an ordinary player never
 * learns the moderation commands exist.
 */
public class HelpCommand implements CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private record Line(String command, String what, String permission) { }

    private static final List<Line> PLAYER = List.of(
            new Line("/menu",    "เมนูรวมทุกอย่าง",            "zones.command.menu"),
            new Line("/zone",    "จัดการเขตป้องกันของคุณ",      "zones.command.zone"),
            new Line("/money",   "ดูเงินของคุณ",                "zones.command.money"),
            new Line("/pay",     "โอนเงินให้เพื่อน",            "zones.command.pay"),
            new Line("/baltop",  "อันดับคนรวย",                 "zones.command.baltop"),
            new Line("/shop",    "ร้านค้าของเซิร์ฟ",            "zones.command.shop"),
            new Line("/market",  "ตลาดกลาง ซื้อขายกับผู้เล่น",  "zones.command.market"),
            new Line("/sell",    "ลงขายของในมือ",               "zones.command.sell"),
            new Line("/job",     "เลือกอาชีพและดูเลเวล",        "zones.command.job"),
            new Line("/mission", "ภารกิจและรางวัล",             "zones.command.mission"),
            new Line("/daily",   "รับรางวัลประจำวัน",           "zones.command.daily"),
            new Line("/crate",   "เปิดกล่องสุ่ม",               "zones.command.crate"),
            new Line("/rank",    "ดูยศและเลื่อนขั้น",           "zones.command.rank"),
            new Line("/dungeon", "เข้าดันเจียน 50 เวฟ",         "zones.command.dungeon"),
            new Line("/spawn",   "กลับจุดเกิด",                 "zones.command.spawn"),
            new Line("/home",    "ไปบ้านของคุณ",                "zones.command.home"),
            new Line("/sethome", "ตั้งบ้านตรงที่ยืนอยู่",       "zones.command.sethome"),
            new Line("/warp",    "ไปจุดวาร์ปของเซิร์ฟ",         "zones.command.warp"),
            new Line("/tpa",     "ขอวาร์ปไปหาเพื่อน",           "zones.command.tpa"),
            new Line("/rtp",     "สุ่มออกไปสำรวจ",              "zones.command.rtp"),
            new Line("/msg",     "ส่งข้อความส่วนตัว",           "zones.command.msg"),
            new Line("/r",       "ตอบข้อความล่าสุด",            "zones.command.r"),
            new Line("/ignore",  "บล็อกคนที่ไม่อยากคุยด้วย",    "zones.command.ignore")
    );

    private static final List<Line> STAFF = List.of(
            new Line("/admin",     "เปิดแผงควบคุม (/admin web = รหัสเข้าหน้าเว็บ)", "zones.admin.admin"),
            new Line("/adminhud",  "เปิด-ปิดแผงข้างจอ",          "zones.admin.adminhud"),
            new Line("/ban",       "แบนผู้เล่น",                 "zones.admin.ban"),
            new Line("/unban",     "ปลดแบน",                     "zones.admin.unban"),
            new Line("/mute",      "ปิดปาก",                     "zones.admin.mute"),
            new Line("/unmute",    "ปลดปิดปาก",                  "zones.admin.unmute"),
            new Line("/kick",      "เตะออกจากเซิร์ฟ",            "zones.admin.kick"),
            new Line("/warn",      "เตือน",                      "zones.admin.warn"),
            new Line("/history",   "ดูประวัติการลงโทษ",          "zones.admin.history"),
            new Line("/chat",      "ล้าง / ล็อกแชต / สอดแชต",    "zones.admin.chat"),
            new Line("/world",     "ดูโลก ย้ายโลก ตั้งขอบโลก",   "zones.admin.world"),
            new Line("/eco",       "เพิ่ม-ลด-ตั้งเงินของคนอื่น", "zones.admin.eco"),
            new Line("/citem",     "แจกไอเทมพิเศษของเซิร์ฟ",     "zones.admin.citem"),
            new Line("/backup",    "สำรองข้อมูลเดี๋ยวนี้",       "zones.admin.backup"),
            new Line("/vanish",    "ล่องหน คนอื่นมองไม่เห็น",     "zones.admin.vanish"),
            new Line("/god",       "อมตะ",                       "zones.admin.god"),
            new Line("/speed",     "ตั้งความเร็ว 0-10",          "zones.admin.speed"),
            new Line("/nv",        "มองกลางคืน",                 "zones.admin.nv"),
            new Line("/invsee",    "ดูกระเป๋าคนอื่น",            "zones.admin.invsee"),
            new Line("/ec",        "ดูหีบเอนเดอร์คนอื่น",        "zones.admin.invsee"),
            new Line("/head",      "ใส่หัวผู้เล่น",              "zones.admin.head"),
            new Line("/mining",    "สถิติการขุด จับ x-ray",      "zones.admin.mining"),
            new Line("/npc",       "สร้างคนยืนให้คลิกที่สปอว์น",  "zones.admin.npc"),
            new Line("/setspawn",  "จุดเกิด / เข้าครั้งแรก / หลังตาย", "zones.admin.setspawn"),
            new Line("/setwarp",   "ตั้งจุดวาร์ป",               "zones.admin.setwarp"),
            new Line("/delwarp",   "ลบจุดวาร์ป",                 "zones.admin.delwarp")
    );

    private static Component row(Line l) {
        return MM.deserialize("<dark_gray>› <gold>" + l.command() + " <dark_gray>— <gray>" + l.what())
                .clickEvent(ClickEvent.suggestCommand(l.command() + " "))
                .hoverEvent(HoverEvent.showText(
                        MM.deserialize("<yellow>กดเพื่อพิมพ์ " + l.command())));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        boolean staffSection = false;
        for (Line l : STAFF) if (sender.hasPermission(l.permission())) { staffSection = true; break; }

        sender.sendMessage(MM.deserialize("<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));
        sender.sendMessage(MM.deserialize(
                "   <gradient:#f0cf8a:#b8863a><bold>BANNARAKX</bold></gradient> <dark_gray>· <gray>คำสั่งทั้งหมด"));
        sender.sendMessage(Component.empty());

        sender.sendMessage(MM.deserialize("<yellow><bold>ของผู้เล่น</bold>"));
        for (Line l : PLAYER) if (sender.hasPermission(l.permission())) sender.sendMessage(row(l));

        if (staffSection) {
            sender.sendMessage(Component.empty());
            sender.sendMessage(MM.deserialize("<red><bold>ของทีมงาน</bold> <dark_gray>(เฉพาะคุณเห็น)"));
            for (Line l : STAFF) if (sender.hasPermission(l.permission())) sender.sendMessage(row(l));
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(MM.deserialize("<dark_gray>กดที่คำสั่งเพื่อพิมพ์ลงช่องแชตได้เลย"));
        sender.sendMessage(MM.deserialize("<dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"));

        if (sender instanceof Player p)
            p.playSound(p.getLocation(), "ui.button.click", 0.6f, 1.3f);
        return true;
    }
}
