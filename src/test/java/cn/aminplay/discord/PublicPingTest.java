package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;

/** 2026-10-18 一般成员用 /minmin 通知 @ 开放的身份组 / 通知组（例如「缺人找我」）。 */
public final class PublicPingTest {
    private static int passed, failed;
    private static final String HERE = "111111111111111111", OTHER = "222222222222222222";
    private static final String U1 = "301", U2 = "302";

    public static void main(String[] args) {
        BotConfig none = cfg(List.of()), open = cfg(List.of("lfg"));
        BotConfig.PanelRole mc = open.entry("mc"), lfg = open.entry("lfg"), upd = open.entry("upd");

        check(none.pingPublic.isEmpty(), "ping.public 默认空");
        check(none.pingUserCooldownSeconds == 600 && none.pingGroupCooldownSeconds == 300 && none.pingMaxLength == 200, "默认冷却 600/300 秒、200 字");
        check(PingRules.publicEntries(open).size() == 1 && PingRules.publicEntries(open).get(0).key().equals("lfg"), "只列出开放的项目");

        PingRules r = new PingRules();
        long t = 1_000_000L;
        check(r.refusal(none, lfg, U1, null, HERE, t) != null, "没开放任何项目时一般成员被拒");
        check(r.refusal(open, mc, U1, null, HERE, t) != null, "全员身份组（没开放）被拒");
        check(r.refusal(open, upd, U1, null, HERE, t).contains("缺人找我"), "被拒时告诉能 @ 哪些");
        check(r.refusal(open, null, U1, null, HERE, t) != null, "乱填的项目被拒");
        check(r.refusal(open, lfg, U1, OTHER, HERE, t) != null, "一般成员不能发到别的频道");
        check(r.refusal(open, lfg, U1, HERE, HERE, t) == null, "填了当前频道也可以");
        check(r.refusal(open, lfg, U1, null, HERE, t) == null, "开放的项目可以 @");

        r.record(U1, "lfg", t);
        check(r.refusal(open, lfg, U1, null, HERE, t + 1000) != null, "同一个人冷却中被拒");
        check(r.refusal(open, lfg, U2, null, HERE, t + 1000) != null, "同一个项目冷却中别人也被拒");
        check(r.refusal(open, lfg, U2, null, HERE, t + 300_000) == null, "项目冷却 5 分钟后别人可以");
        check(r.refusal(open, lfg, U1, null, HERE, t + 300_000) != null, "本人冷却 10 分钟还没到");
        check(r.refusal(open, lfg, U1, null, HERE, t + 300_000).contains("5 分钟"), "提示剩余时间（分钟）");
        check(r.refusal(open, lfg, U1, null, HERE, t + 600_000) == null, "10 分钟后本人可以");
        check(r.refusal(open, lfg, U1, null, HERE, t + 570_000).contains("30 秒"), "不到一分钟时按秒提示");

        BotConfig noCd = cfg(List.of("lfg"), 0, 0);
        PingRules r2 = new PingRules();
        r2.record(U1, "lfg", t);
        check(r2.refusal(noCd, lfg, U1, null, HERE, t) == null, "冷却设 0 = 不限");

        String tail = PingRules.tail(U1, "起床战争\n3 缺 1 @everyone", 200);
        check(tail.contains("<@" + U1 + ">") && !tail.contains("\n"), "标明是谁呼叫的、去掉换行");
        check(PingRules.tail(U1, null, 200).equals(" — <@" + U1 + "> 呼叫"), "没写内容也标明呼叫人");
        check(PingRules.tail(U1, "x".repeat(500), 10).length() < 40, "内容按 max-length 截断");
        // @everyone 文字不会生效：allowed_mentions 只放开这一个身份组
        JsonObject allowed = Notifier.role("999").allowed();
        check(allowed.getAsJsonArray("parse").isEmpty() && allowed.getAsJsonArray("roles").size() == 1, "allowed_mentions 只允许这一个身份组");

        JsonArray subs = Interactions.definitions(true).get(0).getAsJsonObject().getAsJsonArray("options");
        JsonObject ping = null;
        for (var e : subs) if (e.getAsJsonObject().get("name").getAsString().equals("ping")) ping = e.getAsJsonObject();
        check(ping != null && !ping.has("default_member_permissions"), "/minmin 通知 对所有人可见");
        check(subs.size() <= 25, "子指令不超过 Discord 上限 25：" + subs.size());

        System.out.println("PublicPingTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static BotConfig cfg(List<String> open) {
        return cfg(open, -1, -1);
    }

    private static BotConfig cfg(List<String> open, int userCd, int groupCd) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("discord.guild-id", "900");
        c.set("roles.panel", List.of(
                Map.of("key", "mc", "name", "Min-craft玩家", "type", "role", "id", "999"),
                Map.of("key", "lfg", "name", "缺人找我", "type", "subscribe"),
                Map.of("key", "upd", "name", "更新公告", "type", "subscribe")));
        c.set("ping.public", open);
        if (userCd >= 0) c.set("ping.user-cooldown-seconds", userCd);
        if (groupCd >= 0) c.set("ping.group-cooldown-seconds", groupCd);
        return BotConfig.load(c);
    }

    private static void check(boolean condition, String name) {
        if (condition) passed++;
        else { failed++; System.out.println("FAIL " + name); }
    }
}
