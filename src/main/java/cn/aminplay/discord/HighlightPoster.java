package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 2026-10-17 对局精彩回放：把 MiniGameHub {@code cn.friendhub.replay.HighlightApi} 的精彩片段发到 channels.highlights（留空 = 关）。
 * 文字频道 / 公告频道 / 子区：发一张嵌入卡片；论坛频道：开新帖（论坛要求标签时自动用第一个，与 {@link FeedbackPoster} 一致）。
 * 不 @ 任何人（allowed_mentions 为空，正文里也不写 &lt;@id&gt;）。
 * 隐私：本局真人里绑定了 Discord 且用 /minmin 隐私 退出的，先告诉 MiniGameHub（网页回放里换名），卡片里主角名换成「某位玩家」。
 * worker 线程调用。
 */
final class HighlightPoster {
    static final int COLOR = 0xFFB020;
    static final String HIDDEN = "某位玩家";
    static final int MAX_THREAD_NAME = 100;

    /** MiniGameHub 一侧（{@link HubHighlights}；测试里用假的）。 */
    interface Hub {
        boolean hide(String highlightId, List<String> uuids) throws Exception;
    }

    private final DiscordRest rest;
    private final Supplier<BotConfig> cfg;
    private final Predicate<String> optedOut;
    private final Supplier<Hub> hub;
    private final Logger log;
    private volatile boolean warnedNoChannel;

    HighlightPoster(DiscordRest rest, Supplier<BotConfig> cfg, Predicate<String> optedOut, Supplier<Hub> hub, Logger log) {
        this.rest = rest; this.cfg = cfg; this.optedOut = optedOut; this.hub = hub; this.log = log;
    }

    /** 结果：频道 id + 消息/帖子 id；channel 未设置 = null。 */
    record Sent(String channel, String id, boolean forum, boolean anonymous) { }

    Sent post(Map<String, Object> e) throws IOException {
        BotConfig c = cfg.get();
        if (c.highlightsChannel.isEmpty()) {
            if (!warnedNoChannel) { warnedNoChannel = true; log.info("收到精彩片段，但没有设置 channels.highlights（留空 = 不发）"); }
            return null;
        }
        List<String> hidden = hiddenUuids(e, optedOut);
        boolean anonymous = hidden.contains(str(e.get("playerUuid"))) || optedOut.test(str(e.get("playerDiscord")));
        String url = str(e.get("url"));
        if (!hidden.isEmpty() && !url.isEmpty()) {
            boolean ok = false;
            try {
                Hub h = hub.get();
                ok = h != null && h.hide(str(e.get("id")), hidden);
            } catch (Exception ex) {
                log.warning("精彩片段隐私换名失败（" + e.get("id") + "）：" + ex.getMessage());
            }
            if (!ok) url = "";   // 没能在网页里换名：这条就不带网页链接
        }
        JsonObject msg = message(e, anonymous, url, c.serverName);
        JsonObject ch = rest.get("/channels/" + c.highlightsChannel).getAsJsonObject();
        int type = ch.has("type") ? ch.get("type").getAsInt() : 0;
        if (type == 15 || type == 16) {
            JsonObject body = new JsonObject();
            body.addProperty("name", BotRules.truncate(title(e, anonymous), MAX_THREAD_NAME));
            body.addProperty("auto_archive_duration", 4320);
            body.add("message", msg);
            boolean requireTag = ch.has("flags") && (ch.get("flags").getAsInt() & 16) != 0;
            String tag = requireTag ? ReplayPoster.pickTag(ch, "") : null;
            if (tag != null) { JsonArray a = new JsonArray(); a.add(tag); body.add("applied_tags", a); }
            JsonElement r = rest.post("/channels/" + c.highlightsChannel + "/threads", body);
            return new Sent(c.highlightsChannel, r.getAsJsonObject().get("id").getAsString(), true, anonymous);
        }
        JsonElement r = rest.post("/channels/" + c.highlightsChannel + "/messages", msg);
        return new Sent(c.highlightsChannel, r.getAsJsonObject().get("id").getAsString(), false, anonymous);
    }

    /** 本局真人里，绑定了 Discord 并退出隐私的人的 UUID（去重）。 */
    static List<String> hiddenUuids(Map<String, Object> e, Predicate<String> optedOut) {
        Set<String> out = new LinkedHashSet<>();
        if (e.get("people") instanceof List<?> ps) for (Object o : ps) {
            if (!(o instanceof Map<?, ?> p)) continue;
            String d = str(p.get("discordId")), u = str(p.get("uuid"));
            if (!d.isEmpty() && !u.isEmpty() && optedOut.test(d)) out.add(u);
        }
        String d = str(e.get("playerDiscord")), u = str(e.get("playerUuid"));
        if (!d.isEmpty() && !u.isEmpty() && optedOut.test(d)) out.add(u);
        return new ArrayList<>(out);
    }

    /** 标题：MiniGameHub 给的模板，{player} 换成主角名（退出隐私 = 某位玩家）；没有模板就按类型拼。 */
    static String title(Map<String, Object> e, boolean anonymous) {
        String name = anonymous ? HIDDEN : str(e.get("player"));
        if (name.isEmpty()) name = HIDDEN;
        String tpl = str(e.get("titleTemplate"));
        if (tpl.isEmpty()) {
            String t = str(e.get("title"));
            if (!t.isEmpty() && !anonymous) return t;
            String hero = str(e.get("hero"));
            return "精彩时刻！" + name + (hero.isEmpty() ? "" : "（" + hero + "）");
        }
        return tpl.replace("{player}", name);
    }

    /** 消息体：一张嵌入卡片，不 @ 任何人。 */
    static JsonObject message(Map<String, Object> e, boolean anonymous, String url, String serverName) {
        JsonObject m = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed(e, anonymous, url, serverName));
        m.add("embeds", embeds);
        m.add("allowed_mentions", Interactions.noMentions());
        return m;
    }

    static JsonObject embed(Map<String, Object> e, boolean anonymous, String url, String serverName) {
        JsonObject o = new JsonObject();
        o.addProperty("title", BotRules.truncate("🎬 " + title(e, anonymous), 256));
        o.addProperty("color", COLOR);
        if (url != null && !url.isEmpty()) o.addProperty("url", url);
        String game = str(e.get("gameName")).isEmpty() ? str(e.get("game")) : str(e.get("gameName"));
        StringBuilder d = new StringBuilder(game);
        if (!str(e.get("map")).isEmpty()) d.append(" · ").append(str(e.get("map")));
        o.addProperty("description", BotRules.truncate(d.toString(), 4000));
        JsonArray f = new JsonArray();
        if (e.get("matchStart") instanceof Number ms && ms.longValue() > 0) f.add(field("对局时间", "<t:" + ms.longValue() / 1000 + ":f>", true));
        if (e.get("at") instanceof Number at) f.add(field("时刻", "第 " + clock(at.longValue()) + (e.get("clipSeconds") instanceof Number cs ? "（片段 " + cs.longValue() + " 秒）" : ""), true));
        if (!str(e.get("hero")).isEmpty()) f.add(field("英雄", str(e.get("hero")), true));
        if (url != null && !url.isEmpty()) {
            String exp = e.get("urlExpires") instanceof Number x && x.longValue() > 0 ? "，<t:" + x.longValue() / 1000 + ":R>失效" : "";
            f.add(field("网页回放", "[打开俯视回放](" + url + ")" + exp, false));
        }
        f.add(field("游戏内观看", "在大厅输入 `/精彩`", false));
        o.add("fields", f);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", BotRules.truncate((serverName == null || serverName.isEmpty() ? "" : serverName + " · ") + "精彩回放 · " + str(e.get("id")), 2048));
        o.add("footer", footer);
        return o;
    }

    static String clock(long seconds) { long s = Math.max(0, seconds); return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60; }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", BotRules.truncate(value.isEmpty() ? "-" : value, 1000));
        o.addProperty("inline", inline);
        return o;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
