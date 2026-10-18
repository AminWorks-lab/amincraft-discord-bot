package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-10-12 bot-promo：定时广告的纯逻辑（时间解析、下一个时间点、是否该发、广告卡片），不依赖 Bukkit，由 PromoTest 覆盖。
 *
 * 一个频道/子区一条规则：每天固定时间（times），或从每天 0 点起每 N 小时整点（everyHours）。
 * 「时间点」(slot) 用 epoch 毫秒表示；已发过的时间点记在 lastSlot，重启后也不会对同一个时间点再发一次。
 */
final class PromoRules {
    static final int MAX_TIMES = 12;
    static final int MAX_TEXT = 500;
    /** 2026-10-12 服主：只有服主能设置定时广告。allowed = promo.allowed-users；空 = 谁都不能设置（要先在配置里填 id）。 */
    static boolean mayManage(java.util.Set<String> allowed, String userId) {
        return allowed != null && userId != null && !userId.isBlank() && allowed.contains(userId.trim());
    }

    private PromoRules() {
    }

    /** 一条定时规则。times 非空 = 每天固定时间；否则 everyHours（1–24）。 */
    record Rule(List<LocalTime> times, int everyHours) {
        Rule {
            times = times == null ? List.of() : List.copyOf(times);
            if (times.isEmpty() && (everyHours < 1 || everyHours > 24)) throw new IllegalArgumentException("间隔要在 1 到 24 小时之间");
            if (!times.isEmpty()) everyHours = 0;
        }

        static Rule daily(List<LocalTime> times) {
            if (times == null || times.isEmpty()) throw new IllegalArgumentException("至少要有一个时间");
            return new Rule(times, 0);
        }

        static Rule every(int hours) {
            return new Rule(List.of(), hours);
        }

        /** 某一天里的所有时间点（升序）。 */
        List<LocalTime> slotsOfDay() {
            if (!times.isEmpty()) return times;
            List<LocalTime> out = new ArrayList<>();
            for (int h = 0; h < 24; h += everyHours) out.add(LocalTime.of(h, 0));
            return out;
        }

        String describe() {
            if (!times.isEmpty()) {
                List<String> s = new ArrayList<>();
                for (LocalTime t : times) s.add(fmt(t));
                return "每天 " + String.join("、", s);
            }
            return everyHours == 24 ? "每天 00:00" : "每 " + everyHours + " 小时整点（从 0 点算起）";
        }
    }

    /** 该不该发：POST = 发；SKIP = 这个时间点不发但记为已处理（离上一条广告太近 / 错过太久）；NONE = 没有新的时间点。 */
    enum Action { POST, SKIP, NONE }

    record Decision(Action action, long slot, String why) {
    }

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?:[:：](\\d{1,2})|点(?:(\\d{1,2})分?|半)?)?$");

    /** 解析「12:00,20:00」「8:30 20:00」「12点、20点半」。按时间排序去重；格式错误抛出带中文说明的 IllegalArgumentException。 */
    static List<LocalTime> parseTimes(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("没有填写时间，例如 12:00,20:00");
        TreeSet<LocalTime> out = new TreeSet<>();
        for (String part : s.trim().split("[,，、;；\\s]+")) {
            if (part.isEmpty()) continue;
            Matcher m = TIME.matcher(part);
            if (!m.matches()) throw new IllegalArgumentException("看不懂时间「" + part + "」，请写成 12:00 或 20:30，多个用逗号隔开");
            int h = Integer.parseInt(m.group(1));
            int min = 0;
            if (m.group(2) != null) min = Integer.parseInt(m.group(2));
            else if (m.group(3) != null) min = Integer.parseInt(m.group(3));
            else if (part.endsWith("半")) min = 30;
            if (h == 24 && min == 0) h = 0;
            if (h > 23 || min > 59) throw new IllegalArgumentException("时间「" + part + "」超出范围（00:00–23:59）");
            out.add(LocalTime.of(h, min));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("没有填写时间，例如 12:00,20:00");
        if (out.size() > MAX_TIMES) throw new IllegalArgumentException("每天最多 " + MAX_TIMES + " 个时间");
        return new ArrayList<>(out);
    }

    /** 最近一个已到的时间点（≤ now）。 */
    static long latestSlot(Rule r, Instant now, ZoneId zone) {
        ZonedDateTime z = now.atZone(zone);
        LocalDate day = z.toLocalDate();
        for (int back = 0; back < 3; back++) {
            LocalDate d = day.minusDays(back);
            List<LocalTime> slots = r.slotsOfDay();
            for (int i = slots.size() - 1; i >= 0; i--) {
                long t = d.atTime(slots.get(i)).atZone(zone).toInstant().toEpochMilli();
                if (t <= now.toEpochMilli()) return t;
            }
        }
        return Long.MIN_VALUE; // 不会发生（每天至少一个时间点）
    }

    /** 下一个还没到的时间点（> now）。 */
    static long nextSlot(Rule r, Instant now, ZoneId zone) {
        LocalDate day = now.atZone(zone).toLocalDate();
        for (int fwd = 0; fwd < 3; fwd++) {
            LocalDate d = day.plusDays(fwd);
            for (LocalTime t : r.slotsOfDay()) {
                long ms = d.atTime(t).atZone(zone).toInstant().toEpochMilli();
                if (ms > now.toEpochMilli()) return ms;
            }
        }
        return Long.MAX_VALUE;
    }

    /**
     * @param lastSlot     上次已处理（发过或跳过）的时间点
     * @param lastPostedAt 上次在这个频道真正发出广告的时间（含预览），0 = 没发过
     * @param minGap       离上一条广告不到这么久就跳过这个时间点
     * @param grace        时间点过去超过这么久（例如关服期间错过）就不再补发
     */
    static Decision decide(Rule r, Instant now, ZoneId zone, long lastSlot, long lastPostedAt, Duration minGap, Duration grace) {
        long slot = latestSlot(r, now, zone);
        if (slot <= lastSlot) return new Decision(Action.NONE, slot, "");
        long n = now.toEpochMilli();
        if (n - slot > grace.toMillis()) return new Decision(Action.SKIP, slot, "错过太久（" + (n - slot) / 60000 + " 分钟）");
        if (lastPostedAt > 0 && n - lastPostedAt < minGap.toMillis()) return new Decision(Action.SKIP, slot, "离上一条广告不到 " + minGap.toMinutes() + " 分钟");
        return new Decision(Action.POST, slot, "");
    }

    /** 新建/修改规则时的起点：当前已经过去的时间点算作已处理，不会一设置就立刻补发。 */
    static long initialLastSlot(Rule r, Instant now, ZoneId zone) {
        return latestSlot(r, now, zone);
    }

    /** 自定义文字：去掉首尾空白，空 = null（用默认卡片），太长抛异常。 */
    static String cleanText(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.isEmpty()) return null;
        if (t.codePointCount(0, t.length()) > MAX_TEXT) throw new IllegalArgumentException("文字最多 " + MAX_TEXT + " 个字");
        return t;
    }

    static String fmt(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    static String fmtSlot(long ms, ZoneId zone) {
        return Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    // =============== 广告卡片 ===============

    /** 卡片里可配置的内容（config.yml 的 promo:）。 */
    record Card(String title, String intro, String address, List<String> modes, String modpackUrl, String modpackForum, String footer, int color) {
    }

    /**
     * 组装广告嵌入。customText 不为空时替换开头介绍；地址、在线人数、玩法、整合包照常显示。
     * online &lt; 0 = 取不到在线人数（不显示这一栏）。
     */
    static JsonObject embed(Card c, String customText, int online, int max, Instant now) {
        JsonObject e = new JsonObject();
        e.addProperty("title", BotRules.truncate(c.title(), 256));
        String desc = customText != null ? customText : c.intro();
        if (desc != null && !desc.isBlank()) e.addProperty("description", BotRules.truncate(desc, 4000));
        e.addProperty("color", c.color());
        JsonArray fields = new JsonArray();
        if (c.address() != null && !c.address().isBlank()) fields.add(field("服务器地址", "`" + c.address().replace("`", "") + "`", true));
        if (online >= 0) fields.add(field("当前在线", online + (max > 0 ? " / " + max : "") + " 人", true));
        if (c.modes() != null && !c.modes().isEmpty()) fields.add(field("可以玩", BotRules.truncate(String.join("、", c.modes()), 1000), false));
        String pack = modpackLine(c);
        if (pack != null) fields.add(field("整合包 / 助手模组", pack, false));
        if (!fields.isEmpty()) e.add("fields", fields);
        if (c.footer() != null && !c.footer().isBlank()) {
            JsonObject f = new JsonObject();
            f.addProperty("text", BotRules.truncate(c.footer(), 2000));
            e.add("footer", f);
        }
        e.addProperty("timestamp", now.toString());
        return e;
    }

    private static String modpackLine(Card c) {
        boolean url = c.modpackUrl() != null && c.modpackUrl().startsWith("http");
        boolean forum = c.modpackForum() != null && !c.modpackForum().isBlank();
        if (url && forum) return "[点这里下载](" + c.modpackUrl() + ") · 说明见 <#" + c.modpackForum() + ">";
        if (url) return "[点这里下载](" + c.modpackUrl() + ")";
        if (forum) return "到 <#" + c.modpackForum() + "> 下载";
        return null;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }
}
