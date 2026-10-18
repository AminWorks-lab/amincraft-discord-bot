package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-12: sends a {@link PostDrafts.Draft} (worker thread).
 * 2026-10-12 bot-posting: split into {@link #plan} (read-only: GET channel + guild, checks files and sizes; used by --dry-run)
 * and {@link #send}; files go with the FIRST message (forum: inside the thread's starter message), @ goes through {@link Pings}
 * (same Notifier flow as /minmin 公告), optional embed format.
 */
final class DraftPoster {
    /** How the @ of a post is prepared and cleaned up; the plugin wires it to Notifier (subscription groups use a temporary role). */
    interface Pings {
        /** Human-readable for the dry-run, e.g. "@everyone" or "通知组「MC玩家」（12 人）"; throws when the ping is unknown. */
        String describe(String ping) throws IOException;
        Notifier.Mention prepare(String ping) throws Exception;
        /** After sending: messagePath = /channels/x/messages/y (null = nothing was sent), newContent = content once the @ is turned into text. */
        void finish(Notifier.Mention m, String messagePath, String newContent);
    }

    interface RoleResolver { String resolve(BotConfig.PanelRole entry) throws Exception; }

    /**
     * The same @ choices as /minmin 公告: everyone, a roles.panel entry (subscribe = temporary role via Notifier, role = the real role)
     * or role:&lt;id&gt;. guild-id must be set for subscription groups.
     */
    static Pings standardPings(BotConfig cfg, Notifier notifier, Subscriptions subs, RoleResolver roles) {
        return new Pings() {
            @Override public String describe(String ping) throws IOException {
                if (ping.equals("everyone")) return "@everyone（所有人）";
                if (ping.startsWith("role:")) return "身份组 <@&" + ping.substring(5) + ">";
                BotConfig.PanelRole e = entry(ping);
                return e.subscribe() ? "通知组「" + e.name() + "」（" + subs.count(e.key()) + " 人，临时身份组 15 秒后自动删除）" : "身份组「" + e.name() + "」";
            }
            @Override public Notifier.Mention prepare(String ping) throws Exception {
                if (ping.equals("everyone")) return Notifier.everyone();
                if (ping.startsWith("role:")) return Notifier.role(ping.substring(5));
                BotConfig.PanelRole e = entry(ping);
                if (!e.subscribe()) return Notifier.role(roles.resolve(e));
                if (cfg.guildId.isEmpty()) throw new IOException("还没设置 discord.guild-id，不能 @ 通知组");
                return notifier.prepareSubscription(cfg.guildId, e, x -> { });
            }
            @Override public void finish(Notifier.Mention m, String messagePath, String newContent) {
                if (m.tempRoleId() != null) notifier.finish(cfg.guildId, m, messagePath, newContent);
            }
            private BotConfig.PanelRole entry(String key) throws IOException {
                BotConfig.PanelRole e = cfg.entry(key);
                if (e == null) throw new IOException("找不到通知对象「" + key + "」（ping 要写 none、everyone、role:<id> 或 config.yml roles.panel 里的 key）");
                return e;
            }
        };
    }

    record FileInfo(String name, Path path, long bytes) { }
    record Plan(PostDrafts.Draft draft, PostDrafts.Mode mode, String channel, String channelName, int channelType,
                List<String> tagIds, List<FileInfo> files, long uploadLimit, String pingText) {
        /** The dry-run text; never contains the token (nothing here knows it). */
        List<String> lines(String source) {
            List<String> out = new ArrayList<>();
            out.add("【试运行，不会发送】来源：" + source);
            out.add("目标：#" + channelName + "（" + channel + "，" + typeName(channelType) + "）");
            out.add(mode == PostDrafts.Mode.FORUM ? "方式：论坛开新帖「" + draft.title() + "」" + (tagIds.isEmpty() ? "" : "，标签 " + (draft.tags().isEmpty() ? tagIds.get(0) + "（论坛要求选标签，自动用第一个）" : String.join("、", draft.tags()))) : "方式：发消息"
                + (draft.title().isEmpty() ? "" : "，标题「" + draft.title() + "」"));
            out.add("格式：" + (draft.embed() ? "嵌入卡片" : "纯文本") + "，正文 " + draft.length() + " 字，分 " + Math.max(1, draft.parts().size()) + " 条");
            out.add("@：" + pingText);
            long total = files.stream().mapToLong(FileInfo::bytes).sum();
            out.add(files.isEmpty() ? "附件：无" : "附件：" + String.join("、", files.stream().map(f -> f.name() + "（" + mb(f.bytes()) + "）").toList())
                + "，共 " + mb(total) + "，上限 " + mb(uploadLimit) + "/条");
            String first = draft.parts().isEmpty() ? "" : draft.parts().get(0);
            out.add("开头：" + BotRules.truncate(first.replace("\n", " ⏎ "), 120));
            return out;
        }
    }

    private final DiscordRest rest;
    private final long maxFileBytes;
    private final Pings pings;
    private final String footer;

    DraftPoster(DiscordRest rest, long maxFileBytes, Pings pings, String footer) {
        this.rest = rest; this.maxFileBytes = maxFileBytes; this.pings = pings; this.footer = footer;
    }

    /** Read-only checks. mode = what the command asked for (null = the draft's own mode). */
    Plan plan(PostDrafts.Draft d, PostDrafts.Mode mode, String channel, Path filesDir, String guildId) throws IOException {
        if (mode == null) mode = d.mode();
        if (mode == PostDrafts.Mode.FORUM && d.title().isEmpty()) throw new IOException("论坛帖需要 title");
        long limit = Math.min(maxFileBytes, guildId == null || guildId.isEmpty() ? DiscordRest.uploadLimitForTier(0) : rest.uploadLimit(guildId));
        List<FileInfo> files = new ArrayList<>();
        Path base = filesDir.toAbsolutePath().normalize();
        long total = 0;
        for (String n : d.files()) {
            if (!PostDrafts.validFile(n)) throw new IOException("附件名不合法：" + n);
            Path f = base.resolve(n).normalize();
            if (!f.startsWith(base) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) throw new IOException("找不到附件 posts/files/" + n);
            long size = Files.size(f);
            if (size > limit) throw new IOException("附件太大：" + n + "（" + mb(size) + "，上限 " + mb(limit) + "，可以改发下载链接）");
            total += size;
            files.add(new FileInfo(n, f, size));
        }
        if (total > limit) throw new IOException("附件加起来 " + mb(total) + "，超过一条消息的上限 " + mb(limit) + "，请分成两个草稿");
        JsonObject ch = rest.get("/channels/" + channel).getAsJsonObject();
        int type = ch.has("type") ? ch.get("type").getAsInt() : 0;
        String name = ch.has("name") && !ch.get("name").isJsonNull() ? ch.get("name").getAsString() : channel;
        boolean forum = type == 15 || type == 16;
        List<String> tags = List.of();
        if (mode == PostDrafts.Mode.FORUM) {
            if (!forum) throw new IOException("#" + name + " 不是论坛频道，不能开新帖（请用 post 发消息）");
            tags = resolveTags(ch, d.tags());   // 2026-10-12 (main bd0c4a2): every tag must exist; several tags allowed
            boolean requireTag = ch.has("flags") && (ch.get("flags").getAsInt() & 16) != 0;
            if (tags.isEmpty() && requireTag) { String first = ReplayPoster.pickTag(ch, ""); if (first != null) tags = List.of(first); }
        } else {
            if (!d.tags().isEmpty()) throw new IOException("只有论坛新帖能带标签（用 /discordbot forum，或去掉 tags）");
            if (forum) throw new IOException("#" + name + " 是论坛频道，请用 /discordbot forum 开新帖，或填某个帖子的 id");
            if (type != 0 && type != 5 && type != 10 && type != 11 && type != 12) throw new IOException("#" + name + " 不能发消息（频道类型 " + type + "）");
        }
        String pingText = d.ping().equals("none") ? "无" : pings.describe(d.ping());
        return new Plan(d, mode, channel, name, type, List.copyOf(tags), List.copyOf(files), limit, pingText);
    }

    record Sent(String link, String channel, String messageId) { }

    /** Sends a checked plan. Returns the link of the first message; the @ is cleaned up (Notifier.finish) on this thread. */
    Sent send(Plan p, String guildId) throws Exception {
        PostDrafts.Draft d = p.draft();
        List<Path> files = p.files().stream().map(FileInfo::path).toList();
        List<String> parts = d.parts().isEmpty() ? List.of("") : d.parts();
        Notifier.Mention m = d.ping().equals("none") ? Notifier.Mention.none() : pings.prepare(d.ping());
        String where = p.channel(), firstId = null, firstPath = null, firstPlain = null;
        try {
            JsonObject first = message(d, parts.get(0), 0, parts.size(), m);
            if (p.mode() == PostDrafts.Mode.FORUM) {
                JsonObject body = new JsonObject();
                body.addProperty("name", BotRules.truncate(d.title(), PostDrafts.MAX_TITLE));
                body.addProperty("auto_archive_duration", 10080);
                body.add("message", first);
                if (!p.tagIds().isEmpty()) { JsonArray a = new JsonArray(); p.tagIds().forEach(a::add); body.add("applied_tags", a); }
                JsonElement r = files.isEmpty() ? rest.post("/channels/" + p.channel() + "/threads", body)
                    : rest.postFiles("/channels/" + p.channel() + "/threads", body, files, "message");
                where = r.getAsJsonObject().get("id").getAsString();
                firstId = where;   // a forum thread's starter message has the thread's id
            } else {
                firstId = post(where, first, files);
            }
            firstPath = "/channels/" + where + "/messages/" + firstId;
            firstPlain = d.embed() ? m.plainText() : join(m.plainText(), parts.get(0));
            for (int i = 1; i < parts.size(); i++) post(where, message(d, parts.get(i), i, parts.size(), Notifier.Mention.none()), List.of());
        } finally {
            pings.finish(m, firstPath, firstPlain);   // no-op unless a temporary role was made
        }
        String link = "https://discord.com/channels/" + (guildId == null || guildId.isEmpty() ? "@me" : guildId) + "/" + where
            + (p.mode() == PostDrafts.Mode.FORUM ? "" : "/" + firstId);
        return new Sent(link, where, firstId);
    }

    /**
     * 2026-10-12 (服主: 已发的帖子要改一句): one message the bot sent before gets the draft's text. Only one-part drafts; the
     * original's attachments, tags and @ stay (no new @ is sent). A forum post's first message (id = thread id) also gets the
     * draft's title when it differs.
     */
    record Edit(String channel, String messageId, String oldText, String newText, String oldTitle, String newTitle) {
        List<String> lines(String source) {
            List<String> out = new ArrayList<>();
            out.add("【试运行，不会修改】来源：" + source);
            out.add("要改的消息：/channels/" + channel + "/messages/" + messageId + "，正文 " + oldText.length() + " 字 → " + newText.length() + " 字");
            if (oldTitle != null && !newTitle.isEmpty() && !newTitle.equals(oldTitle)) out.add("帖子标题：「" + oldTitle + "」→「" + newTitle + "」");
            List<String> diff = PostDrafts.changedLines(oldText, newText);
            if (diff.isEmpty()) out.add("正文没有变化");
            for (String l : diff.subList(0, Math.min(diff.size(), 12))) out.add(BotRules.truncate(l, 200));
            if (diff.size() > 12) out.add("…还有 " + (diff.size() - 12) + " 行");
            return out;
        }
    }

    Edit planEdit(PostDrafts.Draft d, String channel, String messageId) throws IOException {
        if (d.parts().size() > 1) throw new IOException("草稿要拆成 " + d.parts().size() + " 条才发得下，编辑只能改成一条（每条 " + (d.embed() ? PostDrafts.MAX_EMBED : PostDrafts.MAX_CONTENT) + " 字以内）");
        if (!d.ping().equals("none")) throw new IOException("编辑不会再 @ 人，请把草稿里的 ping 改成 none");
        JsonObject msg = rest.get("/channels/" + channel + "/messages/" + messageId).getAsJsonObject();
        String old = msg.has("content") && !msg.get("content").isJsonNull() ? msg.get("content").getAsString() : "";
        String oldTitle = null;
        if (channel.equals(messageId)) {
            JsonObject ch = rest.get("/channels/" + channel).getAsJsonObject();
            oldTitle = ch.has("name") && !ch.get("name").isJsonNull() ? ch.get("name").getAsString() : null;
        }
        return new Edit(channel, messageId, old, d.parts().isEmpty() ? "" : d.parts().get(0), oldTitle, d.title());
    }

    void edit(PostDrafts.Draft d, Edit e) throws IOException {
        JsonObject m = message(d, e.newText(), 0, 1, Notifier.Mention.none());
        try {
            rest.patch("/channels/" + e.channel() + "/messages/" + e.messageId(), m);
        } catch (DiscordRest.DiscordException ex) {
            if (ex.code != 50083) throw ex;   // forum posts get archived after a week of quiet → unarchive once and retry
            JsonObject un = new JsonObject();
            un.addProperty("archived", false);
            rest.patch("/channels/" + e.channel(), un);
            rest.patch("/channels/" + e.channel() + "/messages/" + e.messageId(), m);
        }
        if (e.oldTitle() != null && !d.title().isEmpty() && !d.title().equals(e.oldTitle())) {
            JsonObject b = new JsonObject();
            b.addProperty("name", BotRules.truncate(d.title(), PostDrafts.MAX_TITLE));
            rest.patch("/channels/" + e.channel(), b);
        }
    }

    private String post(String channel, JsonObject m, List<Path> files) throws IOException {
        JsonElement r = files.isEmpty() ? rest.post("/channels/" + channel + "/messages", m) : rest.postFiles("/channels/" + channel + "/messages", m, files);
        return r.getAsJsonObject().get("id").getAsString();
    }

    /** One message of the post. Only the first one carries the @ (and the files, added by postFiles). */
    JsonObject message(PostDrafts.Draft d, String text, int index, int count, Notifier.Mention m) {
        JsonObject msg;
        if (d.embed()) {
            JsonObject e = new JsonObject();
            if (index == 0 && !d.title().isEmpty()) e.addProperty("title", BotRules.truncate(d.title(), 256));
            if (!text.isEmpty()) e.addProperty("description", text);
            e.addProperty("color", d.color());
            if (index == 0 && !d.image().isEmpty()) {
                JsonObject img = new JsonObject();
                img.addProperty("url", d.image().startsWith("https://") ? d.image() : "attachment://" + d.image());
                e.add("image", img);
            }
            if (index == count - 1) {
                e.addProperty("timestamp", Instant.now().toString());
                JsonObject f = new JsonObject();
                f.addProperty("text", footer);
                e.add("footer", f);
            }
            msg = Interactions.message(index == 0 ? m.content() : null, e);
        } else {
            String content = index == 0 ? join(m.content(), text) : text;
            msg = Interactions.message(content == null || content.isEmpty() ? null : content, null);
        }
        msg.add("allowed_mentions", index == 0 && m.allowed() != null ? m.allowed() : Interactions.noMentions());
        return msg;
    }

    private static String join(String mention, String text) {
        if (mention == null || mention.isEmpty()) return text;
        return text.isEmpty() ? mention : mention + "\n" + text;
    }
    /** 2026-10-12 (main bd0c4a2): every wanted tag (name or id) must exist on the forum, otherwise nothing is posted. */
    static List<String> resolveTags(JsonObject forum, List<String> wanted) throws IOException {
        List<String> out = new ArrayList<>();
        for (String w : wanted) {
            String found = null;
            if (forum.has("available_tags")) for (JsonElement t : forum.getAsJsonArray("available_tags")) {
                JsonObject o = t.getAsJsonObject();
                if (w.equals(o.get("id").getAsString()) || w.equals(o.get("name").getAsString())) { found = o.get("id").getAsString(); break; }
            }
            if (found == null) throw new IOException("论坛里没有标签：" + w);
            if (!out.contains(found)) out.add(found);
        }
        return out;
    }
    static String typeName(int type) {
        return switch (type) {
            case 0 -> "文字频道";
            case 5 -> "公告频道";
            case 10, 11, 12 -> "帖子/子区";
            case 15 -> "论坛";
            case 16 -> "媒体论坛";
            default -> "类型 " + type;
        };
    }
    static String mb(long bytes) {
        return bytes >= 1 << 20 ? String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0) : String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }
}
