package cn.aminplay.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * 2026-10-17 对局精彩回放：MiniGameHub HighlightApi 事件 → channels.highlights。用本地假 Discord（不连真的 Discord），测
 * 卡片标题（模板 + 英雄）、对局信息、网页链接、不 @ 任何人、论坛开帖（必选标签）、文字频道、频道留空不发、
 * /minmin 隐私 退出的人换成「某位玩家」并先通知 MiniGameHub 网页换名、换名失败就不带链接。
 * 运行：java -cp classes:test-classes:<paper deps> cn.aminplay.discord.HighlightPosterTest
 */
public final class HighlightPosterTest {
    private static int passed, failed;

    static final String TEXT = "200000000000000110", FORUM = "200000000000000120";
    static final String ALICE_D = "300000000000000001", BOB_D = "300000000000000002";
    static final String ALICE_U = "00000000-0000-3000-8000-000000000001", BOB_U = "00000000-0000-3000-8000-000000000005";

    record Call(String method, String path, String body) {
        JsonObject payload() { return JsonParser.parseString(body).getAsJsonObject(); }
    }

    static final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body);
            calls.add(c);
            String k = c.method + " " + c.path, resp;
            int status = 200;
            if (k.equals("GET /channels/" + TEXT)) resp = "{\"id\":\"" + TEXT + "\",\"type\":0,\"name\":\"精彩\"}";
            else if (k.equals("GET /channels/" + FORUM)) resp = "{\"id\":\"" + FORUM + "\",\"type\":15,\"flags\":16,\"name\":\"精彩论坛\",\"available_tags\":[{\"id\":\"t1\",\"name\":\"精彩\"}]}";
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
            pure();
            run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
        System.out.println("HighlightPosterTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static Map<String, Object> event(String url) {
        Map<String, Object> e = new HashMap<>();
        e.put("type", "highlight");
        e.put("id", "20261017-200000-moba-m#1");
        e.put("kind", "multikill");
        e.put("title", "五杀！Alice（阿尔托莉雅）");
        e.put("titleTemplate", "五杀！{player}（阿尔托莉雅）");
        e.put("player", "Alice");
        e.put("playerUuid", ALICE_U);
        e.put("playerDiscord", ALICE_D);
        e.put("bot", false);
        e.put("hero", "阿尔托莉雅");
        e.put("count", 5);
        e.put("score", 100);
        e.put("game", "moba");
        e.put("gameName", "方块MOBA");
        e.put("map", "王者峡谷");
        e.put("matchStart", 1_792_000_000_000L);
        e.put("at", 125L);
        e.put("clipSeconds", 24L);
        e.put("url", url);
        e.put("urlExpires", url == null ? null : 1_792_259_200_000L);
        List<Map<String, Object>> people = new ArrayList<>();
        people.add(new HashMap<>(Map.of("uuid", ALICE_U, "name", "Alice", "discordId", ALICE_D)));
        Map<String, Object> bob = new HashMap<>(); bob.put("uuid", BOB_U); bob.put("name", "Bob"); bob.put("discordId", BOB_D);
        people.add(bob);
        Map<String, Object> carl = new HashMap<>(); carl.put("uuid", "00000000-0000-3000-8000-000000000009"); carl.put("name", "Carl"); carl.put("discordId", null);
        people.add(carl);
        e.put("people", people);
        e.put("server", "Amincraft");
        return e;
    }

    static String field(JsonObject embed, String name) {
        for (var f : embed.getAsJsonArray("fields")) {
            JsonObject o = f.getAsJsonObject();
            if (o.get("name").getAsString().equals(name)) return o.get("value").getAsString();
        }
        return null;
    }

    // ================= 纯函数 =================

    static void pure() {
        Map<String, Object> e = event("https://mc.example.invalid/replay-web/AAAAAAAAAAAAAAAAAAAAAA");
        check(HighlightPoster.title(e, false).equals("五杀！Alice（阿尔托莉雅）"), "标题 = 模板 + 主角名");
        check(HighlightPoster.title(e, true).equals("五杀！某位玩家（阿尔托莉雅）"), "退出隐私 = 某位玩家");
        Map<String, Object> noTpl = event(null); noTpl.remove("titleTemplate");
        check(HighlightPoster.title(noTpl, false).equals("五杀！Alice（阿尔托莉雅）"), "没有模板用 title");
        check(HighlightPoster.title(noTpl, true).equals("精彩时刻！某位玩家（阿尔托莉雅）"), "没有模板又要隐藏：不用带名字的 title");
        JsonObject em = HighlightPoster.embed(e, false, String.valueOf(e.get("url")), "Amincraft");
        check(em.get("title").getAsString().equals("🎬 五杀！Alice（阿尔托莉雅）"), "卡片标题：" + em.get("title"));
        check(em.get("description").getAsString().equals("方块MOBA · 王者峡谷"), "对局信息：" + em.get("description"));
        check(em.get("url").getAsString().equals(e.get("url")), "标题链接 = 网页回放");
        check("<t:1792000000:f>".equals(field(em, "对局时间")), "对局时间（Discord 时间戳）");
        check("第 2:05（片段 24 秒）".equals(field(em, "时刻")), "时刻：" + field(em, "时刻"));
        check("阿尔托莉雅".equals(field(em, "英雄")), "英雄");
        check(field(em, "网页回放") != null && field(em, "网页回放").contains("(https://mc.example.invalid/replay-web/") && field(em, "网页回放").contains("<t:1792259200:R>"), "网页回放链接 + 失效时间：" + field(em, "网页回放"));
        check(em.get("footer").getAsJsonObject().get("text").getAsString().equals("Amincraft · 精彩回放 · 20261017-200000-moba-m#1"), "页脚");
        JsonObject noUrl = HighlightPoster.embed(event(null), false, "", "Amincraft");
        check(!noUrl.has("url") && field(noUrl, "网页回放") == null, "没有链接就不显示网页回放");
        JsonObject msg = HighlightPoster.message(e, false, "", "Amincraft");
        check(msg.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty() && !msg.has("content"), "不 @ 任何人");
        check(!msg.toString().contains("<@"), "正文里没有 @");
        List<String> hidden = HighlightPoster.hiddenUuids(e, Set.of(BOB_D)::contains);
        check(hidden.equals(List.of(BOB_U)), "退出隐私的真人（有绑定）：" + hidden);
        check(HighlightPoster.hiddenUuids(e, d -> false).isEmpty(), "没人退出");
        check(HighlightPoster.hiddenUuids(e, Set.of(ALICE_D, BOB_D)::contains).size() == 2, "主角也退出");
        check(HighlightPoster.clock(65).equals("1:05") && HighlightPoster.clock(-3).equals("0:00"), "clock");
    }

    // ================= 发送 =================

    static BotConfig config(String channel) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("discord.token", "SECRET-TOKEN-DO-NOT-PRINT");
        y.set("server.name", "Amincraft");
        y.set("channels.highlights", channel);
        return BotConfig.load(y);
    }

    static void run(String api) throws Exception {
        AtomicReference<BotConfig> c = new AtomicReference<>(config(""));
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), "SECRET-TOKEN-DO-NOT-PRINT", api);
        List<String> hides = new ArrayList<>();
        AtomicReference<Boolean> hubOk = new AtomicReference<>(true);
        AtomicReference<Set<String>> out = new AtomicReference<>(Set.of());
        HighlightPoster.Hub hub = (id, uuids) -> { hides.add(id + "=" + uuids); return hubOk.get(); };
        HighlightPoster poster = new HighlightPoster(rest, c::get, d -> out.get().contains(d), () -> hub, Logger.getLogger("test"));
        String url = "https://mc.example.invalid/replay-web/AAAAAAAAAAAAAAAAAAAAAA";

        calls.clear();
        check(poster.post(event(url)) == null && calls.isEmpty(), "channels.highlights 留空 = 不发");

        c.set(config(TEXT));
        calls.clear();
        HighlightPoster.Sent s = poster.post(event(url));
        check(s != null && !s.forum() && s.id().equals("m1") && !s.anonymous(), "文字频道发一条消息");
        Call post = calls.stream().filter(x -> x.method.equals("POST")).findFirst().orElseThrow();
        check(post.path.equals("/channels/" + TEXT + "/messages"), "发到 channels.highlights");
        JsonObject em = post.payload().getAsJsonArray("embeds").get(0).getAsJsonObject();
        check(em.get("title").getAsString().contains("Alice"), "正常显示主角名");
        check(em.has("url"), "带网页链接");
        check(hides.isEmpty(), "没人退出就不调换名");
        check(!post.body.contains("SECRET-TOKEN"), "token 不出现在消息里");

        // Bob（非主角）退出：网页换名，卡片仍显示 Alice
        out.set(Set.of(BOB_D));
        calls.clear(); hides.clear();
        s = poster.post(event(url));
        check(hides.equals(List.of("20261017-200000-moba-m#1=[" + BOB_U + "]")), "先让 MiniGameHub 在网页里给 Bob 换名：" + hides);
        em = lastEmbed();
        check(em.get("title").getAsString().contains("Alice") && !s.anonymous(), "主角没退出：仍显示名字");
        check(em.has("url"), "换名成功：仍带链接");
        check(!lastBody().contains("Bob"), "卡片里不出现 Bob");

        // 主角 Alice 退出
        out.set(Set.of(ALICE_D));
        calls.clear(); hides.clear();
        s = poster.post(event(url));
        em = lastEmbed();
        check(s.anonymous() && em.get("title").getAsString().equals("🎬 五杀！某位玩家（阿尔托莉雅）"), "主角退出：某位玩家：" + em.get("title"));
        check(!lastBody().contains("Alice"), "卡片里完全没有 Alice");
        check(hides.size() == 1 && hides.get(0).contains(ALICE_U), "网页里也换名");

        // 换名失败：不带链接
        hubOk.set(false);
        calls.clear(); hides.clear();
        poster.post(event(url));
        em = lastEmbed();
        check(!em.has("url") && field(em, "网页回放") == null, "网页换名失败 = 不带链接");
        hubOk.set(true);

        // 没有链接时不需要换名
        calls.clear(); hides.clear();
        poster.post(event(null));
        check(hides.isEmpty(), "没有网页链接就不调换名");
        out.set(Set.of());

        // 论坛：开新帖，必选标签用第一个
        c.set(config(FORUM));
        calls.clear();
        s = poster.post(event(url));
        check(s.forum() && s.id().equals("th1"), "论坛开新帖");
        Call t = calls.stream().filter(x -> x.method.equals("POST")).findFirst().orElseThrow();
        check(t.path.equals("/channels/" + FORUM + "/threads"), "POST threads");
        JsonObject body = t.payload();
        check(body.get("name").getAsString().equals("五杀！Alice（阿尔托莉雅）"), "帖子标题 = 卡片标题：" + body.get("name"));
        check(body.getAsJsonArray("applied_tags").get(0).getAsString().equals("t1"), "必选标签用第一个");
        check(body.getAsJsonObject("message").getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty(), "论坛帖也不 @");

        // 频道不存在：抛出（worker 记日志），不重试
        c.set(config("200000000000000999"));
        boolean threw = false;
        try { poster.post(event(url)); } catch (DiscordRest.DiscordException ex) { threw = true; }
        check(threw, "频道不存在 = DiscordException");
    }

    static String lastBody() {
        for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).method.equals("POST")) return calls.get(i).body;
        return "";
    }

    static JsonObject lastEmbed() { return JsonParser.parseString(lastBody()).getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject(); }

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + what); }
    }
}
