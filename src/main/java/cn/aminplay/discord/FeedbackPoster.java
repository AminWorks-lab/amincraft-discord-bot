package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 2026-10-17 游戏内一键反馈：把 MiniGameHub {@code cn.friendhub.feedback.FeedbackApi} 的事件发到 channels.feedback（留空 = 关）。
 * 文字频道 / 公告频道 / 子区：发一条嵌入卡片；论坛频道：开新帖（论坛要求标签时自动用第一个，与 {@link DraftPoster} 一致）。
 * 有截图时作为附件随第一条消息发出，嵌入里直接显示（attachment://）。allowed_mentions 不 @ 任何人（玩家主动提交的内容，原样转发）。
 * worker 线程调用。
 */
final class FeedbackPoster {
    static final int COLOR = 0x4FA3FF;
    static final int MAX_DESCRIPTION = 4000, MAX_FIELD = 1000, MAX_THREAD_NAME = 100;

    private final DiscordRest rest;
    private final Supplier<BotConfig> cfg;
    private final Logger log;
    private volatile boolean warnedNoChannel;

    FeedbackPoster(DiscordRest rest, Supplier<BotConfig> cfg, Logger log) {
        this.rest = rest; this.cfg = cfg; this.log = log;
    }

    /** 结果：频道 id + 消息/帖子 id；channel 未设置 = null。 */
    record Sent(String channel, String id, boolean forum, boolean withShot) { }

    Sent post(Map<String, Object> e) throws IOException {
        BotConfig c = cfg.get();
        if (c.feedbackChannel.isEmpty()) {
            if (!warnedNoChannel) { warnedNoChannel = true; log.info("收到游戏内反馈，但没有设置 channels.feedback（留空 = 不转发；文件里仍有记录）"); }
            return null;
        }
        Path shot = shotFile(e.get("shot"), DiscordRest.uploadLimitForTier(0));
        String fileName = shot == null ? null : "feedback-" + str(e.get("id")).replaceAll("[^0-9a-z-]", "") + ext(shot);
        JsonObject msg = message(e, fileName, c.serverName);
        List<Path> files = shot == null ? List.of() : List.of(Files.copy(shot, tempNamed(fileName)));
        try {
            JsonObject ch = rest.get("/channels/" + c.feedbackChannel).getAsJsonObject();
            int type = ch.has("type") ? ch.get("type").getAsInt() : 0;
            if (type == 15 || type == 16) {
                JsonObject body = new JsonObject();
                body.addProperty("name", threadName(e));
                body.addProperty("auto_archive_duration", 10080);
                body.add("message", msg);
                boolean requireTag = ch.has("flags") && (ch.get("flags").getAsInt() & 16) != 0;
                String tag = requireTag ? ReplayPoster.pickTag(ch, "") : null;
                if (tag != null) { JsonArray a = new JsonArray(); a.add(tag); body.add("applied_tags", a); }
                JsonElement r = files.isEmpty() ? rest.post("/channels/" + c.feedbackChannel + "/threads", body)
                    : rest.postFiles("/channels/" + c.feedbackChannel + "/threads", body, files, "message");
                return new Sent(c.feedbackChannel, r.getAsJsonObject().get("id").getAsString(), true, !files.isEmpty());
            }
            JsonElement r = files.isEmpty() ? rest.post("/channels/" + c.feedbackChannel + "/messages", msg)
                : rest.postFiles("/channels/" + c.feedbackChannel + "/messages", msg, files);
            return new Sent(c.feedbackChannel, r.getAsJsonObject().get("id").getAsString(), false, !files.isEmpty());
        } finally {
            for (Path f : files) { try { Files.deleteIfExists(f); Files.deleteIfExists(f.getParent()); } catch (IOException ignored) { } }
        }
    }

    /** 附件用固定的英文文件名（原文件不动，复制到临时目录）。 */
    private static Path tempNamed(String name) throws IOException {
        Path dir = Files.createTempDirectory("amincraft-feedback-");
        return dir.resolve(name);
    }

    /** MiniGameHub 给的截图路径：必须是普通文件、扩展名 png/jpg、大小在一条消息的上限内；否则不带截图。 */
    static Path shotFile(Object path, long limit) {
        if (!(path instanceof String s) || s.isBlank()) return null;
        try {
            Path p = Path.of(s);
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(n.endsWith(".png") || n.endsWith(".jpg"))) return null;
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null;
            long size = Files.size(p);
            return size > 0 && size <= limit ? p : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String ext(Path p) { return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg") ? ".jpg" : ".png"; }

    /** 消息体：一张嵌入卡片，不 @ 任何人。 */
    static JsonObject message(Map<String, Object> e, String attachmentName, String serverName) {
        JsonObject m = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed(e, attachmentName, serverName));
        m.add("embeds", embeds);
        m.add("allowed_mentions", Interactions.noMentions());
        return m;
    }

    static JsonObject embed(Map<String, Object> e, String attachmentName, String serverName) {
        JsonObject o = new JsonObject();
        o.addProperty("title", BotRules.truncate("📝 玩家反馈 · " + str(e.get("name")), 256));
        String text = str(e.get("text"));
        o.addProperty("description", BotRules.truncate(text.isEmpty() ? "（空）" : text, MAX_DESCRIPTION));
        o.addProperty("color", COLOR);
        JsonArray f = new JsonArray();
        boolean learner = Boolean.TRUE.equals(e.get("learner"));
        f.add(field("玩家", str(e.get("name")) + (learner ? "（学习版玩家）" : "（正版）"), true));
        f.add(field("UUID", "`" + str(e.get("uuid")) + "`", true));
        f.add(field("位置", or(str(e.get("place")), "?"), true));
        f.add(field("坐标", str(e.get("world")) + " " + num(e.get("x")) + ", " + num(e.get("y")) + ", " + num(e.get("z")), true));
        f.add(field("在线", num(e.get("online")) + (e.get("maxPlayers") instanceof Number mx ? " / " + mx.intValue() : ""), true));
        Object comp = e.get("companion");
        f.add(field("助手", comp == null ? "未安装" : str(comp), true));
        f.add(field("服务器时间", or(str(e.get("time")), "?"), false));
        String shot = shotText(str(e.get("shotStatus")), attachmentName != null);
        if (shot != null) f.add(field("截图", shot, true));
        o.add("fields", f);
        if (attachmentName != null) {
            JsonObject img = new JsonObject();
            img.addProperty("url", "attachment://" + attachmentName);
            o.add("image", img);
        }
        JsonObject footer = new JsonObject();
        footer.addProperty("text", BotRules.truncate((serverName == null || serverName.isEmpty() ? "" : serverName + " · ") + "反馈编号 " + str(e.get("id")), 2048));
        o.add("footer", footer);
        Object ms = e.get("timeMs");
        if (ms instanceof Number n && n.longValue() > 0) o.addProperty("timestamp", java.time.Instant.ofEpochMilli(n.longValue()).toString());
        return o;
    }

    /** 截图一栏：带图 = 附件；其它按 MiniGameHub 的状态说明；没装助手（none）不显示这一栏。 */
    static String shotText(String status, boolean attached) {
        if (attached) return "见下图";
        return switch (status) {
            case "ok" -> "已上传，但文件过大或读取失败，没有附上";
            case "timeout" -> "客户端没有在时限内上传";
            case "failed" -> "上传的文件无效";
            case "unsupported" -> "助手版本不支持截图";
            case "off" -> "截图功能未开启";
            case "shutdown" -> "服务器关闭，没等到截图";
            default -> null;
        };
    }

    /** 论坛帖标题：「反馈 · 玩家 · 内容开头」，最多 100 字。 */
    static String threadName(Map<String, Object> e) {
        String t = str(e.get("text")).replace('\n', ' ');
        return BotRules.truncate("反馈 · " + str(e.get("name")) + " · " + (t.isEmpty() ? str(e.get("id")) : t), MAX_THREAD_NAME);
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", BotRules.truncate(value.isEmpty() ? "-" : value, MAX_FIELD));
        o.addProperty("inline", inline);
        return o;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String or(String s, String d) { return s.isEmpty() ? d : s; }

    private static String num(Object o) {
        if (!(o instanceof Number n)) return "?";
        double d = n.doubleValue();
        return d == Math.rint(d) && Math.abs(d) < 1e12 ? String.valueOf((long) d) : String.valueOf(Math.round(d * 10) / 10.0);
    }
}
