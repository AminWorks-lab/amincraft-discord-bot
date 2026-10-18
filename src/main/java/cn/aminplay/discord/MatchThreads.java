package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 2026-10-15 match-chat：一局小游戏的 Discord 子区（契约 docs/plans/2026-10-15-match-chat.md 第 5 节）。
 * <ul>
 *   <li>open → 公开子区（type 11），首条消息 @ 有 Discord 绑定且没有用 /minmin 隐私 退出的成员；每个私密房间 → 私密子区（type 12，invitable:false）并逐个加成员</li>
 *   <li>rooms → 私密子区补加新成员（不移除）</li>
 *   <li>chat → 「**名字**: 文字」发到对应子区（转义 markdown，不 @ 任何人）</li>
 *   <li>close → 发结果，延迟后锁定并归档</li>
 *   <li>子区里的 Discord 消息 → MatchChatApi.post</li>
 * </ul>
 * 所有 REST 都在 {@link Host#queue}（插件的单线程聊天队列）里执行，主线程只复制事件；429 限速由 {@link DiscordRest} 处理。
 * 子区映射只在内存里：机器人重启后旧子区不再转发、不再自动归档（Discord 的 60 分钟自动归档仍然有效）。
 * 子区名和消息只用游戏名、场地名、房间名、玩家名和结果，不写服务器地址/域名/服主。
 */
final class MatchThreads {
    /** match-chat: 段的快照。 */
    record Settings(boolean enabled, String channel, boolean mention, boolean privateRooms, Set<String> games, int closeDelaySeconds, ZoneId zone) {
        boolean active() {
            return enabled && channel != null && !channel.isEmpty();
        }

        boolean allowsGame(String gameId) {
            return games == null || games.isEmpty() || games.contains(gameId);
        }

        static Settings off() {
            return new Settings(false, "", true, true, Set.of(), 60, ZoneId.of("Asia/Tokyo"));
        }
    }

    interface Host {
        DiscordRest rest();
        Settings settings();
        boolean optedOut(String discordId);
        /** null = MiniGameHub 不在。 */
        HubMatchChat hub();
        /** 单线程聊天队列（REST）。 */
        void queue(Runnable task);
        /** Discord → 游戏（MatchChatApi.post），不占聊天队列。 */
        void inbound(Runnable task);
        /** 若干秒后执行（实现里应再交给 queue）。 */
        void later(long seconds, Runnable task);
    }

    static final int MAX_MATCHES = 64;
    static final int MAX_MENTIONS = 100;   // Discord allowed_mentions.users 上限
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 一个房间对应的子区。 */
    static final class Target {
        final String match, room;
        final boolean priv;
        final String thread;
        final Set<String> added = new LinkedHashSet<>();

        Target(String match, String room, boolean priv, String thread) {
            this.match = match;
            this.room = room;
            this.priv = priv;
            this.thread = thread;
        }
    }

    private static final class MatchState {
        final String game, arena, time;
        final Map<String, Target> rooms = new LinkedHashMap<>();
        final Set<String> failed = new LinkedHashSet<>();   // 建子区失败的房间：同一局不反复重试

        MatchState(String game, String arena, String time) {
            this.game = game;
            this.arena = arena;
            this.time = time;
        }
    }

    private final Host host;
    private final Logger log;
    private final Supplier<Instant> clock;
    /** 只在 queue 线程里改（加锁是为了测试和 status 读取）。 */
    private final Map<String, MatchState> matches = new LinkedHashMap<>();
    /** 子区 id → 房间（Gateway 线程读）。 */
    private final Map<String, Target> byThread = new ConcurrentHashMap<>();

    MatchThreads(Host host, Logger log) {
        this(host, log, Instant::now);
    }

    MatchThreads(Host host, Logger log, Supplier<Instant> clock) {
        this.host = host;
        this.log = log;
        this.clock = clock;
    }

    // ================= MiniGameHub 事件（主线程） =================

    /** MatchChatApi 的 sink：主线程调用，只复制后排队，不阻塞。 */
    void onEvent(Map<String, Object> e) {
        if (e == null) return;
        String type = String.valueOf(e.get("type"));
        if (!host.settings().active() && !"close".equals(type)) return;
        Map<String, Object> copy = deepCopy(e);
        host.queue(() -> handle(copy));
    }

    void handle(Map<String, Object> e) {
        try {
            Settings s = host.settings();
            switch (String.valueOf(e.get("type"))) {
                case "open" -> { if (s.active()) open(e, s); }
                case "rooms" -> { if (s.active()) rooms(e, s); }
                case "chat" -> { if (s.active()) chat(e); }
                case "close" -> close(e, s);
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "对局子区处理事件出错", ex);
        }
    }

    private void open(Map<String, Object> e, Settings s) {
        String match = str(e.get("match"));
        if (match.isEmpty()) return;
        if (!s.allowsGame(str(e.get("gameId")))) return;
        MatchState st;
        synchronized (matches) {
            if (matches.containsKey(match)) return;
            String game = str(e.get("game"));
            if (game.isEmpty()) game = str(e.get("gameId"));
            st = new MatchState(game, str(e.get("arena")), HHMM.format(clock.get().atZone(s.zone())));
            matches.put(match, st);
            evictOld();
        }
        for (Map<String, Object> room : list(e.get("rooms"))) ensureRoom(match, st, room, s);
    }

    private void rooms(Map<String, Object> e, Settings s) {
        String match = str(e.get("match"));
        MatchState st;
        synchronized (matches) {
            st = matches.get(match);
        }
        if (st == null) return;
        for (Map<String, Object> room : list(e.get("rooms"))) ensureRoom(match, st, room, s);
    }

    private void chat(Map<String, Object> e) {
        Target t = target(str(e.get("match")), str(e.get("room")));
        if (t == null) return;
        String text = clean(str(e.get("text")), 256);
        if (text.isEmpty()) return;
        String name = clean(str(e.get("name")), 32);
        send(t.thread, "**" + md(name.isEmpty() ? "?" : name) + "**: " + md(text), null);
    }

    private void close(Map<String, Object> e, Settings s) {
        String match = str(e.get("match"));
        MatchState st;
        synchronized (matches) {
            st = matches.remove(match);
        }
        if (st == null) return;
        List<Target> targets = new ArrayList<>(st.rooms.values());
        for (Target t : targets) byThread.remove(t.thread);
        String result = clean(str(e.get("result")), 300);
        String line = "🏁 对局结束" + (result.isEmpty() ? "" : "：" + md(result));
        for (Target t : targets) send(t.thread, line, null);
        if (targets.isEmpty()) return;
        long delay = Math.max(0, s.closeDelaySeconds());
        host.later(delay, () -> archive(targets));
    }

    void archive(List<Target> targets) {
        JsonObject body = new JsonObject();
        body.addProperty("locked", true);
        body.addProperty("archived", true);
        for (Target t : targets) {
            try {
                host.rest().patch("/channels/" + t.thread, body);
            } catch (DiscordRest.DiscordException ex) {
                if (ex.code != 10003) log.warning("对局子区归档失败：" + ex.friendly());
            } catch (IOException ex) {
                log.log(Level.FINE, "对局子区归档失败", ex);
            }
        }
    }

    // ================= 子区 =================

    private void ensureRoom(String match, MatchState st, Map<String, Object> room, Settings s) {
        String id = str(room.get("room"));
        if (id.isEmpty()) return;
        boolean priv = Boolean.TRUE.equals(room.get("private"));
        List<Map<String, Object>> members = list(room.get("members"));
        Target t = st.rooms.get(id);
        if (t != null) {
            if (t.priv) addMembers(t, members);
            return;
        }
        if (priv && !s.privateRooms()) return;
        if (st.failed.contains(id)) return;
        String roomName = clean(str(room.get("name")), 40);
        String name = threadName(st.game, priv ? (roomName.isEmpty() ? id : roomName) : st.arena, st.time);
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("type", priv ? 12 : 11);
        body.addProperty("auto_archive_duration", 60);
        if (priv) body.addProperty("invitable", false);
        String thread;
        try {
            thread = host.rest().post("/channels/" + s.channel() + "/threads", body).getAsJsonObject().get("id").getAsString();
        } catch (DiscordRest.DiscordException ex) {
            st.failed.add(id);
            log.warning("开对局子区失败（" + name + "）：" + ex.friendly() + "（match-chat.channel 要是文字频道，机器人要有「创建公开/私密子区」「在子区发消息」权限）");
            return;
        } catch (IOException | RuntimeException ex) {
            st.failed.add(id);
            log.warning("开对局子区失败（" + name + "）：" + ex.getMessage());
            return;
        }
        t = new Target(match, id, priv, thread);
        st.rooms.put(id, t);
        byThread.put(thread, t);
        if (priv) {
            send(thread, "🔒 **" + md(roomName.isEmpty() ? id : roomName) + "** 私密频道：只有这个房间的成员能看到。在这里发言会转发给游戏里的同房间成员。", null);
            addMembers(t, members);
        } else {
            List<String> ids = s.mention() ? mentionable(members) : List.of();
            StringBuilder sb = new StringBuilder("🎮 **").append(md(st.game)).append("**");
            if (!st.arena.isEmpty()) sb.append(" · ").append(md(clean(st.arena, 40)));
            sb.append(" 开始了！在这里发言会转发到游戏里的全场聊天（只限本局玩家）。");
            if (!ids.isEmpty()) {
                sb.append("\n");
                for (String u : ids) sb.append("<@").append(u).append("> ");
            }
            JsonArray users = new JsonArray();
            ids.forEach(users::add);
            JsonObject allowed = Interactions.noMentions();
            allowed.add("users", users);
            send(thread, sb.toString().trim(), allowed);
        }
    }

    private void addMembers(Target t, List<Map<String, Object>> members) {
        for (String u : mentionable(members)) {
            if (t.added.contains(u)) continue;
            t.added.add(u);   // 失败也记下，不反复请求
            try {
                host.rest().put("/channels/" + t.thread + "/thread-members/" + u, null);
            } catch (DiscordRest.DiscordException ex) {
                if (ex.code != 10007 && ex.code != 10013) log.warning("把成员加进私密子区失败：" + ex.friendly());
            } catch (IOException ex) {
                log.log(Level.FINE, "把成员加进私密子区失败", ex);
            }
        }
    }

    /** 有 Discord 绑定、id 看起来正常、没有退出隐私的成员，去重，最多 100 个。 */
    private List<String> mentionable(List<Map<String, Object>> members) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> m : members) {
            String u = str(m.get("discordId"));
            if (!u.matches("\\d{15,25}") || host.optedOut(u)) continue;
            out.add(u);
            if (out.size() >= MAX_MENTIONS) break;
        }
        return new ArrayList<>(out);
    }

    private void send(String thread, String content, JsonObject allowed) {
        JsonObject msg = Interactions.message(BotRules.truncate(content, 2000), null);
        msg.add("allowed_mentions", allowed != null ? allowed : Interactions.noMentions());
        msg.addProperty("flags", 4);   // SUPPRESS_EMBEDS：玩家发的链接不展开
        try {
            host.rest().post("/channels/" + thread + "/messages", msg);
        } catch (DiscordRest.DiscordException ex) {
            log.warning("发到对局子区失败：" + ex.friendly());
        } catch (IOException ex) {
            log.log(Level.FINE, "发到对局子区失败", ex);
        }
    }

    private void evictOld() {   // 调用方持有 matches 锁。close 丢了的局（例如 MiniGameHub 重载）不无限堆积
        Iterator<Map.Entry<String, MatchState>> it = matches.entrySet().iterator();
        while (matches.size() > MAX_MATCHES && it.hasNext()) {
            for (Target t : it.next().getValue().rooms.values()) byThread.remove(t.thread);
            it.remove();
        }
    }

    private Target target(String match, String room) {
        synchronized (matches) {
            MatchState st = matches.get(match);
            return st == null ? null : st.rooms.get(room);
        }
    }

    /** 测试 / status 用。 */
    Target byThread(String thread) {
        return byThread.get(thread);
    }

    int openMatches() {
        synchronized (matches) {
            return matches.size();
        }
    }

    // ================= Discord → 游戏（Gateway 线程） =================

    /** true = 这是对局子区的消息（已处理或有意忽略），调用方不要再当普通互通处理。 */
    boolean onDiscordMessage(JsonObject d) {
        Target t = byThread.get(str(d, "channel_id"));
        if (t == null) return false;
        if (!host.settings().active()) return true;
        int type = d.has("type") && !d.get("type").isJsonNull() ? d.get("type").getAsInt() : 0;
        if (type != 0 && type != 19) return true;   // 系统消息（加人、改名…）
        JsonObject author = d.getAsJsonObject("author");
        if (author == null || (author.has("bot") && author.get("bot").getAsBoolean()) || d.has("webhook_id")) return true;
        String user = str(author, "id");
        if (user.isEmpty() || host.optedOut(user)) return true;
        String content = clean(str(d, "content"), 256);
        if (d.has("attachments") && !d.getAsJsonArray("attachments").isEmpty()) content = BotRules.truncate((content + " [图片/文件]").trim(), 256);
        if (content.isEmpty()) return true;
        HubMatchChat hub = host.hub();
        if (hub == null) return true;
        String name = BotRules.truncate(displayName(d), 32);
        String text = content;
        host.inbound(() -> {
            try {
                Map<String, Object> r = hub.post(t.match, t.room, user, name, text);
                String status = String.valueOf(r.get("status"));
                if (!"ok".equals(status) && !"ghost-only".equals(status)) log.fine("对局子区消息没有送进游戏：" + status);
            } catch (Exception ex) {
                log.log(Level.FINE, "MatchChatApi.post 失败", ex);
            }
        });
        return true;
    }

    // ================= 普通聊天互通过滤（2026-10-15：对局内聊天不外泄） =================

    /** 这位玩家的普通聊天能不能转到 Discord 互通频道。MiniGameHub 不在 / 出错 = 原行为（转）。 */
    static boolean bridgeFromGame(HubMatchChat hub, String uuid) {
        if (hub == null) return true;
        try {
            return !hub.inMatch(uuid);
        } catch (Exception e) {
            return true;
        }
    }

    /** Discord 普通互通消息发给谁：MiniGameHub 不在 = 全部；否则只发给不在对局里的玩家。 */
    static <P> List<P> bridgeAudience(Collection<? extends P> players, Function<P, String> uuidOf, HubMatchChat hub) {
        List<P> out = new ArrayList<>();
        for (P p : players) if (bridgeFromGame(hub, uuidOf.apply(p))) out.add(p);
        return out;
    }

    // ================= 工具 =================

    /** 和互通一样：服务器昵称 > 全局显示名 > 用户名。 */
    static String displayName(JsonObject d) {
        JsonObject author = d.getAsJsonObject("author");
        String name = author == null ? "" : str(author, "global_name");
        if (d.has("member") && d.get("member").isJsonObject()) {
            JsonElement nick = d.getAsJsonObject("member").get("nick");
            if (nick != null && !nick.isJsonNull() && !nick.getAsString().isBlank()) name = nick.getAsString();
        }
        if (name.isEmpty() && author != null) name = str(author, "username");
        return clean(name, 64);
    }

    static String threadName(String game, String middle, String time) {
        StringBuilder sb = new StringBuilder(clean(game, 40).isEmpty() ? "对局" : clean(game, 40));
        String m = clean(middle, 40);
        if (!m.isEmpty()) sb.append(" · ").append(m);
        if (time != null && !time.isEmpty()) sb.append(" · ").append(time);
        return BotRules.truncate(sb.toString(), 100);
    }

    /** 转义 Discord markdown（单行文本）。 */
    static String md(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            if ("\\*_~`|>[]()#-<:".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** 换行变空格、去控制字符、截断。 */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (!Character.isISOControl(c) && c != '​' && c != ' ' && c != ' ') sb.append(c);
        }
        return BotRules.truncate(sb.toString().trim(), max);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T> T deepCopy(T o) {
        if (o instanceof Map<?, ?> m) {
            Map<Object, Object> c = new HashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) c.put(en.getKey(), deepCopy(en.getValue()));
            return (T) c;
        }
        if (o instanceof List<?> l) {
            List<Object> c = new ArrayList<>(l.size());
            for (Object x : l) c.add(deepCopy(x));
            return (T) c;
        }
        return o;
    }
}
