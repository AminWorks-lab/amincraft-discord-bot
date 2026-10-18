package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * 2026-10-17 游戏内一键反馈：MiniGameHub FeedbackApi 事件 → channels.feedback。用本地假 Discord（不连真的 Discord），测
 * 嵌入卡片内容、论坛开帖（必选标签自动用第一个）、文字频道发消息、截图附件（multipart + attachment://）、不 @ 任何人、
 * 频道留空不发、截图路径检查（扩展名 / 不存在 / 过大 / 符号链接）。
 * 运行：java -cp classes:test-classes:<paper deps> cn.aminplay.discord.FeedbackPosterTest
 */
public final class FeedbackPosterTest {
    private static int passed, failed;

    static final String TEXT = "200000000000000010", FORUM = "200000000000000020", FORUM_FREE = "200000000000000030";

    record Call(String method, String path, byte[] body, String contentType) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
        boolean multipart() { return contentType != null && contentType.startsWith("multipart/"); }
        JsonObject payload() {
            String t = text();
            if (multipart()) {
                int a = t.indexOf("\r\n\r\n") + 4;
                int b = t.indexOf("\r\n--", a);
                return JsonParser.parseString(t.substring(a, b)).getAsJsonObject();
            }
            return JsonParser.parseString(t).getAsJsonObject();
        }
    }

    static final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body, ex.getRequestHeaders().getFirst("Content-Type"));
            calls.add(c);
            String k = c.method + " " + c.path, resp = "{}";
            int status = 200;
            if (k.equals("GET /channels/" + TEXT)) resp = "{\"id\":\"" + TEXT + "\",\"type\":0,\"name\":\"反馈\"}";
            else if (k.equals("GET /channels/" + FORUM)) resp = "{\"id\":\"" + FORUM + "\",\"type\":15,\"flags\":16,\"name\":\"反馈论坛\",\"available_tags\":[{\"id\":\"t1\",\"name\":\"反馈\"},{\"id\":\"t2\",\"name\":\"bug\"}]}";
            else if (k.equals("GET /channels/" + FORUM_FREE)) resp = "{\"id\":\"" + FORUM_FREE + "\",\"type\":15,\"flags\":0,\"name\":\"反馈论坛2\",\"available_tags\":[{\"id\":\"t9\",\"name\":\"x\"}]}";
            else if (c.method.equals("POST") && c.path.endsWith("/threads")) resp = "{\"id\":\"th1\",\"type\":11}";
            else if (c.method.equals("POST") && c.path.endsWith("/messages")) resp = "{\"id\":\"m1\"}";
            else { status = 404; resp = "{\"code\":10003,\"message\":\"Unknown Channel\"}"; }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            embeds();
            shotFiles();
            run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
        System.out.println("FeedbackPosterTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Map<String, Object> event(String text, Object companion, boolean learner, String shot, String shotStatus) {
        Map<String, Object> e = new HashMap<>();
        e.put("type", "feedback");
        e.put("id", "20261017-abc123");
        e.put("time", "2026-10-17T20:15:00+09:00");
        e.put("timeMs", 1_792_000_000_000L);
        e.put("name", "Alice");
        e.put("uuid", "00000000-0000-3000-8000-000000000001");
        e.put("learner", learner);
        e.put("world", "amincity");
        e.put("x", 12.3);
        e.put("y", 64.0);
        e.put("z", -7.1);
        e.put("place", "类脑市地块 A1");
        e.put("online", 5);
        e.put("maxPlayers", 40);
        e.put("companion", companion);
        e.put("text", text);
        e.put("shot", shot);
        e.put("shotStatus", shotStatus);
        return e;
    }

    static String field(JsonObject embed, String name) {
        for (var f : embed.getAsJsonArray("fields")) {
            JsonObject o = f.getAsJsonObject();
            if (o.get("name").getAsString().equals(name)) return o.get("value").getAsString();
        }
        return null;
    }

    // ================= 嵌入卡片 =================

    static void embeds() {
        JsonObject e = FeedbackPoster.embed(event("B 点卡墙了", null, true, null, "none"), null, "Amincraft");
        check(e.get("title").getAsString().equals("📝 玩家反馈 · Alice"), "标题带玩家名");
        check(e.get("description").getAsString().equals("B 点卡墙了"), "正文 = 反馈内容");
        check("Alice（学习版玩家）".equals(field(e, "玩家")), "学习版玩家标注：" + field(e, "玩家"));
        check("未安装".equals(field(e, "助手")), "没装助手 = 未安装");
        check("类脑市地块 A1".equals(field(e, "位置")), "位置");
        check("amincity 12.3, 64, -7.1".equals(field(e, "坐标")), "坐标：" + field(e, "坐标"));
        check("5 / 40".equals(field(e, "在线")), "在线人数");
        check("2026-10-17T20:15:00+09:00".equals(field(e, "服务器时间")), "服务器时间");
        check(field(e, "截图") == null, "没装助手不显示截图一栏");
        check(!e.has("image"), "没有截图就没有 image");
        check(e.getAsJsonObject("footer").get("text").getAsString().equals("Amincraft · 反馈编号 20261017-abc123"), "页脚带编号");
        check(e.get("timestamp").getAsString().startsWith("2026-"), "timestamp 来自 timeMs");

        JsonObject p = FeedbackPoster.embed(event("x", "1.2.6 fabric", false, null, "timeout"), "feedback-20261017-abc123.png", "");
        check("Alice（正版）".equals(field(p, "玩家")), "正版标注");
        check("1.2.6 fabric".equals(field(p, "助手")), "助手版本");
        check("见下图".equals(field(p, "截图")), "有附件时截图一栏 = 见下图");
        check(p.getAsJsonObject("image").get("url").getAsString().equals("attachment://feedback-20261017-abc123.png"), "嵌入直接显示附件");
        check(FeedbackPoster.shotText("timeout", false).contains("时限"), "超时说明");
        check(FeedbackPoster.shotText("unsupported", false).contains("不支持"), "助手不支持说明");

        String longText = "很长".repeat(3000);
        JsonObject l = FeedbackPoster.embed(event(longText, null, true, null, "none"), null, "A");
        check(l.get("description").getAsString().length() <= FeedbackPoster.MAX_DESCRIPTION, "正文截断到嵌入上限");
        String name = FeedbackPoster.threadName(event("第一行\n" + longText, null, true, null, "none"));
        check(name.length() <= 100 && name.startsWith("反馈 · Alice · 第一行 ") && !name.contains("\n"), "论坛标题 ≤100 字、无换行：" + name.length());

        JsonObject m = FeedbackPoster.message(event("@everyone 快来 <@123>", null, true, null, "none"), null, "A");
        check(m.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty() && !m.getAsJsonObject("allowed_mentions").has("users"),
            "allowed_mentions 不 @ 任何人");
        check(!m.has("content"), "只发嵌入，没有会触发 @ 的 content");
    }

    // ================= 截图路径 =================

    static void shotFiles() throws Exception {
        Path dir = Files.createTempDirectory("fb-shot-test");
        Path png = Files.write(dir.resolve("20261017-abc123.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1});
        Path txt = Files.write(dir.resolve("a.txt"), new byte[]{1});
        Path big = Files.write(dir.resolve("big.jpg"), new byte[2048]);
        check(png.equals(FeedbackPoster.shotFile(png.toString(), 1024)), "正常 png");
        check(FeedbackPoster.shotFile(txt.toString(), 1024) == null, "扩展名不对不带");
        check(FeedbackPoster.shotFile(big.toString(), 1024) == null, "超过上传上限不带");
        check(FeedbackPoster.shotFile(dir.resolve("missing.png").toString(), 1024) == null, "文件不存在不带");
        check(FeedbackPoster.shotFile(null, 1024) == null && FeedbackPoster.shotFile("", 1024) == null && FeedbackPoster.shotFile(5, 1024) == null, "空 / 非字符串");
        check(FeedbackPoster.shotFile(dir.toString(), 1024) == null, "目录不带");
        try {
            Path link = Files.createSymbolicLink(dir.resolve("link.png"), png);
            check(FeedbackPoster.shotFile(link.toString(), 1024) == null, "符号链接不跟随");
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            check(true, "（系统不支持符号链接，跳过）");
        }
    }

    // ================= 发送 =================

    static BotConfig cfg(String channel) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("discord.token", "SECRET-TOKEN-DO-NOT-PRINT");
        y.set("channels.feedback", channel);
        y.set("server.name", "Amincraft");
        return BotConfig.load(y);
    }

    static void run(String api) throws Exception {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), "SECRET-TOKEN-DO-NOT-PRINT", api);
        AtomicReference<BotConfig> c = new AtomicReference<>(cfg(""));
        FeedbackPoster poster = new FeedbackPoster(rest, c::get, Logger.getLogger("test"));

        calls.clear();
        check(poster.post(event("x1", null, true, null, "none")) == null && calls.isEmpty(), "channels.feedback 留空 = 不发、不请求");
        check(cfg("<#" + TEXT + ">").feedbackChannel.equals(TEXT), "配置可直接粘贴 <#id>");

        // 文字频道，无截图
        c.set(cfg(TEXT));
        calls.clear();
        FeedbackPoster.Sent s = poster.post(event("文字频道反馈", null, true, null, "none"));
        check(s != null && !s.forum() && !s.withShot() && s.id().equals("m1"), "文字频道：发一条消息");
        Call post = last("POST");
        check(post != null && post.path.equals("/channels/" + TEXT + "/messages") && !post.multipart(), "文字频道：POST messages（JSON）");
        JsonObject body = post.payload();
        check(body.getAsJsonArray("embeds").size() == 1 && body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty(), "文字频道：嵌入 + 不 @");

        // 论坛（必须选标签）
        c.set(cfg(FORUM));
        calls.clear();
        s = poster.post(event("论坛反馈", null, false, null, "none"));
        check(s != null && s.forum() && s.id().equals("th1"), "论坛：开新帖");
        post = last("POST");
        body = post.payload();
        check(post.path.equals("/channels/" + FORUM + "/threads"), "论坛：POST threads");
        check(body.get("name").getAsString().equals("反馈 · Alice · 论坛反馈"), "论坛：帖子标题");
        JsonArray tags = body.getAsJsonArray("applied_tags");
        check(tags != null && tags.size() == 1 && tags.get(0).getAsString().equals("t1"), "论坛要求标签：自动用第一个");
        check(body.getAsJsonObject("message").getAsJsonArray("embeds").size() == 1
            && body.getAsJsonObject("message").getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty(), "论坛：首条消息是嵌入 + 不 @");

        // 论坛（不要求标签）+ 截图附件
        Path dir = Files.createTempDirectory("fb-send-test");
        byte[] img = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 42, 43, 44};
        Path shot = Files.write(dir.resolve("20261017-abc123.png"), img);
        c.set(cfg(FORUM_FREE));
        calls.clear();
        s = poster.post(event("带截图", "1.2.6 fabric", true, shot.toAbsolutePath().toString(), "ok"));
        check(s != null && s.forum() && s.withShot(), "论坛 + 截图");
        post = last("POST");
        check(post.multipart(), "截图：multipart 上传");
        body = post.payload();
        check(!body.has("applied_tags"), "论坛不要求标签就不加");
        JsonObject msg = body.getAsJsonObject("message");
        check(msg.getAsJsonArray("attachments").size() == 1
            && msg.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString().equals("feedback-20261017-abc123.png"), "附件名固定为 feedback-编号.png");
        check(msg.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("image").get("url").getAsString().equals("attachment://feedback-20261017-abc123.png"), "嵌入引用附件");
        check(post.text().contains("filename=\"feedback-20261017-abc123.png\"") && post.text().contains("Content-Type: image/png"), "附件部分带文件名和图片类型");
        check(new String(post.body, StandardCharsets.ISO_8859_1).contains(new String(img, StandardCharsets.ISO_8859_1)), "附件字节原样上传");
        check(Files.exists(shot), "MiniGameHub 的原截图不删");

        // 文字频道 + 截图
        c.set(cfg(TEXT));
        calls.clear();
        s = poster.post(event("文字带图", "1.2.6 fabric", true, shot.toString(), "ok"));
        post = last("POST");
        check(s != null && s.withShot() && post.multipart() && post.payload().getAsJsonArray("attachments").size() == 1, "文字频道：附件在顶层 attachments");

        // 截图不可用 → 照常发，不带附件
        calls.clear();
        s = poster.post(event("截图丢了", "1.2.6 fabric", true, dir.resolve("gone.png").toString(), "ok"));
        check(s != null && !s.withShot() && !last("POST").multipart(), "截图文件不在：只发嵌入");

        // Token 不出现在任何请求体里
        boolean leaked = false;
        for (Call x : calls) if (x.text().contains("SECRET-TOKEN")) leaked = true;
        check(!leaked, "请求体里没有 token");

        // 频道不存在 → 抛 DiscordException（AminPlayDiscord 记日志）
        c.set(cfg("299999999999999999"));
        try {
            poster.post(event("x", null, true, null, "none"));
            check(false, "频道不存在应抛异常");
        } catch (DiscordRest.DiscordException ex) {
            check(ex.code == 10003, "频道不存在：DiscordException 10003");
        }
    }

    static Call last(String method) {
        synchronized (calls) {
            for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).method.equals(method)) return calls.get(i);
        }
        return null;
    }

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }
}
