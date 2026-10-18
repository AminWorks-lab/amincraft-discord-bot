package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 2026-10-15 match-chat：对局子区的离线自检（假 Discord REST + 假 MatchChatApi，不需要 Bukkit / 真 Discord）：
 * java -cp classes:test-classes:<paper deps> cn.aminplay.discord.MatchChatTest
 */
public final class MatchChatTest {
    private static int passed, failed;

    private static final String CH = "900000000000000001";
    private static final String U1 = "100000000000000001", U2 = "100000000000000002", U3 = "100000000000000003", OPT = "100000000000000009";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant T0 = Instant.parse("2026-10-15T11:05:00Z");   // 东京 20:05

    /** 记下的一次请求。 */
    record Call(String method, String path, String body) {
        JsonObject json() {
            return body == null || body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        }
    }

    static final class FakeDiscord implements AutoCloseable {
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger nextThread = new AtomicInteger(500);
        final Set<String> failThreadCreate = Collections.synchronizedSet(new LinkedHashSet<>());
        final HttpServer server;

        FakeDiscord() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(new Call(ex.getRequestMethod(), path, body));
                int status = 200;
                String resp = "{\"id\":\"1\"}";
                if (path.endsWith("/threads") && ex.getRequestMethod().equals("POST")) {
                    String name = body.isEmpty() ? "" : JsonParser.parseString(body).getAsJsonObject().get("name").getAsString();
                    if (failThreadCreate.stream().anyMatch(name::contains)) {
                        status = 403;
                        resp = "{\"message\":\"Missing Permissions\",\"code\":50013}";
                    } else {
                        resp = "{\"id\":\"" + nextThread.incrementAndGet() + "\"}";
                    }
                } else if (path.contains("/thread-members/")) {
                    status = 204;
                    resp = "";
                }
                byte[] b = resp.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
                if (b.length > 0) ex.getResponseBody().write(b);
                ex.close();
            });
            server.start();
        }

        DiscordRest rest() {
            return new DiscordRest(HttpClient.newHttpClient(), "x", "http://127.0.0.1:" + server.getAddress().getPort());
        }

        List<Call> matching(String method, String pathPart) {
            List<Call> out = new ArrayList<>();
            synchronized (calls) {
                for (Call c : calls) if (c.method.equals(method) && c.path.contains(pathPart)) out.add(c);
            }
            return out;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static final class FakeHub extends HubMatchChat {
        final List<List<String>> posts = Collections.synchronizedList(new ArrayList<>());
        final Set<String> inMatch = new LinkedHashSet<>();
        String status = "ok";
        boolean throwInMatch;

        @Override
        Map<String, Object> post(String match, String room, String discordId, String displayName, String text) {
            posts.add(List.of(match, room, discordId, displayName, text));
            return Map.of("status", status);
        }

        @Override
        boolean inMatch(String uuid) throws Exception {
            if (throwInMatch) throw new IllegalStateException("boom");
            return inMatch.contains(uuid);
        }
    }

    static final class TestHost implements MatchThreads.Host {
        final DiscordRest rest;
        MatchThreads.Settings settings;
        final Set<String> optedOut = new LinkedHashSet<>(Set.of(OPT));
        HubMatchChat hub;
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        int queued;

        TestHost(DiscordRest rest, MatchThreads.Settings settings, HubMatchChat hub) {
            this.rest = rest;
            this.settings = settings;
            this.hub = hub;
        }

        @Override public DiscordRest rest() { return rest; }
        @Override public MatchThreads.Settings settings() { return settings; }
        @Override public boolean optedOut(String id) { return optedOut.contains(id); }
        @Override public HubMatchChat hub() { return hub; }
        @Override public void queue(Runnable task) { queued++; task.run(); }
        @Override public void inbound(Runnable task) { task.run(); }
        @Override public void later(long seconds, Runnable task) { delays.add(seconds); delayed.add(task); }
    }

    public static void main(String[] args) throws Exception {
        openAndMentions();
        privateRoomsAndCatchUp();
        chatForwardAndEscape();
        closeArchives();
        discordToGame();
        bridgeFilter();
        disabledSendsNothing();
        helpers();
        System.out.println("MatchChatTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static MatchThreads.Settings on() {
        return new MatchThreads.Settings(true, CH, true, true, Set.of(), 60, TOKYO);
    }

    static Map<String, Object> member(String uuid, String name, String discordId) {
        Map<String, Object> m = new HashMap<>();
        m.put("uuid", uuid);
        m.put("name", name);
        m.put("discordId", discordId);
        return m;
    }

    static Map<String, Object> room(String id, String name, boolean priv, List<Map<String, Object>> members) {
        return Map.of("room", id, "name", name, "private", priv, "members", members);
    }

    static Map<String, Object> openEvent(String match, String gameId, List<Map<String, Object>> rooms) {
        return Map.of("type", "open", "match", match, "gameId", gameId, "game", "谋杀之谜", "arena", "图书馆", "rooms", rooms);
    }

    static List<Map<String, Object>> standardRooms() {
        List<Map<String, Object>> all = List.of(member("u1", "Alice", U1), member("u2", "Bob", U2), member("u3", "Carol", null),
                member("u9", "Opt", OPT), member("u4", "Dup", U1));
        List<Map<String, Object>> killers = List.of(member("u2", "Bob", U2), member("u9", "Opt", OPT));
        return List.of(room("all", "全场", false, all), room("killers", "杀手", true, killers));
    }

    private static void openAndMentions() throws Exception {
        try (FakeDiscord fd = new FakeDiscord()) {
            TestHost h = new TestHost(fd.rest(), on(), new FakeHub());
            MatchThreads mt = new MatchThreads(h, Logger.getLogger("t"), () -> T0);
            mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
            List<Call> creates = fd.matching("POST", "/channels/" + CH + "/threads");
            eq(2, creates.size(), "open 建 2 个子区（全场 + 杀手）");
            JsonObject pub = creates.get(0).json();
            eq(11, pub.get("type").getAsInt(), "全场 = 公开子区 type 11");
            eq("谋杀之谜 · 图书馆 · 20:05", pub.get("name").getAsString(), "子区名 <游戏> · <场地> · HH:mm（东京时间）");
            eq(60, pub.get("auto_archive_duration").getAsInt(), "auto_archive 60");
            check(!pub.has("invitable"), "公开子区不带 invitable");
            List<Call> firstMsgs = fd.matching("POST", "/channels/501/messages");
            eq(1, firstMsgs.size(), "公开子区发首条消息");
            JsonObject m = firstMsgs.get(0).json();
            String content = m.get("content").getAsString();
            check(content.contains("<@" + U1 + ">") && content.contains("<@" + U2 + ">"), "@ 有绑定的成员");
            check(!content.contains(OPT), "退出隐私的不 @");
            eq(1, content.split("<@" + U1 + ">", -1).length - 1, "同一个 Discord 账号只 @ 一次");
            JsonObject am = m.getAsJsonObject("allowed_mentions");
            eq(0, am.getAsJsonArray("parse").size(), "allowed_mentions.parse 为空（不 @everyone/身份组）");
            JsonArray users = am.getAsJsonArray("users");
            eq(2, users.size(), "allowed_mentions.users 只含这 2 个 id");
            check(users.toString().contains(U1) && users.toString().contains(U2) && !users.toString().contains(OPT), "users 内容");
            check(!content.toLowerCase().contains("example") && !content.contains("服主"), "首条消息不含地址/服主");
            check(mt.byThread("501") != null && mt.byThread("501").room.equals("all"), "记住 501 = all");
            // 重复 open 不重复建
            mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
            eq(2, fd.matching("POST", "/channels/" + CH + "/threads").size(), "同一局重复 open 不重复建子区");

            // mention:false → 不 @
            fd.calls.clear();
            h.settings = new MatchThreads.Settings(true, CH, false, true, Set.of(), 60, TOKYO);
            mt.onEvent(openEvent("murder-2", "murder", standardRooms()));
            Call first = fd.matching("POST", "/messages").get(0);
            check(!first.body.contains("<@"), "mention:false 首条消息不 @");
            eq(0, first.json().getAsJsonObject("allowed_mentions").getAsJsonArray("users").size(), "mention:false allowed users 空");

            // games 过滤
            fd.calls.clear();
            h.settings = new MatchThreads.Settings(true, CH, true, true, Set.of("bedwars"), 60, TOKYO);
            mt.onEvent(openEvent("murder-3", "murder", standardRooms()));
            eq(0, fd.calls.size(), "games 不包含 murder → 不开子区");
        }
    }

    private static void privateRoomsAndCatchUp() throws Exception {
        try (FakeDiscord fd = new FakeDiscord()) {
            TestHost h = new TestHost(fd.rest(), on(), new FakeHub());
            MatchThreads mt = new MatchThreads(h, Logger.getLogger("t"), () -> T0);
            mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
            JsonObject priv = fd.matching("POST", "/channels/" + CH + "/threads").get(1).json();
            eq(12, priv.get("type").getAsInt(), "杀手 = 私密子区 type 12");
            eq(false, priv.get("invitable").getAsBoolean(), "invitable:false");
            eq("谋杀之谜 · 杀手 · 20:05", priv.get("name").getAsString(), "私密子区名用房间名");
            List<Call> puts = fd.matching("PUT", "/channels/502/thread-members/");
            eq(1, puts.size(), "私密子区只加有绑定、没退出隐私的成员");
            check(puts.get(0).path.endsWith("/" + U2), "加的是 U2");
            Call intro = fd.matching("POST", "/channels/502/messages").get(0);
            check(!intro.body.contains("<@"), "私密子区说明不 @");
            eq(0, intro.json().getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(), "私密说明 allowed_mentions 空");

            // rooms：杀手顶替 → 补加 U3，U2 不重复
            List<Map<String, Object>> killers = List.of(member("u2", "Bob", U2), member("u3", "Carol", U3));
            List<Map<String, Object>> all = List.of(member("u1", "Alice", U1), member("u2", "Bob", U2), member("u3", "Carol", U3));
            mt.onEvent(Map.of("type", "rooms", "match", "murder-1", "rooms", List.of(room("all", "全场", false, all), room("killers", "杀手", true, killers))));
            puts = fd.matching("PUT", "/thread-members/");
            eq(2, puts.size(), "rooms 只补加新成员 U3");
            check(puts.get(1).path.equals("/channels/502/thread-members/" + U3), "补加到 502");
            eq(0, fd.matching("PUT", "/channels/501/").size(), "公开子区不 PUT 成员");
            eq(2, fd.matching("POST", "/channels/" + CH + "/threads").size(), "rooms 不重复建子区");
            eq(0, fd.matching("DELETE", "/").size(), "rooms 不移除旧成员");
            // 同样的 rooms 再来一次：什么都不请求
            int before = fd.calls.size();
            mt.onEvent(Map.of("type", "rooms", "match", "murder-1", "rooms", List.of(room("killers", "杀手", true, killers))));
            eq(before, fd.calls.size(), "成员没变化不请求");
            // rooms 带新房间（队伍）→ 开新私密子区
            mt.onEvent(Map.of("type", "rooms", "match", "murder-1", "rooms", List.of(room("team:red", "红队", true, List.of(member("u1", "Alice", U1))))));
            eq(3, fd.matching("POST", "/channels/" + CH + "/threads").size(), "rooms 里的新房间会开子区");
            // 未知 match 的 rooms 忽略
            before = fd.calls.size();
            mt.onEvent(Map.of("type", "rooms", "match", "nope-9", "rooms", List.of(room("killers", "杀手", true, killers))));
            eq(before, fd.calls.size(), "未知对局的 rooms 忽略");

            // private-rooms:false → 只开公开子区
            fd.calls.clear();
            h.settings = new MatchThreads.Settings(true, CH, true, false, Set.of(), 60, TOKYO);
            mt.onEvent(openEvent("murder-2", "murder", standardRooms()));
            List<Call> creates = fd.matching("POST", "/channels/" + CH + "/threads");
            eq(1, creates.size(), "private-rooms:false 只开公开子区");
            eq(0, fd.matching("PUT", "/thread-members/").size(), "private-rooms:false 不加成员");

            // 建子区失败（权限不足）：同一局不反复重试，也不崩
            fd.calls.clear();
            h.settings = on();
            fd.failThreadCreate.add("杀手");
            mt.onEvent(openEvent("murder-4", "murder", standardRooms()));
            mt.onEvent(Map.of("type", "rooms", "match", "murder-4", "rooms", List.of(room("killers", "杀手", true, killers))));
            eq(2, fd.matching("POST", "/channels/" + CH + "/threads").size(), "私密子区建失败后同一局不重试");
            mt.onEvent(Map.of("type", "chat", "match", "murder-4", "room", "killers", "uuid", "u2", "name", "Bob", "text", "hi"));
            eq(0L, fd.matching("POST", "/messages").stream().filter(c -> c.body.contains("hi")).count(), "没子区的房间聊天丢弃");
        }
    }

    private static void chatForwardAndEscape() throws Exception {
        try (FakeDiscord fd = new FakeDiscord()) {
            TestHost h = new TestHost(fd.rest(), on(), new FakeHub());
            MatchThreads mt = new MatchThreads(h, Logger.getLogger("t"), () -> T0);
            mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
            fd.calls.clear();
            mt.onEvent(Map.of("type", "chat", "match", "murder-1", "room", "all", "uuid", "u1", "name", "A_li*ce", "text", "**粗** @everyone <@" + U2 + "> [x](http://a) `c`\n# 标题"));
            List<Call> sent = fd.matching("POST", "/channels/501/messages");
            eq(1, sent.size(), "chat all → 公开子区");
            JsonObject m = sent.get(0).json();
            String c = m.get("content").getAsString();
            check(c.startsWith("**A\\_li\\*ce**: "), "名字加粗并转义：" + c);
            check(c.contains("\\*\\*粗\\*\\*") && c.contains("\\[x\\]\\(http\\:") && c.contains("\\`c\\`") && c.contains("\\<@"), "文字转义 markdown / 提及：" + c);
            check(!c.contains("\n"), "换行被替换");
            JsonObject am = m.getAsJsonObject("allowed_mentions");
            eq(0, am.getAsJsonArray("parse").size(), "chat allowed_mentions.parse 空");
            check(!am.has("users") && !am.has("roles"), "chat 不 @ 任何人");
            eq(4, m.get("flags").getAsInt(), "不展开链接预览");

            mt.onEvent(Map.of("type", "chat", "match", "murder-1", "room", "killers", "uuid", "u2", "name", "Bob", "text", "今晚刀谁"));
            eq(1, fd.matching("POST", "/channels/502/messages").size(), "chat killers → 私密子区");
            check(fd.matching("POST", "/channels/502/messages").get(0).body.contains("今晚刀谁"), "私密子区内容");
            int before = fd.calls.size();
            mt.onEvent(Map.of("type", "chat", "match", "murder-1", "room", "ghost", "uuid", "u2", "name", "Bob", "text", "x"));
            mt.onEvent(Map.of("type", "chat", "match", "other-1", "room", "all", "uuid", "u2", "name", "Bob", "text", "x"));
            mt.onEvent(Map.of("type", "chat", "match", "murder-1", "room", "all", "uuid", "u2", "name", "Bob", "text", "   "));
            eq(before, fd.calls.size(), "ghost / 未知对局 / 空文字不发");
            String longText = "字".repeat(400);
            mt.onEvent(Map.of("type", "chat", "match", "murder-1", "room", "all", "uuid", "u2", "name", "Bob", "text", longText));
            String lc = fd.matching("POST", "/channels/501/messages").get(1).json().get("content").getAsString();
            check(lc.length() < 300, "超长文字截断到 256");
        }
    }

    private static void closeArchives() throws Exception {
        try (FakeDiscord fd = new FakeDiscord()) {
            TestHost h = new TestHost(fd.rest(), on(), new FakeHub());
            MatchThreads mt = new MatchThreads(h, Logger.getLogger("t"), () -> T0);
            mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
            fd.calls.clear();
            mt.onEvent(Map.of("type", "close", "match", "murder-1", "result", "杀手获胜"));
            check(fd.matching("POST", "/channels/501/messages").get(0).body.contains("杀手获胜"), "公开子区发结果");
            check(fd.matching("POST", "/channels/502/messages").get(0).body.contains("杀手获胜"), "私密子区发结果");
            eq(0, fd.matching("PATCH", "/").size(), "延迟前不归档");
            eq(List.of(60L), h.delays, "延迟 close-delay-seconds");
            check(mt.byThread("501") == null, "close 后子区消息不再转发");
            eq(0, mt.openMatches(), "close 后不再记这一局");
            h.delayed.get(0).run();
            List<Call> patches = fd.matching("PATCH", "/channels/");
            eq(2, patches.size(), "两个子区都 PATCH");
            JsonObject b = patches.get(0).json();
            check(b.get("locked").getAsBoolean() && b.get("archived").getAsBoolean(), "locked + archived");
            int before = fd.calls.size();
            mt.onEvent(Map.of("type", "close", "match", "murder-1", "result", "x"));
            eq(before, fd.calls.size(), "重复 close 不请求");
            // 关了 match-chat 之后旧局 close 仍然归档
            mt.onEvent(openEvent("murder-2", "murder", standardRooms()));
            h.settings = MatchThreads.Settings.off();
            mt.onEvent(Map.of("type", "close", "match", "murder-2", "result", "平局"));
            eq(2, h.delayed.size(), "关闭开关后已开的局仍会归档");
        }
    }

    private static JsonObject discordMsg(String channel, String userId, String content) {
        JsonObject d = new JsonObject();
        d.addProperty("channel_id", channel);
        d.addProperty("type", 0);
        d.addProperty("content", content);
        JsonObject a = new JsonObject();
        a.addProperty("id", userId);
        a.addProperty("username", "user" + userId.substring(userId.length() - 1));
        a.addProperty("global_name", "全局名");
        d.add("author", a);
        JsonObject mem = new JsonObject();
        mem.addProperty("nick", "昵称");
        d.add("member", mem);
        return d;
    }

    private static void discordToGame() throws Exception {
        try (FakeDiscord fd = new FakeDiscord()) {
            FakeHub hub = new FakeHub();
            TestHost h = new TestHost(fd.rest(), on(), hub);
            MatchThreads mt = new MatchThreads(h, Logger.getLogger("t"), () -> T0);
            mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
            int restCalls = fd.calls.size();
            check(mt.onDiscordMessage(discordMsg("501", U1, "大家好\n第二行")), "子区消息被认领");
            eq(List.of("murder-1", "all", U1, "昵称", "大家好 第二行"), hub.posts.get(0), "post(match, room, discordId, 昵称, 文字)");
            check(mt.onDiscordMessage(discordMsg("502", U2, "刀他")), "私密子区消息");
            eq(List.of("murder-1", "killers", U2, "昵称", "刀他"), hub.posts.get(1), "私密子区 → killers");
            check(!mt.onDiscordMessage(discordMsg("777", U1, "普通频道")), "不是子区 → 交给普通互通");
            eq(2, hub.posts.size(), "普通频道不 post");

            JsonObject bot = discordMsg("501", U1, "bot");
            bot.getAsJsonObject("author").addProperty("bot", true);
            JsonObject hook = discordMsg("501", U1, "hook");
            hook.addProperty("webhook_id", "1");
            JsonObject sys = discordMsg("501", U1, "sys");
            sys.addProperty("type", 7);
            JsonObject empty = discordMsg("501", U1, "  ");
            for (JsonObject d : new JsonObject[]{bot, hook, sys, discordMsg("501", OPT, "opt"), empty}) check(mt.onDiscordMessage(d), "子区消息仍被认领（不进普通互通）");
            eq(2, hub.posts.size(), "bot / webhook / 系统消息 / 退出隐私 / 空消息 不 post");

            JsonObject att = discordMsg("501", U3, "");
            JsonArray atts = new JsonArray();
            atts.add(new JsonObject());
            att.add("attachments", atts);
            att.remove("member");
            mt.onDiscordMessage(att);
            eq(List.of("murder-1", "all", U3, "全局名", "[图片/文件]"), hub.posts.get(2), "附件 + 没有昵称用全局名");

            hub.status = "no-room";
            mt.onDiscordMessage(discordMsg("501", U1, "不是成员"));
            eq(4, hub.posts.size(), "非 ok 也只是 post 一次");
            eq(restCalls, fd.calls.size(), "Discord → 游戏 不回帖、不加反应");

            mt.onDiscordMessage(discordMsg("501", U1, "x".repeat(600)));
            eq(256, hub.posts.get(4).get(4).length(), "发进游戏的文字 ≤256");

            h.hub = null;
            check(mt.onDiscordMessage(discordMsg("501", U1, "hub 不在")), "MiniGameHub 不在时仍认领");
            eq(5, hub.posts.size(), "MiniGameHub 不在时不 post");
        }
    }

    private static void bridgeFilter() {
        FakeHub hub = new FakeHub();
        hub.inMatch.add("in-1");
        check(!MatchThreads.bridgeFromGame(hub, "in-1"), "对局中玩家的聊天不转 Discord");
        check(MatchThreads.bridgeFromGame(hub, "free-1"), "大厅玩家的聊天照常转");
        check(MatchThreads.bridgeFromGame(null, "in-1"), "MiniGameHub 不在 = 原行为");
        List<String> players = List.of("in-1", "free-1", "free-2");
        eq(List.of("free-1", "free-2"), MatchThreads.bridgeAudience(players, p -> p, hub), "Discord 互通消息只发给不在对局里的玩家");
        eq(players, MatchThreads.bridgeAudience(players, p -> p, null), "MiniGameHub 不在 = 发给所有人");
        hub.throwInMatch = true;
        check(MatchThreads.bridgeFromGame(hub, "in-1"), "inMatch 出错 = 原行为");
    }

    private static void disabledSendsNothing() throws Exception {
        try (FakeDiscord fd = new FakeDiscord()) {
            FakeHub hub = new FakeHub();
            for (MatchThreads.Settings s : new MatchThreads.Settings[]{
                    MatchThreads.Settings.off(),
                    new MatchThreads.Settings(true, "", true, true, Set.of(), 60, TOKYO),
                    new MatchThreads.Settings(false, CH, true, true, Set.of(), 60, TOKYO)}) {
                TestHost h = new TestHost(fd.rest(), s, hub);
                MatchThreads mt = new MatchThreads(h, Logger.getLogger("t"), () -> T0);
                mt.onEvent(openEvent("murder-1", "murder", standardRooms()));
                mt.onEvent(Map.of("type", "chat", "match", "murder-1", "room", "all", "uuid", "u1", "name", "A", "text", "x"));
                mt.onEvent(Map.of("type", "close", "match", "murder-1", "result", "x"));
                check(!mt.onDiscordMessage(discordMsg("501", U1, "x")), "未开启时子区消息不认领");
                eq(0, h.queued == 0 ? 0 : h.delayed.size(), "未开启时没有延迟任务");
            }
            eq(0, fd.calls.size(), "未开启 / 没配频道时一个请求都不发");
            eq(0, hub.posts.size(), "未开启时不 post");
        }
    }

    private static void helpers() {
        eq("对局 · 20:05", MatchThreads.threadName("", "", "20:05"), "没有游戏名");
        eq(100, MatchThreads.threadName("游".repeat(80), "场".repeat(80), "20:05").length() <= 100 ? 100 : -1, "子区名 ≤100");
        eq("a b", MatchThreads.clean("a\u0000\nb", 10), "去控制字符");
        eq("\\_\\*\\~\\`\\|\\>", MatchThreads.md("_*~`|>"), "md 转义");
        MatchThreads.Settings s = new MatchThreads.Settings(true, CH, true, true, Set.of(), 60, TOKYO);
        check(s.active() && s.allowsGame("any"), "空 games = 全部");
        check(!MatchThreads.Settings.off().active(), "默认关闭");
    }

    private static void check(boolean cond, String what) {
        if (cond) passed++;
        else fail(what);
    }

    private static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual == null : expected.equals(actual)) passed++;
        else fail(what + ": expected " + expected + " got " + actual);
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("FAIL " + msg);
    }
}
