package cn.aminplay.discord;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * 2026-10-12 bot-promo：定时广告的离线自检（不需要 Bukkit / 真 Discord）：
 * java -cp classes:test-classes:gson.jar cn.aminplay.discord.PromoTest
 */
public final class PromoTest {
    private static int passed, failed;
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final Duration GAP = Duration.ofMinutes(30), GRACE = Duration.ofMinutes(10);

    public static void main(String[] args) throws Exception {
        parsing();
        slots();
        decisions();
        card();
        store();
        poster();
        managers();
        System.out.println("PromoTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static Instant sh(String local) {
        return java.time.LocalDateTime.parse(local).atZone(SH).toInstant();
    }

    private static void parsing() {
        eq(List.of(LocalTime.of(12, 0), LocalTime.of(20, 0)), PromoRules.parseTimes("12:00,20:00"), "基本");
        eq(List.of(LocalTime.of(8, 30), LocalTime.of(20, 0)), PromoRules.parseTimes(" 20:00，8:30 "), "中文逗号 + 排序");
        eq(List.of(LocalTime.of(12, 0), LocalTime.of(20, 30)), PromoRules.parseTimes("12点、20点半"), "中文 点/半");
        eq(List.of(LocalTime.of(9, 5)), PromoRules.parseTimes("9点5分"), "点分");
        eq(List.of(LocalTime.of(0, 0)), PromoRules.parseTimes("24:00"), "24:00 = 0 点");
        eq(List.of(LocalTime.of(7, 0)), PromoRules.parseTimes("7 07:00 7"), "去重 + 只写小时");
        eq(List.of(LocalTime.of(21, 15)), PromoRules.parseTimes("21：15"), "全角冒号");
        for (String bad : new String[]{"", "   ", null, "25:00", "12:60", "abc", "12:00,xx", "12-00", "-1:00", "1,2,3,4,5,6,7,8,9,10,11,12,13"}) {
            try {
                PromoRules.parseTimes(bad);
                fail("应拒绝「" + bad + "」");
            } catch (IllegalArgumentException e) {
                ok();
            }
        }
        for (int bad : new int[]{0, 25, -3}) {
            try {
                PromoRules.Rule.every(bad);
                fail("间隔 " + bad + " 应拒绝");
            } catch (IllegalArgumentException e) {
                ok();
            }
        }
        eq(null, PromoRules.cleanText("   "), "空文字 = 默认卡片");
        eq("你好", PromoRules.cleanText("  你好 "), "去空白");
        try {
            PromoRules.cleanText("字".repeat(501));
            fail("501 字应拒绝");
        } catch (IllegalArgumentException e) {
            ok();
        }
        eq("字".repeat(500), PromoRules.cleanText("字".repeat(500)), "500 字可以");
        eq("每天 12:00、20:00", PromoRules.Rule.daily(PromoRules.parseTimes("20:00,12:00")).describe(), "describe daily");
        eq("每 3 小时整点（从 0 点算起）", PromoRules.Rule.every(3).describe(), "describe every");
    }

    private static void slots() {
        PromoRules.Rule daily = PromoRules.Rule.daily(PromoRules.parseTimes("12:00,20:00"));
        eq(sh("2026-10-12T12:00").toEpochMilli(), PromoRules.latestSlot(daily, sh("2026-10-12T13:30"), SH), "latest 今天 12 点");
        eq(sh("2026-10-11T20:00").toEpochMilli(), PromoRules.latestSlot(daily, sh("2026-10-12T08:00"), SH), "latest 跨天回到昨天 20 点");
        eq(sh("2026-10-12T12:00").toEpochMilli(), PromoRules.latestSlot(daily, sh("2026-10-12T12:00"), SH), "latest 正好到点");
        eq(sh("2026-10-12T20:00").toEpochMilli(), PromoRules.nextSlot(daily, sh("2026-10-12T12:00"), SH), "next 到点后是下一个");
        eq(sh("2026-10-13T12:00").toEpochMilli(), PromoRules.nextSlot(daily, sh("2026-10-12T21:00"), SH), "next 跨天");
        eq("10-13 12:00", PromoRules.fmtSlot(PromoRules.nextSlot(daily, sh("2026-10-12T21:00"), SH), SH), "fmtSlot");

        PromoRules.Rule every3 = PromoRules.Rule.every(3);
        eq(List.of(LocalTime.of(0, 0), LocalTime.of(3, 0), LocalTime.of(6, 0), LocalTime.of(9, 0), LocalTime.of(12, 0), LocalTime.of(15, 0), LocalTime.of(18, 0), LocalTime.of(21, 0)),
                every3.slotsOfDay(), "每 3 小时 = 0,3,…,21 点");
        eq(sh("2026-10-12T15:00").toEpochMilli(), PromoRules.latestSlot(every3, sh("2026-10-12T16:59:59"), SH), "every3 latest 对齐整点");
        eq(sh("2026-10-12T18:00").toEpochMilli(), PromoRules.nextSlot(every3, sh("2026-10-12T16:00"), SH), "every3 next");
        eq(sh("2026-10-13T00:00").toEpochMilli(), PromoRules.nextSlot(every3, sh("2026-10-12T22:10"), SH), "every3 next 跨天到 0 点");
        PromoRules.Rule every1 = PromoRules.Rule.every(1);
        eq(sh("2026-10-12T17:00").toEpochMilli(), PromoRules.nextSlot(every1, sh("2026-10-12T16:00:01"), SH), "每小时整点");
        PromoRules.Rule every5 = PromoRules.Rule.every(5);
        eq(5, every5.slotsOfDay().size(), "5 小时不整除 24：0,5,10,15,20");
        eq(sh("2026-10-13T00:00").toEpochMilli(), PromoRules.nextSlot(every5, sh("2026-10-12T20:30"), SH), "every5 20 点后下一次 0 点");
        PromoRules.Rule every24 = PromoRules.Rule.every(24);
        eq(List.of(LocalTime.of(0, 0)), every24.slotsOfDay(), "24 = 每天 0 点");
        // 时区：同一 Instant，东京比上海快 1 小时
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        eq(sh("2026-10-12T11:00").toEpochMilli(), PromoRules.latestSlot(daily, sh("2026-10-12T11:30"), tokyo), "按配置时区算（东京 12:00 = 上海 11:00）");
    }

    private static void decisions() {
        PromoRules.Rule daily = PromoRules.Rule.daily(PromoRules.parseTimes("12:00,20:00"));
        Instant setAt = sh("2026-10-12T12:05");
        long init = PromoRules.initialLastSlot(daily, setAt, SH);
        eq(PromoRules.Action.NONE, PromoRules.decide(daily, setAt, SH, init, 0, GAP, GRACE).action(), "刚设置不会立刻补发 12:00");
        PromoRules.Decision d = PromoRules.decide(daily, sh("2026-10-12T20:00:20"), SH, init, 0, GAP, GRACE);
        eq(PromoRules.Action.POST, d.action(), "20:00 到点发");
        eq(sh("2026-10-12T20:00").toEpochMilli(), d.slot(), "slot = 20:00");
        long postedAt = sh("2026-10-12T20:00:20").toEpochMilli();
        eq(PromoRules.Action.NONE, PromoRules.decide(daily, sh("2026-10-12T20:00:50"), SH, d.slot(), postedAt, GAP, GRACE).action(), "同一时间点 30 秒后不重复");
        // 重启：从文件读回 lastSlot 后同一时间点仍然不发
        eq(PromoRules.Action.NONE, PromoRules.decide(daily, sh("2026-10-12T20:03"), SH, d.slot(), postedAt, GAP, GRACE).action(), "重启后同一时间点不重复");
        // 错过太久（关服期间）
        PromoRules.Decision late = PromoRules.decide(daily, sh("2026-10-12T20:45"), SH, init, 0, GAP, GRACE);
        eq(PromoRules.Action.SKIP, late.action(), "错过 45 分钟不补发");
        eq(sh("2026-10-12T20:00").toEpochMilli(), late.slot(), "跳过也记下这个时间点");
        eq(PromoRules.Action.POST, PromoRules.decide(daily, sh("2026-10-12T20:09"), SH, init, 0, GAP, GRACE).action(), "宽限内补发");
        // 防刷屏：19:45 预览过 → 20:00 跳过
        long preview = sh("2026-10-12T19:45").toEpochMilli();
        PromoRules.Decision gap = PromoRules.decide(daily, sh("2026-10-12T20:00:10"), SH, init, preview, GAP, GRACE);
        eq(PromoRules.Action.SKIP, gap.action(), "离上一条不到 30 分钟跳过");
        check(gap.why().contains("30"), "跳过原因写明分钟数");
        long older = sh("2026-10-12T19:29").toEpochMilli();
        eq(PromoRules.Action.POST, PromoRules.decide(daily, sh("2026-10-12T20:00:10"), SH, init, older, GAP, GRACE).action(), "超过 30 分钟照常发");
        eq(PromoRules.Action.POST, PromoRules.decide(daily, sh("2026-10-12T20:00:10"), SH, init, preview, Duration.ZERO, GRACE).action(), "min-gap 0 = 不限制");
        // 每小时 + 30 分钟间隔：正常每小时都发
        PromoRules.Rule every1 = PromoRules.Rule.every(1);
        long s1 = sh("2026-10-12T15:00").toEpochMilli();
        eq(PromoRules.Action.POST, PromoRules.decide(every1, sh("2026-10-12T16:00:05"), SH, s1, sh("2026-10-12T15:00:05").toEpochMilli(), GAP, GRACE).action(), "每小时整点连续发");
    }

    private static void card() {
        PromoRules.Card c = new PromoRules.Card("🎮 测试服", "默认介绍", "play.example.invalid", List.of("起床战争", "空岛战争"),
                "https://example.invalid/pack.zip", "123", "学习版玩家：用 /minmin 领取密钥", 0x57C84D);
        Instant now = Instant.parse("2026-10-12T12:00:00Z");
        JsonObject e = PromoRules.embed(c, null, 3, 50, now);
        String s = e.toString();
        eq("默认介绍", e.get("description").getAsString(), "默认卡片用 intro");
        check(s.contains("play.example.invalid") && s.contains("3 / 50 人") && s.contains("起床战争、空岛战争"), "地址 / 在线 / 玩法");
        check(s.contains("https://example.invalid/pack.zip") && s.contains("<#123>"), "整合包链接 + 论坛");
        check(s.contains("学习版玩家") && !s.contains("盗版"), "用「学习版玩家」");
        JsonObject custom = PromoRules.embed(c, "今晚 8 点起床战争开黑！", -1, 0, now);
        eq("今晚 8 点起床战争开黑！", custom.get("description").getAsString(), "自定义文字替换介绍");
        check(!custom.toString().contains("当前在线"), "取不到在线人数时不显示");
        PromoRules.Card bare = new PromoRules.Card("t", "", "", List.of(), "", "", "", 1);
        JsonObject b = PromoRules.embed(bare, null, -1, 0, now);
        check(!b.has("fields") && !b.has("description") && !b.has("footer"), "空配置不出空栏位");
        PromoRules.Card notUrl = new PromoRules.Card("t", "", "", List.of(), "javascript:x", "", "", 1);
        check(!PromoRules.embed(notUrl, null, -1, 0, now).toString().contains("javascript"), "只接受 http 链接");
    }

    private static void store() throws Exception {
        File dir = Files.createTempDirectory("promo-store").toFile();
        File f = new File(dir, "promo-schedules.json");
        PromoStore s = new PromoStore(f, Logger.getLogger("t"));
        eq(0, s.all().size(), "默认没有定时广告");
        PromoRules.Rule daily = PromoRules.Rule.daily(PromoRules.parseTimes("12:00,20:00"));
        PromoStore.Entry e = new PromoStore.Entry("111", daily, "你好 \"引号\"\n第二行", "admin", 1000L, 0L, null);
        check(s.put(e), "put");
        check(s.put(new PromoStore.Entry("222", PromoRules.Rule.every(3), null, "admin", 5L, 0L, null)), "put 2");
        PromoStore.Entry posted = e.withPosted(2000L, 2100L);
        s.update(e, posted);
        PromoStore r = new PromoStore(f, Logger.getLogger("t"));   // 模拟重启
        eq(2, r.all().size(), "重启后读回 2 条");
        PromoStore.Entry back = r.get("111");
        eq(daily, back.rule(), "规则读回");
        eq("你好 \"引号\"\n第二行", back.text(), "文字读回");
        eq(2000L, back.lastSlot(), "lastSlot 读回（重启不重复发）");
        eq(2100L, back.lastPostedAt(), "lastPostedAt 读回");
        eq(3, r.get("222").rule().everyHours(), "间隔读回");
        eq(null, r.get("222").text(), "无文字");
        // 发送途中被重新设置：不覆盖新规则，但记下发过的时间
        PromoStore.Entry changed = new PromoStore.Entry("111", PromoRules.Rule.every(2), null, "admin2", 3000L, 2100L, null);
        r.put(changed);
        r.update(back, back.withPosted(2500L, 2600L));
        eq(PromoRules.Rule.every(2), r.get("111").rule(), "发送中途改了规则不被旧结果覆盖");
        eq(2600L, r.get("111").lastPostedAt(), "但记下刚发的时间");
        eq(3000L, r.get("111").lastSlot(), "lastSlot 保留新设置的");
        check(r.remove("222") && !r.remove("222"), "remove");
        r.markPostedNow("111", 9999L);
        eq(9999L, new PromoStore(f, null).get("111").lastPostedAt(), "预览记为发过");
        PromoStore.Entry dis = r.get("111").withDisabled("HTTP 404");
        r.update(r.get("111"), dis);
        check(!new PromoStore(f, null).get("111").active(), "停用状态读回");
        Files.writeString(f.toPath(), "{坏掉的 json", StandardCharsets.UTF_8);
        eq(0, new PromoStore(f, Logger.getLogger("t")).all().size(), "坏文件不崩溃");
        Files.writeString(f.toPath(), "{\"schedules\":[{\"channel\":\"5\",\"times\":[\"99:99\"]},{\"channel\":\"6\",\"every-hours\":4}]}", StandardCharsets.UTF_8);
        PromoStore partial = new PromoStore(f, Logger.getLogger("t"));
        check(partial.get("5") == null && partial.get("6") != null, "跳过坏条目，保留好的");
        String json = Files.readString(f.toPath());
        check(!json.contains("token"), "不存 token");
    }

    private static void poster() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            calls.add(ex.getRequestMethod() + " " + path);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = 200;
            String resp = "{\"id\":\"1\"}";
            if (path.equals("/channels/404/messages")) {
                status = 404;
                resp = "{\"message\":\"Unknown Channel\",\"code\":10003}";
            } else if (path.equals("/channels/403/messages")) {
                status = 403;
                resp = "{\"message\":\"Missing Permissions\",\"code\":50013}";
            } else if (path.equals("/channels/500/messages")) {
                status = 500;
                resp = "{\"message\":\"oops\"}";
            }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), "x", "http://127.0.0.1:" + server.getAddress().getPort());
            List<String> logs = new ArrayList<>();
            PromoRules.Card card = new PromoRules.Card("🎮 测试服", "介绍", "play.example.invalid", List.of("起床战争"), "", "", "", 1);
            PromoPoster.Host host = new PromoPoster.Host() {
                @Override public DiscordRest rest() { return rest; }
                @Override public ZoneId zone() { return SH; }
                @Override public Duration minGap() { return GAP; }
                @Override public Duration grace() { return GRACE; }
                @Override public PromoRules.Card card() { return card; }
                @Override public int[] online() { return new int[]{7, 40}; }
                @Override public void adminLog(String text) { logs.add(text); }
            };
            File f = new File(Files.createTempDirectory("promo-poster").toFile(), "promo-schedules.json");
            PromoStore store = new PromoStore(f, null);
            PromoRules.Rule daily = PromoRules.Rule.daily(PromoRules.parseTimes("20:00"));
            long init = PromoRules.initialLastSlot(daily, sh("2026-10-12T19:00"), SH);
            for (String ch : new String[]{"100", "200", "404", "403", "500"}) store.put(new PromoStore.Entry(ch, daily, ch.equals("200") ? "自定义" : null, "a", init, 0, null));
            store.markPostedNow("200", sh("2026-10-12T19:50").toEpochMilli());   // 预览过 → 这次跳过
            PromoPoster p = new PromoPoster(host, store, Logger.getLogger("t"));
            eq(0, p.tick(sh("2026-10-12T19:59:50")), "没到点不发");
            eq(0, calls.size(), "没到点不请求");
            eq(1, p.tick(sh("2026-10-12T20:00:10")), "到点只有 100 发出");
            check(calls.contains("POST /channels/100/messages") && !calls.contains("POST /channels/200/messages"), "100 发、200 因刚预览过跳过");
            String body = bodies.get(calls.indexOf("POST /channels/100/messages"));
            check(body.contains("7 / 40 人") && body.contains("\"parse\":[]"), "带在线人数，不 @ 任何人");
            check(!store.get("404").active() && !store.get("403").active(), "404 / 403 自动停用");
            check(store.get("500").active(), "500 不停用（稍后重试）");
            eq(2, logs.size(), "停用时写管理记录");
            int before = calls.size();
            eq(0, p.tick(sh("2026-10-12T20:00:40")), "同一时间点不重复");
            check(calls.size() == before + 1 && calls.get(calls.size() - 1).equals("POST /channels/500/messages"), "只有失败的 500 在宽限内重试");
            // 重启：新 store / poster 读回，同一时间点不再发
            PromoPoster restarted = new PromoPoster(host, new PromoStore(f, null), Logger.getLogger("t"));
            before = calls.size();
            restarted.tick(sh("2026-10-12T20:01:00"));
            check(calls.size() == before + 1 && calls.get(calls.size() - 1).endsWith("/500/messages"), "重启后 100 不重复发");
            eq(0, restarted.tick(sh("2026-10-12T20:30:00")), "500 过了宽限不再补发");
            eq(2, restarted.tick(sh("2026-10-13T20:00:05")), "第二天 100、200 照常发（500 仍失败，停用的 404/403 不请求）");
            check(!calls.contains("POST /channels/404/messages") || calls.stream().filter(c -> c.endsWith("/404/messages")).count() == 1, "停用后不再请求 404");
        } finally {
            server.stop(0);
        }
    }

    private static void ok() {
        passed++;
    }

    /** 2026-10-12 服主：只有 promo.allowed-users 里的人能设置。 */
    private static void managers() {
        java.util.Set<String> owner = java.util.Set.of("111111111111111111");
        check(PromoRules.mayManage(owner, "111111111111111111"), "服主可以设置");
        check(!PromoRules.mayManage(owner, "222222222222222222"), "其他人（包括管理员）不能设置");
        check(!PromoRules.mayManage(java.util.Set.of(), "111111111111111111"), "没配置时谁都不能设置");
        check(!PromoRules.mayManage(owner, null) && !PromoRules.mayManage(null, "111111111111111111"), "空值");
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
