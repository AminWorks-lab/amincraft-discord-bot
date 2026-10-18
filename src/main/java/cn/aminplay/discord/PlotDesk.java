package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 2026-10-08 bot-plots：类脑市地块申请 ↔ Discord。
 * <ul>
 *   <li>新申请发到管理频道 channels.plots：嵌入 + [同意]/[驳回]（只有管理员能点，驳回可填原因），结果同步回游戏，嵌入改成「谁处理的」。</li>
 *   <li>绑定了游戏号的成员可以 /minmin 申请地块、/minmin 加入申请、/minmin 我的申请（结果只有自己可见）。</li>
 *   <li>共同开发者的权限变动要全体同意：私信每个成员 [同意]/[拒绝]；私信发不出去（没绑定 / 关了私信）就告诉游戏服务器改成游戏内提示。</li>
 * </ul>
 * 游戏那边是 MiniGameHub 的 City 申请 API（{@link HubCity}，反射）；Discord ↔ 游戏号只用学习版密钥的绑定表。worker 线程调用。
 */
final class PlotDesk {
    static final Set<String> COMMANDS = Set.of("plotapply", "plotjoin", "plotmine");
    static final String UNAVAILABLE = "类脑市地块申请现在**不可用**（游戏服务器的类脑市接口没有运行），请稍后再试，或者在游戏里申请。";

    /** 游戏服务器的 City 申请 API（接口约定 bot-plots 段）。方法不存在时抛 {@link UnsupportedOperationException}。 */
    interface City {
        Map<String, Object> submit(String plot, String uuid, String reason, String source) throws Exception;
        Map<String, Object> join(String applicationId, String uuid, String source) throws Exception;
        List<Map<String, Object>> list(String filter) throws Exception;
        Map<String, Object> get(String applicationId) throws Exception;
        Map<String, Object> approve(String applicationId, String by, String source) throws Exception;
        Map<String, Object> reject(String applicationId, String by, String reason, String source) throws Exception;
        Map<String, Object> answerCoDev(String requestId, String uuid, boolean yes, String source) throws Exception;
        /** 可选：没有这个方法时什么都不做。 */
        void notified(String requestId, String uuid, boolean dm) throws Exception;
        /** 可选：没有时返回空列表。 */
        List<Map<String, Object>> searchPlots(String query, int limit) throws Exception;
    }

    /** 绑定的游戏号。 */
    record Account(String uuid, String name, boolean banned) {}

    /** Discord ↔ 游戏号（学习版密钥绑定表）。 */
    interface Accounts {
        /** 这个 Discord 用户绑定的游戏号；没绑定 = null。 */
        Account bound(String discordId) throws Exception;
        /** 这个游戏号绑定的 Discord 用户 id；没有 = null。 */
        String discordOf(String uuid, String name) throws Exception;
    }

    interface Host {
        DiscordRest rest();
        BotConfig cfg();
        String applicationId();
        /** null = MiniGameHub 不在 / 太旧。 */
        City city();
        /** null = 绑定表不可用。 */
        Accounts accounts();
        void adminLog(String text);
    }

    /** 用学习版密钥绑定表（CrackedKeyApi.lookup：按 Discord id / 游戏名查，不含密钥）做 Discord ↔ 游戏号。keys == null → null（不可用）。 */
    static Accounts accountsOf(HubAuthKeys keys) {
        if (keys == null) return null;
        return new Accounts() {
            @Override
            public Account bound(String discordId) throws Exception {
                for (Map<String, Object> r : keys.lookup(discordId)) {
                    if (!discordId.equals(PlotRules.str(r.get("discordId"))) || !Boolean.TRUE.equals(r.get("bound"))) continue;
                    String uuid = PlotRules.str(r.get("uuid"));
                    if (!uuid.isEmpty()) return new Account(uuid, PlotRules.str(r.get("name")), Boolean.TRUE.equals(r.get("banned")));
                }
                return null;
            }

            @Override
            public String discordOf(String uuid, String name) throws Exception {
                if (name == null || name.isBlank() || uuid == null || uuid.isBlank()) return null;
                for (Map<String, Object> r : keys.lookup(name)) {
                    if (Boolean.TRUE.equals(r.get("bound")) && uuid.equalsIgnoreCase(PlotRules.str(r.get("uuid")))) {
                        String d = PlotRules.str(r.get("discordId"));
                        if (!d.isEmpty()) return d;
                    }
                }
                return null;
            }
        };
    }

    private final Host host;
    private final PlotStore store;
    private final Logger log;
    private volatile boolean warnedNoChannel;

    PlotDesk(Host host, PlotStore store, Logger log) {
        this.host = host;
        this.store = store;
        this.log = log;
    }

    PlotStore store() {
        return store;
    }

    // ===================== 游戏 → Discord（事件） =====================

    /** City API 的事件（worker 线程）。 */
    void onEvent(Map<String, Object> e) {
        String type = PlotRules.str(e.get("type"));
        try {
            switch (type) {
                case "application-new", "application-updated" -> sync(PlotRules.str(e.get("id")), true);
                case "codev-request" -> codevRequest(e);
                case "codev-closed" -> codevClosed(PlotRules.str(e.get("id")), PlotRules.str(e.get("result")));
                default -> {
                }
            }
        } catch (Exception ex) {
            log.log(Level.WARNING, "处理类脑市事件 " + type + " 出错", ex);
        }
    }

    /** 机器人启动 / 接口重新连上 / 设了频道之后：把还没发过的待审申请补发到管理频道（最多 limit 条）。返回补发条数。 */
    int catchUp(int limit) {
        City city = host.city();
        if (city == null || host.cfg().plotsChannel.isEmpty()) return 0;
        int n = 0;
        try {
            for (Map<String, Object> a : city.list("open")) {
                String id = PlotRules.str(a.get("id"));
                if (id.isEmpty() || store.post(id) != null) continue;
                if (n >= limit) break;
                if (sync(id, false)) n++;
            }
        } catch (Exception ex) {
            log.warning("补发待审地块申请失败：" + ex);
        }
        return n;
    }

    /**
     * 让管理频道里这条申请的嵌入和游戏里一致：发过就改，没发过且还在待审就发。siblings = 顺便刷新同一地块的其它申请（竞争栏）。
     * 返回 true = 新发了一条。
     */
    boolean sync(String id, boolean siblings) throws Exception {
        City city = host.city();
        if (city == null || id.isEmpty()) return false;
        Map<String, Object> app = city.get(id);
        if (app == null || "not-found".equals(app.get("status")) || app.get("plot") == null) return false;
        List<Map<String, Object>> competing = competing(city, app);
        boolean posted = render(app, competing);
        if (siblings) for (Map<String, Object> c : competing) {
            String cid = PlotRules.str(c.get("id"));
            if (cid.equals(id) || store.post(cid) == null) continue;
            try {
                Map<String, Object> full = city.get(cid);
                if (full != null && full.get("plot") != null) render(full, competing(city, full));
            } catch (Exception ex) {
                log.fine("刷新竞争申请 " + cid + " 失败：" + ex);
            }
        }
        return posted;
    }

    private List<Map<String, Object>> competing(City city, Map<String, Object> app) {
        String plot = PlotRules.str(app.get("plot"));
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> a : city.list("plot:" + plot)) if (plot.equals(PlotRules.str(a.get("plot"))) && PlotRules.open(a)) out.add(a);
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 发 / 改一条嵌入。返回 true = 新发了一条。 */
    private boolean render(Map<String, Object> app, List<Map<String, Object>> competing) throws IOException {
        String id = PlotRules.str(app.get("id"));
        JsonObject msg = PlotRules.applicationMessage(app, competing, mentions(app));
        String path = store.post(id);
        if (path != null) {
            try {
                inThread(path.split("/")[2], () -> host.rest().patch(path, msg));   // 2026-10-08 release-136: archived thread → unarchive + retry
                return false;
            } catch (DiscordRest.DiscordException ex) {
                if (ex.code != 10008 && ex.code != 10003) throw ex;
                store.removePost(id);   // 那条消息 / 频道被删了：待审的重新发
            }
        }
        if (!PlotRules.open(app)) return false;
        String ch = host.cfg().plotsChannel;
        if (ch.isEmpty()) {
            if (!warnedNoChannel) log.info("有新的类脑市地块申请，但还没设置管理频道：/discordbot set channels.plots <频道id>");
            warnedNoChannel = true;
            return false;
        }
        String mid = inThread(ch, () -> host.rest().post("/channels/" + ch + "/messages", msg)).getAsJsonObject().get("id").getAsString();   // 2026-10-08 release-136
        store.putPost(id, "/channels/" + ch + "/messages/" + mid);
        return true;
    }

    // 2026-10-08 release-136: channels.plots may be a (private) thread (user: 子区 <private-thread-id>). An archived thread rejects
    // posts and edits with Discord error 50083 → unarchive it once (PATCH archived:false, same as ReplayPoster) and retry.
    @FunctionalInterface interface RestCall { JsonElement run() throws IOException; }
    JsonElement inThread(String channel, RestCall call) throws IOException {
        try { return call.run(); } catch (DiscordRest.DiscordException ex) {
            if (ex.code != 50083) throw ex;
            JsonObject un = new JsonObject(); un.addProperty("archived", false);
            host.rest().patch("/channels/" + channel, un);
            log.info("地块申请子区 " + channel + " 已归档，已取消归档后重发");
            return call.run();
        }
    }

    /** 申请人 uuid → 绑定的 Discord id。 */
    private Map<String, String> mentions(Map<String, Object> app) {
        Map<String, String> out = new HashMap<>();
        Accounts acc = host.accounts();
        if (acc == null) return out;
        for (Map<String, Object> a : PlotRules.applicants(app)) {
            try {
                String d = acc.discordOf(PlotRules.str(a.get("uuid")), PlotRules.str(a.get("name")));
                if (d != null) out.put(PlotRules.str(a.get("uuid")), d);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private void codevRequest(Map<String, Object> e) throws Exception {
        String id = PlotRules.str(e.get("id"));
        City city = host.city();
        if (city == null || id.isEmpty()) return;
        Accounts acc = host.accounts();
        for (Map<String, Object> m : PlotRules.members(e)) {
            String vote = PlotRules.str(m.get("vote"));
            if (vote.equals("yes") || vote.equals("no")) continue;   // 发起人自己 / 已经表过态的人不用私信
            String uuid = PlotRules.str(m.get("uuid"));
            boolean ok = false;
            try {
                String d = acc == null ? null : acc.discordOf(uuid, PlotRules.str(m.get("name")));
                String path = d == null ? null : dm(d, PlotRules.codevDm(e, host.cfg().serverName));
                if (path != null) {
                    store.addDm(id, path);
                    ok = true;
                }
            } catch (Exception ex) {
                log.fine("共同开发者私信失败：" + ex);
            }
            try {
                city.notified(id, uuid, ok);   // false → 游戏内提示
            } catch (Exception ex) {
                log.fine("City notified 失败：" + ex);
            }
        }
    }

    /** 私信；返回消息路径，发不出去（关了私信）= null。 */
    private String dm(String userId, JsonObject msg) throws IOException {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("recipient_id", userId);
            String ch = host.rest().post("/users/@me/channels", body).getAsJsonObject().get("id").getAsString();
            msg.add("allowed_mentions", Interactions.noMentions());
            String mid = host.rest().post("/channels/" + ch + "/messages", msg).getAsJsonObject().get("id").getAsString();
            return "/channels/" + ch + "/messages/" + mid;
        } catch (DiscordRest.DiscordException ex) {
            if (ex.code == 50007 || ex.status == 403) return null;
            throw ex;
        }
    }

    private void codevClosed(String id, String result) {
        JsonObject edit = new JsonObject();
        edit.addProperty("content", PlotRules.codevClosed(result));
        edit.add("components", new JsonArray());
        for (String path : store.takeDms(id)) {
            try {
                host.rest().patch(path, edit);
            } catch (IOException ex) {
                log.fine("改共同开发者私信失败：" + ex);
            }
        }
    }

    // ===================== Discord → 游戏（交互） =====================

    /** custom_id 是不是这里的（私信里的共同开发者按钮要在服务器检查之前处理）。 */
    static boolean ownsComponent(String customId) {
        return customId.startsWith(PlotRules.BTN) || customId.startsWith(PlotRules.MODAL) || customId.startsWith(PlotRules.CODEV);
    }

    /** 斜杠指令（command = plotapply / plotjoin / plotmine）、按钮、弹窗、自动补全。自己处理所有错误。 */
    void handle(JsonObject d) {
        Ix ix = new Ix(d);
        try {
            switch (ix.type) {
                case 2 -> command(ix);
                case 3 -> component(ix);
                case 4 -> autocomplete(ix);
                case 5 -> modal(ix);
                default -> {
                }
            }
        } catch (UnsupportedOperationException e) {
            ix.quietly(UNAVAILABLE);
        } catch (DiscordRest.DiscordException e) {
            log.warning("Discord 交互失败：" + e.getMessage());
            ix.quietly("❌ " + e.friendly());
        } catch (Exception e) {
            log.log(Level.WARNING, "处理地块申请交互出错", e);
            ix.quietly("❌ 出错了：" + e.getClass().getSimpleName() + " " + BotRules.truncate(String.valueOf(e.getMessage()), 300));
        }
    }

    private void command(Ix ix) throws Exception {
        ix.deferEphemeral();
        City city = host.city();
        Accounts acc = host.accounts();
        if (city == null || acc == null) {
            ix.reply(UNAVAILABLE);
            return;
        }
        Account me = acc.bound(ix.userId());
        if (me == null) {
            ix.reply("只有**绑定了游戏号**的 Discord 账号能在这里申请地块（绑定：/minmin 领取密钥 → 进服 `/key 密钥`）。也可以直接在游戏里打开菜单 → 类脑市 申请。");
            return;
        }
        if (me.banned()) {
            ix.reply("⛔ 你的账号已被封禁，不能申请地块。");
            return;
        }
        String text;
        switch (ix.command) {
            case "plotapply" -> {
                String plot = ix.option("plot").trim(), reason = ix.option("reason").trim();
                if (plot.isEmpty() || reason.isEmpty()) {
                    ix.reply("地块和理由都要填。");
                    return;
                }
                Map<String, Object> r = city.submit(plot, me.uuid(), BotRules.truncate(reason, 300), "discord");
                text = PlotRules.submitReply(r, plot);
                if ("ok".equals(r.get("status"))) host.adminLog("🏙️ " + me.name() + "（<@" + ix.userId() + ">）在 Discord 申请了地块 " + plot);
            }
            case "plotjoin" -> {
                String id = resolveApplication(city, ix.option("application").trim());
                if (id == null) {
                    ix.reply("这个地块有好几个待审申请，请从下拉列表里选要加入哪一个。");
                    return;
                }
                Map<String, Object> r = city.join(id, me.uuid(), "discord");
                text = PlotRules.joinReply(r);
            }
            default -> text = "";
        }
        text = (text.isEmpty() ? "" : text + "\n\n") + PlotRules.mine(city.list("player:" + me.uuid()));
        ix.reply(BotRules.truncate("游戏号：**" + PlotRules.esc(me.name()) + "**\n" + text, 2000));
    }

    /**
     * 加入申请：值是申请编号；也接受地块编号 / 名字（那块地只有一个待审申请时）。null = 有好几个，要用户选。
     * 找不到时原样交给游戏服务器（city-rules-v2 的 join 自己也认地块名，有多个时回 ambiguous）。
     */
    private static String resolveApplication(City city, String v) throws Exception {
        Map<String, Object> a = city.get(v);
        if (a != null && a.get("plot") != null && !"not-found".equals(a.get("status"))) return v;
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map<String, Object> x : city.list("plot:" + v)) if (PlotRules.open(x)) open.add(x);
        if (open.size() > 1) return null;
        return open.isEmpty() ? v : PlotRules.str(open.get(0).get("id"));
    }

    private void component(Ix ix) throws Exception {
        String cid = ix.customId();
        if (cid.startsWith(PlotRules.CODEV)) {
            codevButton(ix, cid);
            return;
        }
        String[] p = cid.split("\\|", 3);
        if (p.length < 3) return;
        if (!ix.isAdmin(host.cfg())) {
            ix.ephemeralNow("⛔ 只有管理员能审批地块申请。");
            return;
        }
        City city = host.city();
        if (city == null) {
            ix.ephemeralNow(UNAVAILABLE);
            return;
        }
        if (p[1].equals("no")) {
            ix.callback(9, PlotRules.rejectModal(p[2]));
            return;
        }
        ix.deferUpdate();
        Map<String, Object> r = city.approve(p[2], ix.actorName(), "discord");
        decided(ix, city, p[2], r, "同意");
    }

    private void modal(Ix ix) throws Exception {
        String id = ix.customId().substring(PlotRules.MODAL.length());
        if (!ix.isAdmin(host.cfg())) {
            ix.ephemeralNow("⛔ 只有管理员能审批地块申请。");
            return;
        }
        City city = host.city();
        if (city == null) {
            ix.ephemeralNow(UNAVAILABLE);
            return;
        }
        Map<String, String> values = new HashMap<>();
        collectValues(ix.data.get("components"), values);
        String reason = values.getOrDefault("reason", "").trim();
        ix.deferUpdate();
        Map<String, Object> r = city.reject(id, ix.actorName(), reason, "discord");
        decided(ix, city, id, r, "驳回");
    }

    /** 同意 / 驳回之后：改这条嵌入（最新状态，按钮去掉），失败时给管理员一条仅自己可见的说明，同一地块的其它申请也刷新。 */
    @SuppressWarnings("unchecked")
    private void decided(Ix ix, City city, String id, Map<String, Object> r, String verb) throws Exception {
        Map<String, Object> app = r.get("application") instanceof Map<?, ?> m ? (Map<String, Object>) m : city.get(id);
        if (app != null && app.get("plot") != null) {
            List<Map<String, Object>> competing = competing(city, app);
            ix.edit(PlotRules.applicationMessage(app, competing, mentions(app)));
            String path = "/channels/" + ix.channelId() + "/messages/" + ix.messageId();
            if (!ix.messageId().isEmpty() && store.post(id) == null) store.putPost(id, path);
        }
        if (!"ok".equals(r.get("status"))) {
            ix.followup(PlotRules.decisionError(r));
            return;
        }
        String plot = app == null ? id : PlotRules.str(app.get("plot"));
        host.adminLog("🏙️ " + ix.actorName() + " 在 Discord " + verb + "了地块 " + plot + " 的申请（" + id + "）");
        try {
            sync(id, true);   // 竞争申请的「其它申请」栏
        } catch (Exception ex) {
            log.fine("刷新竞争申请失败：" + ex);
        }
    }

    private void codevButton(Ix ix, String cid) throws Exception {
        String[] p = cid.split("\\|", 3);
        if (p.length < 3) return;
        City city = host.city();
        Accounts acc = host.accounts();
        ix.deferUpdate();
        if (city == null || acc == null) {
            ix.followup(UNAVAILABLE);
            return;
        }
        Account me = acc.bound(ix.userId());
        boolean yes = p[1].equals("y");
        Map<String, Object> r = me == null ? Map.of("status", "wrong-user") : city.answerCoDev(p[2], me.uuid(), yes, "discord");
        String st = PlotRules.str(r.get("status"));
        if (st.equals("error")) {   // 保留按钮，可以再点一次
            ix.followup(PlotRules.codevAnswer(r, yes));
            return;
        }
        JsonObject edit = new JsonObject();
        edit.addProperty("content", PlotRules.codevAnswer(r, yes));
        edit.add("components", new JsonArray());
        ix.edit(edit);
    }

    private void autocomplete(Ix ix) throws Exception {
        String focused = "", typed = "";
        for (JsonObject o : ix.leafOptions()) {
            if (o.has("focused") && o.get("focused").getAsBoolean()) {
                focused = o.get("name").getAsString();
                typed = o.has("value") ? o.get("value").getAsString() : "";
            }
        }
        String q = typed.toLowerCase(Locale.ROOT);
        JsonArray choices = new JsonArray();
        City city = host.city();
        if (city != null) {
            if (focused.equals("plot")) {
                for (Map<String, Object> p : city.searchPlots(typed, 25)) {
                    if (choices.size() >= 25) break;
                    choices.add(choice(PlotRules.str(p.get("label")).isEmpty() ? PlotRules.str(p.get("id")) : PlotRules.str(p.get("label")), PlotRules.str(p.get("id"))));
                }
            } else if (focused.equals("application")) {
                for (Map<String, Object> a : city.list("open")) {
                    if (choices.size() >= 25) break;
                    String label = PlotRules.choiceLabel(a);
                    if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
                    choices.add(choice(label, PlotRules.str(a.get("id"))));
                }
            }
        }
        JsonObject data = new JsonObject();
        data.add("choices", choices);
        ix.callback(8, data);
    }

    private static JsonObject choice(String name, String value) {
        JsonObject c = new JsonObject();
        c.addProperty("name", BotRules.truncate(name, 100));
        c.addProperty("value", BotRules.truncate(value, 100));
        return c;
    }

    private static void collectValues(JsonElement e, Map<String, String> out) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectValues(x, out);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("custom_id") && o.has("value") && !o.get("value").isJsonNull()) out.put(o.get("custom_id").getAsString(), o.get("value").getAsString());
            collectValues(o.get("component"), out);
            collectValues(o.get("components"), out);
        }
    }

    /** /discordbot plots 的状态行。 */
    List<String> status(Map<String, Object> cityStatus) {
        List<String> out = new ArrayList<>();
        BotConfig c = host.cfg();
        out.add("类脑市接口：" + (host.city() == null ? "不可用（MiniGameHub 没有 City 申请 API）" : "已连接" + (cityStatus == null ? "" : " " + cityStatus)));
        out.add("绑定表：" + (host.accounts() == null ? "不可用" : "可用"));
        out.add("管理频道 channels.plots：" + (c.plotsChannel.isEmpty() ? "未设置（/discordbot set channels.plots <id>）" : c.plotsChannel));
        out.add("已发嵌入 " + store.postCount() + " 条，待结束的共同开发者私信 " + store.dmCount() + " 条");
        return out;
    }

    // ===================== 一次交互 =====================

    /** 一次交互：自己选「回复 / 改原消息 / 追加仅自己可见」。 */
    final class Ix {
        final JsonObject d, data;
        final int type;
        final String command;
        boolean deferred, responded;

        Ix(JsonObject d) {
            this.d = d;
            this.type = d.get("type").getAsInt();
            this.data = d.has("data") ? d.getAsJsonObject("data") : new JsonObject();
            String name = data.has("name") ? data.get("name").getAsString() : "";
            String c = name;
            if (name.equals(Interactions.ROOT) && data.has("options") && !data.getAsJsonArray("options").isEmpty()) {
                c = data.getAsJsonArray("options").get(0).getAsJsonObject().get("name").getAsString();
            }
            this.command = c;
        }

        String customId() {
            return data.has("custom_id") ? data.get("custom_id").getAsString() : "";
        }

        String option(String name) {
            for (JsonObject o : leafOptions()) if (o.get("name").getAsString().equals(name) && o.has("value")) return o.get("value").getAsString();
            return "";
        }

        List<JsonObject> leafOptions() {
            List<JsonObject> out = new ArrayList<>();
            leaves(data.get("options"), out);
            return out;
        }

        private void leaves(JsonElement e, List<JsonObject> out) {
            if (e == null || !e.isJsonArray()) return;
            for (JsonElement x : e.getAsJsonArray()) {
                JsonObject o = x.getAsJsonObject();
                int t = o.has("type") ? o.get("type").getAsInt() : 0;
                if (t == 1 || t == 2) leaves(o.get("options"), out);
                else out.add(o);
            }
        }

        JsonObject member() {
            return d.has("member") ? d.getAsJsonObject("member") : new JsonObject();
        }

        JsonObject user() {
            JsonObject m = member();
            if (m.has("user")) return m.getAsJsonObject("user");
            return d.has("user") ? d.getAsJsonObject("user") : new JsonObject();
        }

        String userId() {
            return user().get("id").getAsString();
        }

        String actorName() {
            JsonObject m = member(), u = user();
            if (m.has("nick") && !m.get("nick").isJsonNull()) return m.get("nick").getAsString();
            if (u.has("global_name") && !u.get("global_name").isJsonNull()) return u.get("global_name").getAsString();
            return u.has("username") ? u.get("username").getAsString() : "?";
        }

        boolean isAdmin(BotConfig cfg) {
            JsonObject m = member();
            if (m.has("permissions") && BotRules.hasAdminBits(m.get("permissions").getAsString())) return true;
            if (m.has("roles")) for (JsonElement r : m.getAsJsonArray("roles")) if (cfg.adminRoles.contains(r.getAsString())) return true;
            return false;
        }

        String channelId() {
            return d.has("channel_id") ? d.get("channel_id").getAsString() : "";
        }

        String messageId() {
            return d.has("message") && d.getAsJsonObject("message").has("id") ? d.getAsJsonObject("message").get("id").getAsString() : "";
        }

        void callback(int cbType, JsonObject cbData) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("type", cbType);
            if (cbData != null) body.add("data", cbData);
            host.rest().post("/interactions/" + d.get("id").getAsString() + "/" + d.get("token").getAsString() + "/callback", body);
            responded = true;
        }

        void deferEphemeral() throws IOException {
            JsonObject f = new JsonObject();
            f.addProperty("flags", 64);
            callback(5, f);
            deferred = true;
        }

        /** 按钮 / 弹窗：先应答，稍后改原消息。 */
        void deferUpdate() throws IOException {
            callback(6, null);
            deferred = true;
        }

        void ephemeralNow(String text) throws IOException {
            JsonObject m = Interactions.message(BotRules.truncate(text, 2000), null);
            m.addProperty("flags", 64);
            m.add("allowed_mentions", Interactions.noMentions());
            callback(4, m);
        }

        /** 改 @original（指令 = 那条仅自己可见的回复；按钮 = 按钮所在的消息）。 */
        void edit(JsonObject msg) throws IOException {
            if (!msg.has("allowed_mentions")) msg.add("allowed_mentions", Interactions.noMentions());
            host.rest().patch("/webhooks/" + host.applicationId() + "/" + d.get("token").getAsString() + "/messages/@original", msg);
        }

        void reply(String text) throws IOException {
            if (deferred) edit(Interactions.message(BotRules.truncate(text, 2000), null));
            else ephemeralNow(text);
        }

        void followup(String text) throws IOException {
            JsonObject m = Interactions.message(BotRules.truncate(text, 2000), null);
            m.addProperty("flags", 64);
            m.add("allowed_mentions", Interactions.noMentions());
            host.rest().post("/webhooks/" + host.applicationId() + "/" + d.get("token").getAsString(), m);
        }

        void quietly(String text) {
            if (type == 4) return;
            try {
                if (!responded) ephemeralNow(text);
                else if (type == 2) reply(text);
                else followup(text);
            } catch (IOException ignored) {
            }
        }
    }
}
