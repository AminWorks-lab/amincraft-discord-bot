package cn.aminplay.discord;

import java.util.*;

/**
 * 2026-10-12 user (服主): 「让bot可以由你操控自动发帖发消息…发整合包帖子和公告帖子，也可以在频道里发公告」.
 * Pure part: a draft is a text file in plugins/AminPlayDiscord/posts/&lt;name&gt;.md with a small header, written by an admin
 * (or Claude through the panel file manager) and sent from the SERVER CONSOLE. No network, no Bukkit here.
 * <pre>
 * ---
 * target: announce        # 只在「/discordbot post 草稿 confirm」时用：频道别名（posts.aliases，内置 announce / modpack）或数字 id
 * mode: message           # 同上：message = 发一条消息；forum = 在论坛开新帖
 * title: Amincraft 整合包 1.2.6   # forum 必填，最多 100 字；message 时作为嵌入标题 / 纯文本的粗体首行
 * tags: 官方, 版本更新     # 可选，论坛标签名或 id，逗号分隔，最多 5 个，只有论坛新帖能带（旧写法 tag: 也认）
 * files: a.mrpack, b.png  # 可选，posts/files/ 里的文件名，最多 10 个，跟第一条消息一起发
 * ping: none              # none（默认）| everyone | 面板 key（config.yml roles.panel，订阅组或身份组）| role:&lt;身份组id&gt;
 * format: plain           # plain（默认，纯文本）| embed（嵌入卡片，和 /minmin 公告 一样）
 * color: #F2B33D          # 可选，嵌入颜色
 * image: b.png            # 可选，嵌入大图：https 链接，或 files 里的一个图片文件名
 * ---
 * 正文（Markdown；纯文本每条 2000 字、嵌入每条 4000 字，超过会按段落拆成多条，最多 10 条）
 * </pre>
 * 2026-10-12 bot-posting: console commands parsed by {@link #parseArgs}; format/ping/color/image, aliases and the size caps added.
 */
final class PostDrafts {
    private PostDrafts() { }

    enum Mode { MESSAGE, FORUM }
    record Draft(String target, Mode mode, String title, List<String> tags, List<String> files, String ping, boolean embed,
                 int color, String image, List<String> parts) {
        boolean everyone() { return ping.equals("everyone"); }
        int length() { return parts.stream().mapToInt(String::length).sum(); }
    }

    static final int MAX_CONTENT = 2000, MAX_EMBED = 4000, MAX_TITLE = 100, MAX_FILES = 10, MAX_PARTS = 10, MAX_TAGS = 5;
    /** 2026-10-12 bot-posting: a draft file larger than this is refused before parsing. */
    static final long MAX_DRAFT_BYTES = 128 * 1024;
    /** Room kept in the first plain-text part for the @ line. */
    static final int PING_RESERVE = 100;
    static final int DEFAULT_COLOR = 0xF2B33D;   // same gold as /minmin 公告

    /** Draft names: letters, digits, '-', '_' and CJK; no paths. */
    static boolean validName(String name) {
        return name != null && name.length() <= 64 && !name.isEmpty() && name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
    /** Attachment names: one plain file name inside posts/files/. */
    static boolean validFile(String name) {
        return name != null && !name.isEmpty() && name.length() <= 120 && !name.contains("/") && !name.contains("\\") && !name.contains("..")
            && !name.startsWith(".") && !name.contains(":") && name.codePoints().noneMatch(Character::isISOControl);
    }
    /** 2026-10-12 bot-posting: "整合包" or "整合包.md" → "整合包"; null when it is not a plain draft name (paths, "..", other extensions). */
    static String draftName(String arg) {
        if (arg == null) return null;
        String n = arg.endsWith(".md") ? arg.substring(0, arg.length() - 3) : arg;
        return validName(n) ? n : null;
    }

    static Draft parse(String text) {
        if (text == null) throw new IllegalArgumentException("草稿是空的");
        String t = text.replace("\r\n", "\n");
        if (t.startsWith("﻿")) t = t.substring(1);
        Map<String, String> head = new HashMap<>();
        String body = t;
        if (t.startsWith("---\n")) {
            int end = t.indexOf("\n---", 3);
            if (end < 0) throw new IllegalArgumentException("开头的 --- 没有闭合");
            for (String line : t.substring(4, Math.max(4, end)).split("\n")) {
                String l = line.strip();
                int hash = l.indexOf(" # ");   // 2026-10-12 bot-posting: "space # space" starts a comment, so "color: #F2B33D" and "第 #1 期" survive
                if (hash < 0 && l.endsWith(" #")) hash = l.length() - 2;
                if (hash >= 0) l = l.substring(0, hash).strip();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int colon = l.indexOf(':');
                if (colon <= 0) throw new IllegalArgumentException("看不懂这一行：" + line);
                head.put(l.substring(0, colon).strip().toLowerCase(Locale.ROOT), unquote(l.substring(colon + 1).strip()));
            }
            int nl = t.indexOf('\n', end + 1);
            body = nl < 0 ? "" : t.substring(nl + 1);
        }
        body = body.strip();
        String target = head.getOrDefault("target", "announce");
        if (!target.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("target 只能是频道别名（字母、数字、- _）或数字 id");
        Mode mode = switch (head.getOrDefault("mode", "message").toLowerCase(Locale.ROOT)) {
            case "message" -> Mode.MESSAGE;
            case "forum" -> Mode.FORUM;
            default -> throw new IllegalArgumentException("mode 只能是 message 或 forum");
        };
        String title = head.getOrDefault("title", "");
        if (mode == Mode.FORUM && title.isEmpty()) throw new IllegalArgumentException("论坛帖需要 title");
        if (title.codePointCount(0, title.length()) > MAX_TITLE) throw new IllegalArgumentException("title 最多 " + MAX_TITLE + " 字");
        List<String> files = new ArrayList<>();
        for (String f : head.getOrDefault("files", "").split(",")) {
            String n = unquote(f.strip());
            if (n.isEmpty()) continue;
            if (!validFile(n)) throw new IllegalArgumentException("附件名不合法（只能写 posts/files/ 里的文件名，不能带路径）：" + n);
            if (!files.contains(n)) files.add(n);
        }
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("附件最多 " + MAX_FILES + " 个");
        List<String> tags = new ArrayList<>();   // 2026-10-12 服主: 官方 + 版本更新/客户端 → several tags (merged from main bd0c4a2)
        for (String f : (head.getOrDefault("tags", "") + "," + head.getOrDefault("tag", "")).split(",")) {
            String n = unquote(f.strip());
            if (!n.isEmpty() && !tags.contains(n)) tags.add(n);
        }
        if (tags.size() > MAX_TAGS) throw new IllegalArgumentException("标签最多 " + MAX_TAGS + " 个");
        // bot-posting: the command (post / forum) decides the mode, so "tags on a plain message" is refused in DraftPoster.plan
        String ping = parsePing(head.getOrDefault("ping", "none"));
        boolean embed = switch (head.getOrDefault("format", head.containsKey("embed") ? (Boolean.parseBoolean(head.get("embed")) ? "embed" : "plain") : "plain").toLowerCase(Locale.ROOT)) {
            case "plain", "text" -> false;
            case "embed" -> true;
            default -> throw new IllegalArgumentException("format 只能是 plain 或 embed");
        };
        int color = DEFAULT_COLOR;
        String c = head.getOrDefault("color", "");
        if (!c.isEmpty()) {
            if (!c.matches("#?[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("color 要写成 #RRGGBB");
            color = Integer.parseInt(c.startsWith("#") ? c.substring(1) : c, 16);
        }
        String image = head.getOrDefault("image", "");
        if (!image.isEmpty()) {
            if (!embed) throw new IllegalArgumentException("image 只在 format: embed 时有用");
            if (!image.startsWith("https://") && !files.contains(image)) throw new IllegalArgumentException("image 要么是 https 链接，要么是 files 里的一个文件名");
        }
        if (body.isEmpty() && files.isEmpty() && !(embed && !title.isEmpty())) throw new IllegalArgumentException("正文和附件不能都为空");
        int max = embed ? MAX_EMBED : MAX_CONTENT - (ping.equals("none") ? 0 : PING_RESERVE);
        // plain text: the title becomes a bold first line of a normal message (forum titles are the thread name instead)
        String full = !embed && mode == Mode.MESSAGE && !title.isEmpty() ? "**" + title + "**" + (body.isEmpty() ? "" : "\n\n" + body) : body;
        List<String> parts = split(full, max);
        if (parts.size() > MAX_PARTS) throw new IllegalArgumentException("正文太长（超过 " + MAX_PARTS + " 条消息）");
        return new Draft(target, mode, title, List.copyOf(tags), List.copyOf(files), ping, embed, color, image, List.copyOf(parts));
    }

    /** 2026-10-12 bot-posting: "text:今晚维护" from the console → a one-line plain draft. "\n" in the text becomes a line break. */
    static Draft quick(String text, Mode mode, String title) {
        String body = text == null ? "" : text.replace("\\n", "\n").strip();
        if (body.isEmpty()) throw new IllegalArgumentException("text: 后面没有内容");
        if (body.length() > MAX_CONTENT) throw new IllegalArgumentException("text: 最多 " + MAX_CONTENT + " 字，长内容请写成草稿文件");
        if (mode == Mode.FORUM && (title == null || title.isEmpty())) throw new IllegalArgumentException("论坛帖需要标题，请写成草稿文件");
        return new Draft("-", mode, title == null ? "" : title, List.of(), List.of(), "none", false, DEFAULT_COLOR, "", List.of(body));
    }

    static String parsePing(String raw) {
        String p = raw.strip();
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("none") || lower.equals("no")) return "none";
        if (lower.equals("everyone") || lower.equals("@everyone")) return "everyone";
        if (lower.startsWith("role:")) {
            String id = p.substring(5).strip();
            if (!id.matches("\\d{15,25}")) throw new IllegalArgumentException("ping: role: 后面要写身份组的数字 id");
            return "role:" + id;
        }
        if (p.matches("[A-Za-z0-9_-]{1,32}")) return p;   // a key in roles.panel, checked against config when sending
        throw new IllegalArgumentException("ping 只能是 none、everyone、面板 key 或 role:<id>");
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) return v.substring(1, v.length() - 1);
        return v;
    }

    /** Splits at blank lines, then lines, then hard cuts; every part is 1..max chars. */
    static List<String> split(String body, int max) {
        List<String> out = new ArrayList<>();
        if (body.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\n\n")) {
            for (String piece : pieces(para, max)) {
                int extra = cur.isEmpty() ? 0 : 2;
                if (cur.length() + extra + piece.length() > max) { out.add(cur.toString()); cur.setLength(0); extra = 0; }
                if (extra > 0) cur.append("\n\n");
                cur.append(piece);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }
    private static List<String> pieces(String para, int max) {
        if (para.length() <= max) return List.of(para);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : para.split("\n")) {
            while (line.length() > max) { if (!cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); } out.add(line.substring(0, max)); line = line.substring(max); }
            if (cur.length() + (cur.isEmpty() ? 0 : 1) + line.length() > max) { out.add(cur.toString()); cur.setLength(0); }
            if (!cur.isEmpty()) cur.append('\n');
            cur.append(line);
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    // ================= 2026-10-12 bot-posting: console command + channel aliases =================

    /**
     * One parsed console command. kind: LIST (posts), HELP, SEND.
     * target == null → use the draft's own target/mode (legacy "post &lt;草稿&gt; [confirm]"); otherwise the command decides the mode.
     * draft == null → quickText is the message (text:…).
     */
    enum Kind { LIST, HELP, SEND }
    record Cmd(Kind kind, Mode mode, String target, String draft, String quickText, boolean dryRun, boolean again, String error) {
        static Cmd err(String e) { return new Cmd(Kind.HELP, null, null, null, null, false, false, e); }
    }

    static final String USAGE = String.join("\n",
        "/discordbot posts —— 列出 posts/ 里的草稿",
        "/discordbot post <频道id|别名> <草稿[.md]> [--dry-run] —— 在文字/公告频道发消息",
        "/discordbot post <频道id|别名> text:<一行文字> [--dry-run] —— 快速发一行（\\n 换行）",
        "/discordbot forum <论坛id|别名> <草稿[.md]> [--dry-run] —— 在论坛开新帖（草稿里要有 title）",
        "/discordbot post <草稿> [confirm] —— 按草稿里的 target/mode 预览，加 confirm 才发送",
        "同一个草稿已经发到同一个频道时会拒绝，确实要再发一次请加 --again。",
        "/discordbot edit <消息链接> <草稿> [--dry-run] —— 把机器人发过的一条消息（或论坛帖的第一条）改成草稿内容");

    /** args[0] is "post", "forum" or "posts". */
    static Cmd parseArgs(String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("posts")) return new Cmd(Kind.LIST, null, null, null, null, false, false, null);
        boolean dry = false, again = false, confirm = false;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--dry-run") || a.equalsIgnoreCase("--dryrun")) dry = true;
            else if (a.equalsIgnoreCase("--again")) again = true;
            else rest.add(a);
        }
        if (rest.isEmpty()) return new Cmd(Kind.HELP, null, null, null, null, false, false, null);
        Mode mode = sub.equals("forum") ? Mode.FORUM : Mode.MESSAGE;
        // legacy: post <草稿> [confirm]
        if (sub.equals("post") && (rest.size() == 1 || (rest.size() == 2 && rest.get(1).equalsIgnoreCase("confirm"))) && !rest.get(0).startsWith("text:")) {
            confirm = rest.size() == 2;
            String n = draftName(rest.get(0));
            if (n == null) return Cmd.err("草稿名只能有字母、数字、- 和 _（不能带路径）");
            return new Cmd(Kind.SEND, null, null, n, null, dry || !confirm, again, null);
        }
        String target = rest.get(0);
        if (!target.matches("[A-Za-z0-9_-]{1,32}")) return Cmd.err("频道要写数字 id 或 posts.aliases 里的别名");
        if (rest.size() < 2) return Cmd.err("缺少草稿名或 text:…");
        if (rest.get(1).startsWith("text:")) {
            String text = String.join(" ", rest.subList(1, rest.size())).substring(5);
            if (mode == Mode.FORUM) return Cmd.err("论坛帖需要标题，请把内容写成草稿文件再用 forum 发");
            return new Cmd(Kind.SEND, mode, target, null, text, dry, again, null);
        }
        if (rest.size() > 2) return Cmd.err("参数太多：草稿名里不能有空格");
        String n = draftName(rest.get(1));
        if (n == null) return Cmd.err("草稿名只能有字母、数字、- 和 _（不能带路径，文件要放在 plugins/AminPlayDiscord/posts/ 里）");
        return new Cmd(Kind.SEND, mode, target, n, null, dry, again, null);
    }

    /**
     * 2026-10-12 (服主: 已经发出去的帖子要改一句): "edit &lt;消息链接&gt; &lt;草稿&gt;" or "edit &lt;频道/帖子id&gt; &lt;消息id&gt; &lt;草稿&gt;", plus --dry-run.
     * A forum post link (…/channels/&lt;服务器&gt;/&lt;帖子&gt;) means the post's first message, which has the thread's id.
     */
    record EditCmd(String channel, String messageId, String draft, boolean dryRun, String error) {
        static EditCmd err(String e) { return new EditCmd(null, null, null, false, e); }
    }
    static final String EDIT_USAGE = "/discordbot edit <消息链接> <草稿> [--dry-run] —— 把机器人发过的一条消息（或论坛帖的第一条）改成草稿内容；也可以写 edit <频道/帖子id> <消息id> <草稿>";

    static EditCmd parseEdit(String[] args) {
        boolean dry = false;
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equalsIgnoreCase("--dry-run") || a.equalsIgnoreCase("--dryrun")) dry = true;
            else rest.add(a);
        }
        if (rest.isEmpty()) return EditCmd.err(EDIT_USAGE);
        String ch, msg;
        int next;
        if (rest.get(0).startsWith("https://")) {
            String[] p = rest.get(0).replaceFirst("^https://(ptb\\.|canary\\.)?discord(app)?\\.com/channels/", "").split("/");
            if (p.length == 2 && p[1].matches("\\d{15,25}")) { ch = p[1]; msg = p[1]; }
            else if (p.length == 3 && p[1].matches("\\d{15,25}") && p[2].matches("\\d{15,25}")) { ch = p[1]; msg = p[2]; }
            else return EditCmd.err("看不懂这个消息链接（要 https://discord.com/channels/… 的链接）");
            next = 1;
        } else {
            if (rest.size() < 3) return EditCmd.err(EDIT_USAGE);
            ch = rest.get(0); msg = rest.get(1); next = 2;
            if (!ch.matches("\\d{15,25}") || !msg.matches("\\d{15,25}")) return EditCmd.err("频道和消息都要写数字 id");
        }
        if (rest.size() != next + 1) return EditCmd.err("缺少草稿名，或参数太多（草稿名里不能有空格）");
        String n = draftName(rest.get(next));
        if (n == null) return EditCmd.err("草稿名只能有字母、数字、- 和 _（文件要放在 plugins/AminPlayDiscord/posts/ 里）");
        return new EditCmd(ch, msg, n, dry, null);
    }

    /** 2026-10-12 edit: lines only in a (removed, "－") and only in b (added, "＋"), for the dry-run. */
    static List<String> changedLines(String a, String b) {
        List<String> out = new ArrayList<>();
        List<String> la = Arrays.asList(a.split("\n", -1)), lb = Arrays.asList(b.split("\n", -1));
        for (String l : la) if (!l.isBlank() && !lb.contains(l)) out.add("－ " + l);
        for (String l : lb) if (!l.isBlank() && !la.contains(l)) out.add("＋ " + l);
        return out;
    }

    /**
     * Alias or id → channel id. Built-ins: announce = channels.announce, modpack = posts.modpack-forum; posts.aliases overrides them.
     * Returns null when the alias is unknown or points at an empty setting.
     */
    static String resolveTarget(String target, Map<String, String> aliases, String announce, String modpackForum) {
        if (target == null) return null;
        if (target.matches("\\d{15,25}")) return target;
        String v = aliases.get(target);
        if (v == null) v = aliases.get(target.toLowerCase(Locale.ROOT));
        if (v == null) v = switch (target.toLowerCase(Locale.ROOT)) {
            case "announce" -> announce;
            case "modpack", "modpack-forum" -> modpackForum;
            default -> null;
        };
        if (v == null) return null;
        v = v.strip();
        return v.matches("\\d{15,25}") ? v : null;
    }

    /** One-line preview for the console list. */
    static String describe(String name, Draft d, String resolvedTarget) {
        return "草稿 " + name + "：" + (d.mode() == Mode.FORUM ? "论坛开新帖「" + d.title() + "」" : "发消息") + " → " + d.target()
            + (resolvedTarget == null ? "（未设置频道）" : "（" + resolvedTarget + "）") + "，" + (d.embed() ? "嵌入" : "纯文本") + "，正文 " + d.parts().size() + " 条"
            + (d.tags().isEmpty() ? "" : "，标签 " + String.join("、", d.tags()))
            + (d.files().isEmpty() ? "" : "，附件 " + String.join("、", d.files())) + (d.ping().equals("none") ? "" : "，@" + d.ping());
    }

    /** 2026-10-12 bot-posting: reads posts/&lt;name&gt;.md; refuses links/paths out of posts/ and files over {@link PostDrafts#MAX_DRAFT_BYTES}. */
    static Draft read(java.nio.file.Path dir, String name) throws java.io.IOException {
        if (!validName(name)) throw new java.io.IOException("草稿名只能有字母、数字、- 和 _");
        java.nio.file.Path base = dir.toAbsolutePath().normalize();
        java.nio.file.Path file = base.resolve(name + ".md").normalize();
        if (!file.startsWith(base) || !java.nio.file.Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.NoSuchFileException(name + ".md");
        if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new java.io.IOException("posts/" + name + ".md 不是普通文件");
        if (java.nio.file.Files.size(file) > MAX_DRAFT_BYTES) throw new java.io.IOException("草稿太大（上限 " + (MAX_DRAFT_BYTES >> 10) + " KB）");
        try {
            return parse(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new java.io.IOException(ex.getMessage());
        }
    }

    /** sent.log line: time \t channel \t message id \t draft (tabs/newlines stripped from every field). */
    static String logLine(String time, String channel, String messageId, String draft) {
        return String.join("\t", clean(time), clean(channel), clean(messageId), clean(draft));
    }
    /** True when sent.log already has this draft sent to this channel. */
    static boolean alreadySent(List<String> logLines, String channel, String draft) {
        for (String l : logLines) {
            String[] f = l.split("\t");
            if (f.length >= 4 && f[1].equals(channel) && f[3].equals(draft)) return true;
        }
        return false;
    }
    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
}
