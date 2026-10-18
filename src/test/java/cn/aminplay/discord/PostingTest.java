package cn.aminplay.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 2026-10-12 bot-posting：控制台发帖（/discordbot posts | post | forum）。用本地假 Discord（不连真的 Discord），测
 * 草稿头部解析、命令解析、频道别名、路径穿越拒绝、大小上限、试运行输出、发出去的请求 JSON（纯文本 / 嵌入 / 论坛 + 附件）和 @ 通知组。
 * 合并了原来的 JUnit 版 PostDraftsTest（pom 里没有 JUnit，那个类会让 mvn 编译失败）。
 * 运行：java -cp classes;test-classes;<paper deps> cn.aminplay.discord.PostingTest
 */
public final class PostingTest {
    private static int passed, failed;

    static final String TOKEN = "SECRET-TOKEN-DO-NOT-PRINT";
    static final String GUILD = "100000000000000001", TEXT = "100000000000000010", FORUM = "100000000000000020", NEWS = "100000000000000030";
    static final String EDT = "555000000000000001";
    static final String EDA = "555000000000000002";   // 2026-10-12: an archived forum post (PATCH → 50083 until unarchived)
    static volatile boolean edaArchived = true;   // 2026-10-12 edit: a forum post (thread id = its first message id)

    record Call(String method, String path, byte[] body, String contentType, String auth) {
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
    static final AtomicInteger ids = new AtomicInteger(500);

    public static void main(String[] args) throws Exception {
        System.setProperty("aminplay.discord.settle-ms", "10");   // Notifier waits this long before turning the @ into text
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), body, ex.getRequestHeaders().getFirst("Content-Type"),
                ex.getRequestHeaders().getFirst("Authorization"));
            calls.add(c);
            int status = 200;
            String resp = "{}";
            String k = c.method + " " + c.path;
            if (k.equals("GET /channels/" + TEXT)) resp = "{\"id\":\"" + TEXT + "\",\"type\":0,\"name\":\"公告\"}";
            else if (k.equals("GET /channels/" + NEWS)) resp = "{\"id\":\"" + NEWS + "\",\"type\":5,\"name\":\"更新\"}";
            else if (k.equals("GET /channels/" + FORUM)) resp = "{\"id\":\"" + FORUM + "\",\"type\":15,\"flags\":16,\"name\":\"整合包\",\"available_tags\":[{\"id\":\"t1\",\"name\":\"整合包\"},{\"id\":\"t2\",\"name\":\"更新\"}]}";
            else if (k.equals("GET /guilds/" + GUILD)) resp = "{\"id\":\"" + GUILD + "\",\"premium_tier\":0}";
            else if (k.equals("POST /channels/" + FORUM + "/threads")) resp = "{\"id\":\"th" + ids.incrementAndGet() + "\",\"type\":11}";
            else if (c.method.equals("POST") && c.path.endsWith("/messages")) resp = "{\"id\":\"m" + ids.incrementAndGet() + "\"}";
            else if (k.equals("POST /guilds/" + GUILD + "/roles")) resp = "{\"id\":\"r9\"}";
            else if (c.method.equals("PUT") || c.method.equals("DELETE")) status = 204;
            else if (k.equals("PATCH /channels/" + EDA + "/messages/" + EDA) && edaArchived) { status = 400; resp = "{\"code\":50083,\"message\":\"Thread is archived\"}"; }
            else if (k.equals("PATCH /channels/" + EDA)) edaArchived = false;
            else if (k.equals("GET /channels/" + EDA + "/messages/" + EDA)) resp = "{\"id\":\"" + EDA + "\",\"content\":\"旧\"}";
            else if (k.equals("GET /channels/" + EDA)) resp = "{\"id\":\"" + EDA + "\",\"type\":11,\"name\":\"t\"}";
            else if (k.equals("GET /channels/" + EDT + "/messages/" + EDT)) resp = "{\"id\":\"" + EDT + "\",\"content\":\"第一行\\n旧的一行\"}";
            else if (k.equals("GET /channels/" + EDT)) resp = "{\"id\":\"" + EDT + "\",\"type\":11,\"name\":\"旧标题\"}";
            else if (c.method.equals("GET")) { status = 404; resp = "{\"code\":10003,\"message\":\"Unknown Channel\"}"; }
            byte[] b = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
            if (status != 204) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            parsing();
            commands();
            aliases();
            files();
            run("http://127.0.0.1:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
        System.out.println("PostingTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ================= 草稿头部 =================

    static void parsing() {
        PostDrafts.Draft d = PostDrafts.parse("---\ntarget: modpack   # 整合包论坛\nmode: forum\ntitle: \"Amincraft 整合包 1.2.6\"\ntag: 整合包\n"
            + "files: a.mrpack, b.png, a.mrpack\nping: everyone\nformat: embed\ncolor: #3366FF\nimage: b.png\n---\n第一段\n\n第二段\n");
        check(d.target().equals("modpack") && d.mode() == PostDrafts.Mode.FORUM, "头部：target / mode");
        check(d.title().equals("Amincraft 整合包 1.2.6"), "头部：title 去掉引号");
        check(d.files().equals(List.of("a.mrpack", "b.png")), "头部：files 去重 " + d.files());
        check(d.everyone() && d.embed() && d.color() == 0x3366FF && d.image().equals("b.png"), "头部：ping / format / color / image");
        check(d.parts().equals(List.of("第一段\n\n第二段")), "正文 1 条");

        // the example in docs/devlog/2026-10-12-bot-posting.md
        PostDrafts.Draft doc = PostDrafts.parse("---\ntitle: Amincraft 整合包 1.2.6\nformat: embed\ntag: 整合包\nping: mc            # roles.panel 里的「MC玩家」订阅组\n"
            + "files: Amincraft-1.2.6.mrpack, 启动器截图.png\nimage: 启动器截图.png\n---\n**下载**：附件里的 `Amincraft-1.2.6.mrpack`\n\n**学习版玩家**：先在 Discord 用 `/minmin 领取密钥`。\n");
        check(doc.ping().equals("mc") && doc.embed() && doc.files().size() == 2 && doc.image().equals("启动器截图.png") && doc.parts().size() == 1, "开发记录里的示例草稿");

        // ported from main bd0c4a2 (PostDraftsTest): several tags, tags + legacy tag, at most 5
        PostDrafts.Draft tg = PostDrafts.parse("---\nmode: forum\ntitle: t\ntags: 100000000000000123, 版本更新\ntag: 官方\n---\nx");
        check(tg.tags().equals(List.of("100000000000000123", "版本更新", "官方")), "tags: 多个标签 + 旧写法 tag: " + tg.tags());
        check(PostDrafts.parse("---\ntags: 官方, 官方\n---\nx").tags().equals(List.of("官方")), "标签去重");
        refuses("---\nmode: forum\ntitle: t\ntags: a,b,c,d,e,f\n---\nx", "标签超过 5 个");

        PostDrafts.Draft plain = PostDrafts.parse("\uFEFF服务器 20:00 维护\r\n");
        check(plain.target().equals("announce") && plain.mode() == PostDrafts.Mode.MESSAGE && !plain.embed() && plain.ping().equals("none"), "没有头部：announce / message / 纯文本 / 不 @");
        check(plain.parts().equals(List.of("服务器 20:00 维护")), "BOM 和 CRLF 去掉");

        PostDrafts.Draft titled = PostDrafts.parse("---\ntitle: 维护公告\n---\n今晚 8 点");
        check(titled.parts().get(0).equals("**维护公告**\n\n今晚 8 点"), "纯文本消息的 title 变成粗体首行");
        check(PostDrafts.parse("---\nping: mc\n---\nx").ping().equals("mc"), "ping 面板 key");
        check(PostDrafts.parse("---\nping: role:123456789012345678\n---\nx").ping().equals("role:123456789012345678"), "ping role:<id>");
        check(PostDrafts.parse("---\nembed: true\n---\nx").embed(), "embed: true 等于 format: embed");
        check(PostDrafts.parse("---\ntitle: 更新 #1 期  # 注释\n---\nx").title().equals("更新 #1 期"), "「 # 」才是注释，标题里的 #1 保留");

        refuses("---\nmode: forum\n---\n正文", "论坛帖没 title");
        refuses("---\nfiles: ../config.yml\n---\nx", "附件 ../");
        refuses("---\nfiles: sub/a.png\n---\nx", "附件带 /");
        refuses("---\nfiles: ..\\\\a.png\n---\nx", "附件带 \\");
        refuses("---\nfiles: C:a.png\n---\nx", "附件带盘符");
        refuses("---\nfiles: .env\n---\nx", "附件是隐藏文件");
        refuses("---\ntarget: ../x\n---\nx", "target 不是别名或 id");
        refuses("---\nping here\n---\nx", "头部一行没有冒号");
        refuses("---\nping: role:abc\n---\nx", "ping role: 不是 id");
        refuses("---\nformat: html\n---\nx", "format 不认识");
        refuses("---\nimage: https://x/y.png\n---\nx", "纯文本不能用 image");
        refuses("---\nformat: embed\nimage: c.png\n---\nx", "image 不在 files 里");
        refuses("---\ncolor: red\n---\nx", "color 不是 #RRGGBB");
        refuses("---\ntarget: announce\n", "--- 没闭合");
        refuses("---\n---\n", "正文和附件都空");
        refuses("---\ntitle: " + "长".repeat(101) + "\n---\nx", "title 超过 100 字");
        refuses("---\nfiles: " + String.join(",", java.util.stream.IntStream.range(0, 11).mapToObj(i -> "f" + i + ".png").toList()) + "\n---\nx", "附件超过 10 个");
        refuses("x".repeat(2000 * 11), "正文超过 10 条");

        String para = "字".repeat(1500);
        List<String> parts = PostDrafts.split(para + "\n\n" + para + "\n\n" + "x".repeat(4500), 2000);
        boolean within = true;
        for (String p : parts) within &= p.length() >= 1 && p.length() <= 2000;
        check(within && parts.stream().mapToInt(p -> p.replace("\n", "").length()).sum() == 7500, "长正文按段落拆，每条 ≤ 2000 字");
        PostDrafts.Draft pinged = PostDrafts.parse("---\nping: everyone\n---\n" + "y".repeat(1990));
        check(pinged.parts().size() == 2 && pinged.parts().get(0).length() <= 2000 - PostDrafts.PING_RESERVE, "有 @ 时第一条留出位置");
        PostDrafts.Draft embed = PostDrafts.parse("---\nformat: embed\n---\n" + "y".repeat(3990));
        check(embed.parts().size() == 1, "嵌入每条 4000 字");

        PostDrafts.Draft q = PostDrafts.quick("今晚 8 点维护\\n大约 30 分钟", PostDrafts.Mode.MESSAGE, "");
        check(q.parts().equals(List.of("今晚 8 点维护\n大约 30 分钟")) && q.files().isEmpty() && q.ping().equals("none"), "text: 快速消息，\\n 换行，不 @");
        try { PostDrafts.quick(" ", PostDrafts.Mode.MESSAGE, ""); check(false, "text: 空的应拒绝"); } catch (IllegalArgumentException e) { check(true, "text: 空的拒绝"); }
    }

    static void refuses(String text, String what) {
        try {
            PostDrafts.parse(text);
            check(false, "应拒绝：" + what);
        } catch (IllegalArgumentException e) {
            check(true, "拒绝：" + what + "（" + e.getMessage() + "）");
        }
    }

    // ================= 命令解析 =================

    static void commands() {
        PostDrafts.Cmd c = PostDrafts.parseArgs(new String[]{"posts"});
        check(c.kind() == PostDrafts.Kind.LIST, "posts = 列表");
        check(PostDrafts.parseArgs(new String[]{"post"}).kind() == PostDrafts.Kind.HELP, "post 无参数 = 帮助");
        c = PostDrafts.parseArgs(new String[]{"post", "announce", "整合包-1_2_6.md"});
        check(c.kind() == PostDrafts.Kind.SEND && c.mode() == PostDrafts.Mode.MESSAGE && c.target().equals("announce") && c.draft().equals("整合包-1_2_6") && !c.dryRun(), "post <别名> <草稿.md>");
        c = PostDrafts.parseArgs(new String[]{"forum", "modpack", "整合包", "--dry-run"});
        check(c.mode() == PostDrafts.Mode.FORUM && c.dryRun() && c.draft().equals("整合包"), "forum <别名> <草稿> --dry-run");
        c = PostDrafts.parseArgs(new String[]{"post", "--dry-run", TEXT, "text:今晚", "8", "点维护"});
        check(c.draft() == null && c.quickText().equals("今晚 8 点维护") && c.dryRun() && c.target().equals(TEXT), "post <id> text:… 带空格");
        c = PostDrafts.parseArgs(new String[]{"post", "announce", "公告", "--again"});
        check(c.again() && !c.dryRun(), "--again");
        c = PostDrafts.parseArgs(new String[]{"post", "公告"});
        check(c.kind() == PostDrafts.Kind.SEND && c.target() == null && c.dryRun(), "旧写法 post <草稿> = 预览");
        c = PostDrafts.parseArgs(new String[]{"post", "公告", "confirm"});
        check(c.target() == null && !c.dryRun(), "旧写法 post <草稿> confirm = 发送");
        c = PostDrafts.parseArgs(new String[]{"post", "公告", "confirm", "--dry-run"});
        check(c.dryRun(), "confirm 加 --dry-run 仍然只预览");
        check(PostDrafts.parseArgs(new String[]{"post", "announce", "../config.yml"}).error() != null, "拒绝 ../config.yml");
        check(PostDrafts.parseArgs(new String[]{"post", "announce", "..\\config"}).error() != null, "拒绝 ..\\config");
        check(PostDrafts.parseArgs(new String[]{"post", "announce", "C:/x.md"}).error() != null, "拒绝绝对路径");
        check(PostDrafts.parseArgs(new String[]{"post", "announce", "a.txt"}).error() != null, "拒绝其它扩展名");
        check(PostDrafts.parseArgs(new String[]{"post", "../x"}).error() != null, "旧写法也拒绝路径");
        check(PostDrafts.parseArgs(new String[]{"post", "#公告", "x"}).error() != null, "频道写法不对");
        check(PostDrafts.parseArgs(new String[]{"post", "announce"}).error() == null, "post <名字> 一个参数 = 旧写法预览");
        check(PostDrafts.parseArgs(new String[]{"forum", "modpack", "text:x"}).error() != null, "论坛不能 text:");
        check(PostDrafts.parseArgs(new String[]{"post", "announce", "a", "b"}).error() != null, "参数太多");
        check(PostDrafts.parseArgs(new String[]{"forum", "modpack"}).error() != null, "forum 缺草稿");
        check(PostDrafts.USAGE.contains("--dry-run") && PostDrafts.USAGE.contains("text:"), "帮助里有 --dry-run 和 text:");
    }

    // ================= 频道别名 =================

    static void aliases() {
        Map<String, String> a = new HashMap<>();
        a.put("updates", NEWS);
        a.put("broken", "#公告");
        a.put("announce", "100000000000000099");   // posts.aliases 覆盖内置
        check(PostDrafts.resolveTarget(TEXT, a, "", "").equals(TEXT), "数字 id 原样");
        check(PostDrafts.resolveTarget("updates", a, "", "").equals(NEWS), "posts.aliases 别名");
        check(PostDrafts.resolveTarget("announce", a, TEXT, "").equals("100000000000000099"), "别名覆盖内置 announce");
        check(PostDrafts.resolveTarget("announce", Map.of(), TEXT, "").equals(TEXT), "内置 announce = channels.announce");
        check(PostDrafts.resolveTarget("modpack", Map.of(), TEXT, FORUM).equals(FORUM), "内置 modpack = posts.modpack-forum");
        check(PostDrafts.resolveTarget("modpack", Map.of(), TEXT, "") == null, "modpack 没设置 = null");
        check(PostDrafts.resolveTarget("nope", a, TEXT, FORUM) == null, "不认识的别名 = null");
        check(PostDrafts.resolveTarget("broken", a, TEXT, FORUM) == null, "别名的值不是 id = null");
        check(PostDrafts.resolveTarget("12", a, TEXT, FORUM) == null, "太短的数字不是 id");

        List<String> log = List.of(PostDrafts.logLine("2026-10-12 20:00:00", TEXT, "m1", "posts/公告.md"));
        check(log.get(0).split("\t").length == 4, "sent.log 一行 4 列");
        check(PostDrafts.alreadySent(log, TEXT, "posts/公告.md") && !PostDrafts.alreadySent(log, NEWS, "posts/公告.md") && !PostDrafts.alreadySent(log, TEXT, "posts/别的.md"), "重复发送检测按 频道+草稿");
        check(!PostDrafts.logLine("a\tb", "c\nd", "e", "f").contains("\n") && PostDrafts.logLine("a\tb", "c", "e", "f").split("\t").length == 4, "sent.log 字段去掉 tab / 换行");
    }

    // ================= 草稿文件 =================

    static void files() throws Exception {
        Path root = Files.createTempDirectory("posting-test");
        Path posts = Files.createDirectories(root.resolve("posts"));
        Files.writeString(root.resolve("secret.md"), "---\ntarget: announce\n---\n不该被读到", StandardCharsets.UTF_8);
        Files.writeString(posts.resolve("公告.md"), "今晚维护", StandardCharsets.UTF_8);
        Files.writeString(posts.resolve("大.md"), "x".repeat((int) PostDrafts.MAX_DRAFT_BYTES + 1), StandardCharsets.UTF_8);
        Files.createDirectories(posts.resolve("dir.md"));
        check(PostDrafts.read(posts, "公告").parts().equals(List.of("今晚维护")), "读草稿");
        readFails(posts, "../secret", "路径穿越 ../secret");
        readFails(posts, "..", "..");
        readFails(posts, "大", "草稿超过 128 KB");
        readFails(posts, "dir", "目录不是草稿");
        readFails(posts, "没有", "不存在");
        try { PostDrafts.read(posts, "没有"); } catch (java.nio.file.NoSuchFileException e) { check(true, "不存在 = NoSuchFileException"); }
    }

    static void readFails(Path dir, String name, String what) {
        try {
            PostDrafts.read(dir, name);
            check(false, "应拒绝：" + what);
        } catch (IOException e) {
            check(true, "拒绝：" + what);
        }
    }

    // ================= 假 Discord =================

    static void run(String api) throws Exception {
        DiscordRest rest = new DiscordRest(HttpClient.newHttpClient(), TOKEN, api);
        Path filesDir = Files.createDirectories(Files.createTempDirectory("posting-files").resolve("files"));
        Files.write(filesDir.resolve("Amincraft-1.2.6.mrpack"), new byte[3000]);
        Files.write(filesDir.resolve("shot.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Files.write(filesDir.resolve("huge.zip"), new byte[20_000]);
        Files.writeString(filesDir.getParent().resolve("outside.png"), "x");

        YamlConfiguration yc = new YamlConfiguration();
        yc.loadFromString("discord:\n  guild-id: '" + GUILD + "'\nroles:\n  panel:\n    - key: mc\n      name: MC玩家\n      type: subscribe\n"
            + "    - key: update\n      name: 版本更新\n      type: role\n      id: '100000000000000077'\n");
        BotConfig cfg = BotConfig.load(yc);
        Path subFile = Files.createTempDirectory("posting-subs").resolve("subscriptions.yml");
        Subscriptions subs = new Subscriptions(subFile.toFile(), Logger.getLogger("PostingTest"));
        subs.toggle("mc", "200000000000000001");
        subs.toggle("mc", "200000000000000002");
        Notifier notifier = new Notifier(rest, subs, Logger.getLogger("PostingTest"));
        DraftPoster.Pings pings = DraftPoster.standardPings(cfg, notifier, subs, e -> e.id());
        DraftPoster poster = new DraftPoster(rest, 10_000, pings, "Amincraft");

        // ---- 试运行：只 GET，不 POST；输出里有频道名、标题、字数、附件，没有 token ----
        PostDrafts.Draft fd = PostDrafts.parse("---\ntitle: Amincraft 整合包 1.2.6\ntag: 整合包\nfiles: Amincraft-1.2.6.mrpack, shot.png\nping: mc\nformat: embed\nimage: shot.png\n---\n"
            + "下载后用启动器导入。\n\n学习版玩家请先在 Discord 领取登录密钥。");
        calls.clear();
        DraftPoster.Plan plan = poster.plan(fd, PostDrafts.Mode.FORUM, FORUM, filesDir, GUILD);
        List<String> lines = plan.lines("posts/整合包.md");
        String all = String.join("\n", lines);
        System.out.println(all);
        check(calls.stream().allMatch(c -> c.method.equals("GET")), "试运行只有 GET：" + calls.stream().map(c -> c.method + " " + c.path).toList());
        check(all.contains("#整合包") && all.contains(FORUM) && all.contains("论坛"), "试运行：频道名和 id");
        check(all.contains("Amincraft 整合包 1.2.6") && all.contains("标签 整合包"), "试运行：标题和标签");
        check(all.contains("正文 " + fd.length() + " 字") && all.contains("分 1 条") && all.contains("嵌入"), "试运行：长度和格式");
        check(all.contains("Amincraft-1.2.6.mrpack（2.9 KB）") && all.contains("shot.png"), "试运行：附件和大小");
        check(all.contains("通知组「MC玩家」（2 人"), "试运行：@ 通知组和人数");
        check(!all.contains(TOKEN), "试运行输出没有 token");
        check(calls.stream().allMatch(c -> ("Bot " + TOKEN).equals(c.auth)), "请求带 Bot 鉴权（只在请求头里）");

        // ---- 论坛开新帖：附件在开帖消息里（multipart，attachments 在 message 里），嵌入图片引用附件，@ 通知组 ----
        calls.clear();
        DraftPoster.Sent sent = poster.send(plan, GUILD);
        List<Call> c = new ArrayList<>(calls);
        Call thread = c.stream().filter(x -> x.method.equals("POST") && x.path.equals("/channels/" + FORUM + "/threads")).findFirst().orElse(null);
        check(thread != null && thread.multipart(), "论坛：POST threads（multipart）");
        if (thread != null) {
            JsonObject p = thread.payload();
            JsonObject msg = p.getAsJsonObject("message");
            check(p.get("name").getAsString().equals("Amincraft 整合包 1.2.6"), "论坛：帖子标题");
            check(p.getAsJsonArray("applied_tags").get(0).getAsString().equals("t1"), "论坛：标签 整合包 = t1");
            check(!p.has("attachments") && msg.getAsJsonArray("attachments").size() == 2
                && msg.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString().equals("Amincraft-1.2.6.mrpack"), "论坛：attachments 放在 message 里");
            JsonObject e = msg.getAsJsonArray("embeds").get(0).getAsJsonObject();
            check(e.get("title").getAsString().equals("Amincraft 整合包 1.2.6") && e.get("description").getAsString().contains("学习版玩家")
                && e.getAsJsonObject("image").get("url").getAsString().equals("attachment://shot.png") && e.get("color").getAsInt() == PostDrafts.DEFAULT_COLOR
                && e.getAsJsonObject("footer").get("text").getAsString().equals("Amincraft"), "论坛：嵌入标题 / 正文 / 图片 / 颜色 / 页脚");
            check(msg.get("content").getAsString().equals("<@&r9>") && msg.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").get(0).getAsString().equals("r9"), "论坛：@ 临时身份组");
            String t = thread.text();
            check(t.contains("name=\"files[0]\"; filename=\"Amincraft-1.2.6.mrpack\"") && t.contains("name=\"files[1]\"; filename=\"shot.png\""), "论坛：两个文件都上传");
        }
        check(c.stream().anyMatch(x -> x.method.equals("POST") && x.path.equals("/guilds/" + GUILD + "/roles")), "通知组：建临时身份组（复用 Notifier）");
        check(c.stream().filter(x -> x.method.equals("PUT") && x.path.contains("/roles/r9")).count() == 2, "通知组：加 2 个订阅者");
        Call patch = c.stream().filter(x -> x.method.equals("PATCH")).findFirst().orElse(null);
        check(patch != null && patch.path.equals("/channels/" + sent.channel() + "/messages/" + sent.messageId()) && patch.payload().get("content").getAsString().contains("MC玩家"), "通知组：@ 改成文字");
        check(c.stream().anyMatch(x -> x.method.equals("DELETE") && x.path.equals("/guilds/" + GUILD + "/roles/r9")), "通知组：删临时身份组");
        check(sent.link().equals("https://discord.com/channels/" + GUILD + "/" + sent.channel()) && sent.channel().startsWith("th") && sent.messageId().equals(sent.channel()), "论坛：链接 = 帖子");
        check(subs.pending().isEmpty(), "临时身份组记录已清掉");

        // ---- 纯文本消息：@everyone，长正文拆成 2 条，附件跟第一条 ----
        PostDrafts.Draft md = PostDrafts.parse("---\nping: everyone\nfiles: shot.png\ntitle: 维护公告\n---\n" + "a".repeat(1500) + "\n\n" + "b".repeat(1500));
        calls.clear();
        DraftPoster.Plan mp = poster.plan(md, PostDrafts.Mode.MESSAGE, TEXT, filesDir, GUILD);
        DraftPoster.Sent ms = poster.send(mp, GUILD);
        List<Call> posts = calls.stream().filter(x -> x.method.equals("POST")).toList();
        check(posts.size() == 2 && posts.stream().allMatch(x -> x.path.equals("/channels/" + TEXT + "/messages")), "纯文本：2 条消息");
        if (posts.size() == 2) {
            JsonObject p1 = posts.get(0).payload(), p2 = posts.get(1).payload();
            check(posts.get(0).multipart() && p1.getAsJsonArray("attachments").size() == 1 && !posts.get(1).multipart(), "纯文本：附件跟第一条");
            check(p1.get("content").getAsString().startsWith("@everyone\n**维护公告**\n\n") && p1.get("content").getAsString().length() <= 2000, "纯文本：@everyone + 粗体标题，≤ 2000 字");
            check(p1.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").get(0).getAsString().equals("everyone"), "纯文本：允许 @everyone");
            check(p2.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty() && !p2.has("embeds") && p2.get("content").getAsString().startsWith("b"), "纯文本：第二条不 @");
        }
        check(ms.link().equals("https://discord.com/channels/" + GUILD + "/" + TEXT + "/" + ms.messageId()), "纯文本：链接到第一条");
        check(calls.stream().noneMatch(x -> x.method.equals("PATCH") || x.method.equals("DELETE")), "@everyone 不建临时身份组");

        // ---- 公告频道 + 常驻身份组 + text: ----
        calls.clear();
        poster.send(poster.plan(PostDrafts.parse("---\nping: update\n---\n新版本上线"), PostDrafts.Mode.MESSAGE, NEWS, filesDir, GUILD), GUILD);
        Call rp = calls.stream().filter(x -> x.method.equals("POST")).findFirst().orElse(null);
        check(rp != null && rp.payload().get("content").getAsString().equals("<@&100000000000000077>\n新版本上线")
            && rp.payload().getAsJsonObject("allowed_mentions").getAsJsonArray("roles").size() == 1, "公告频道：@ 常驻身份组");
        calls.clear();
        poster.send(poster.plan(PostDrafts.quick("今晚 8 点维护", PostDrafts.Mode.MESSAGE, ""), PostDrafts.Mode.MESSAGE, TEXT, filesDir, GUILD), GUILD);
        Call qp = calls.stream().filter(x -> x.method.equals("POST")).findFirst().orElse(null);
        check(qp != null && qp.payload().get("content").getAsString().equals("今晚 8 点维护") && qp.payload().getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty(), "text:：一行，不 @");

        // ---- 拒绝 ----
        planFails(poster, PostDrafts.parse("x"), PostDrafts.Mode.MESSAGE, FORUM, filesDir, "论坛频道不能直接发消息");
        planFails(poster, PostDrafts.parse("---\ntitle: t\n---\nx"), PostDrafts.Mode.FORUM, TEXT, filesDir, "文字频道不能开帖");
        planFails(poster, PostDrafts.parse("---\ntitle: t\ntag: 不存在\n---\nx"), PostDrafts.Mode.FORUM, FORUM, filesDir, "论坛没有这个标签");
        planFails(poster, PostDrafts.parse("---\ntags: 官方\n---\nx"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, "只有论坛新帖能带标签（main bd0c4a2）");
        try {
            poster.plan(PostDrafts.parse("---\ntitle: t\ntags: 整合包, 官方\n---\nx"), PostDrafts.Mode.FORUM, FORUM, filesDir, GUILD);
            check(false, "应拒绝：一个标签不存在");
        } catch (IOException e) {
            check(e.getMessage().equals("论坛里没有标签：官方"), "一个标签不存在就整帖拒绝：" + e.getMessage());
        }
        DraftPoster.Plan multi = poster.plan(PostDrafts.parse("---\ntitle: t\ntags: 整合包, t2, 更新\n---\nx"), PostDrafts.Mode.FORUM, FORUM, filesDir, GUILD);
        check(multi.tagIds().equals(List.of("t1", "t2")), "多个标签按名字或 id 解析并去重：" + multi.tagIds());
        calls.clear();
        poster.send(multi, GUILD);
        Call mt = calls.stream().filter(x -> x.method.equals("POST") && x.path.endsWith("/threads")).findFirst().orElse(null);
        check(mt != null && mt.payload().getAsJsonArray("applied_tags").size() == 2 && mt.payload().getAsJsonArray("applied_tags").get(1).getAsString().equals("t2"), "applied_tags 带上全部标签");
        DraftPoster.Plan fallback = poster.plan(PostDrafts.parse("---\ntitle: t\n---\nx"), PostDrafts.Mode.FORUM, FORUM, filesDir, GUILD);
        check(fallback.tagIds().equals(List.of("t1")) && String.join("\n", fallback.lines("x")).contains("自动用第一个"), "论坛要求标签而没写：自动用第一个");
        planFails(poster, PostDrafts.parse("---\nfiles: huge.zip\n---\nx"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, "附件超过 max-file-mb");
        planFails(poster, PostDrafts.parse("---\nfiles: missing.png\n---\nx"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, "附件不存在");
        planFails(poster, PostDrafts.parse("---\nfiles: outside.png\n---\nx"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, "posts/files 外面的文件");
        planFails(poster, PostDrafts.parse("---\nping: nobody\n---\nx"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, "不认识的通知对象");
        planFails(poster, PostDrafts.parse("x"), PostDrafts.Mode.MESSAGE, "100000000000000999", filesDir, "频道不存在（Discord 10003）");
        DraftPoster small = new DraftPoster(rest, 5_000, pings, "Amincraft");
        Files.write(filesDir.resolve("p1.bin"), new byte[3000]);
        Files.write(filesDir.resolve("p2.bin"), new byte[3000]);
        planFails(small, PostDrafts.parse("---\nfiles: p1.bin, p2.bin\n---\nx"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, "附件加起来超过一条消息的上限");
        DraftPoster big = new DraftPoster(rest, 500L << 20, pings, "Amincraft");
        check(big.plan(PostDrafts.parse("x"), PostDrafts.Mode.MESSAGE, TEXT, filesDir, GUILD).uploadLimit() == DiscordRest.uploadLimitForTier(0), "上限再受服务器加成等级限制（无加成约 10 MB）");
        check(DiscordRest.uploadLimitForTier(2) > DiscordRest.uploadLimitForTier(1) && DiscordRest.uploadLimitForTier(3) > DiscordRest.uploadLimitForTier(2), "加成等级越高上限越大");

        // ---- 2026-10-12 改帖（服主: 已经发出去的帖子要改一句）----
        PostDrafts.EditCmd ec = PostDrafts.parseEdit(new String[]{"edit", "https://discord.com/channels/" + GUILD + "/" + EDT, "更新公告-c", "--dry-run"});
        check(ec.error() == null && ec.channel().equals(EDT) && ec.messageId().equals(EDT) && ec.draft().equals("更新公告-c") && ec.dryRun(), "edit：论坛帖链接 = 帖子第一条消息");
        ec = PostDrafts.parseEdit(new String[]{"edit", "https://discord.com/channels/" + GUILD + "/" + TEXT + "/100000000000000555", "x.md"});
        check(ec.error() == null && ec.channel().equals(TEXT) && ec.messageId().equals("100000000000000555") && ec.draft().equals("x") && !ec.dryRun(), "edit：消息链接");
        ec = PostDrafts.parseEdit(new String[]{"edit", TEXT, "100000000000000555", "x"});
        check(ec.error() == null && ec.channel().equals(TEXT) && ec.messageId().equals("100000000000000555"), "edit：频道 id + 消息 id");
        check(PostDrafts.parseEdit(new String[]{"edit", "https://example.com/channels/1/2", "a"}).error() != null, "edit：别的网站的链接拒绝");
        check(PostDrafts.parseEdit(new String[]{"edit", TEXT, "a"}).error() != null, "edit：缺参数");
        check(PostDrafts.parseEdit(new String[]{"edit", "https://discord.com/channels/" + GUILD + "/" + EDT, "../x"}).error() != null, "edit：草稿名带路径拒绝");
        check(PostDrafts.parseEdit(new String[]{"edit"}).error() != null, "edit：没有参数给用法");
        PostDrafts.Draft ed = PostDrafts.parse("---\ntitle: 新标题\n---\n第一行\n新的一行");
        calls.clear();
        DraftPoster.Edit e = poster.planEdit(ed, EDT, EDT);
        String el = String.join("\n", e.lines("posts/c.md"));
        System.out.println(el);
        check(calls.stream().allMatch(x -> x.method.equals("GET")), "改帖试运行只有 GET");
        check(el.contains("－ 旧的一行") && el.contains("＋ 新的一行") && !el.contains("＋ 第一行") && el.contains("「旧标题」→「新标题」"), "改帖试运行：只列出改了的行和标题");
        calls.clear();
        poster.edit(ed, e);
        Call pm = calls.stream().filter(x -> x.method.equals("PATCH") && x.path.equals("/channels/" + EDT + "/messages/" + EDT)).findFirst().orElse(null);
        check(pm != null && pm.payload().get("content").getAsString().startsWith("**新标题**") && pm.payload().get("content").getAsString().endsWith("第一行\n新的一行")
            && pm.payload().getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty(), "改帖：PATCH 消息正文（和发帖时一样，纯文本的标题是粗体首行），不 @");
        Call pt = calls.stream().filter(x -> x.method.equals("PATCH") && x.path.equals("/channels/" + EDT)).findFirst().orElse(null);
        check(pt != null && pt.payload().get("name").getAsString().equals("新标题"), "改帖：标题不同就改帖子标题");
        check(calls.stream().noneMatch(x -> x.method.equals("POST")), "改帖不发新消息");
        calls.clear();
        poster.edit(PostDrafts.parse("---\ntitle: 旧标题\n---\n第一行\n新的一行"), poster.planEdit(PostDrafts.parse("---\ntitle: 旧标题\n---\n第一行\n新的一行"), EDT, EDT));
        check(calls.stream().noneMatch(x -> x.method.equals("PATCH") && x.path.equals("/channels/" + EDT)), "标题没变就不改帖子标题");
        calls.clear();
        PostDrafts.Draft ad = PostDrafts.parse("新");
        poster.edit(ad, poster.planEdit(ad, EDA, EDA));
        List<String> seq = calls.stream().filter(x -> x.method.equals("PATCH")).map(x -> x.path).toList();
        check(seq.equals(List.of("/channels/" + EDA + "/messages/" + EDA, "/channels/" + EDA, "/channels/" + EDA + "/messages/" + EDA)) && !edaArchived, "帖子已归档：先取消归档再改：" + seq);
        try { poster.planEdit(PostDrafts.parse("---\nping: everyone\n---\nx"), EDT, EDT); check(false, "改帖应拒绝 @"); }
        catch (IOException ex) { check(ex.getMessage().contains("ping"), "改帖不再 @ 人"); }
    }

    static void planFails(DraftPoster poster, PostDrafts.Draft d, PostDrafts.Mode mode, String channel, Path filesDir, String what) {
        int before = (int) calls.stream().filter(x -> !x.method.equals("GET")).count();
        try {
            poster.plan(d, mode, channel, filesDir, GUILD);
            check(false, "应拒绝：" + what);
        } catch (Exception e) {
            check(!String.valueOf(e.getMessage()).contains(TOKEN), "拒绝：" + what + "（" + e.getMessage() + "）");
        }
        check(calls.stream().filter(x -> !x.method.equals("GET")).count() == before, "拒绝时没有发出任何东西：" + what);
    }

    static void check(boolean ok, String what) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }
}
