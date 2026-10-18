package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import io.papermc.paper.ban.BanListType;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** 处理斜杠指令、按钮、弹窗、自动补全。运行在插件的 worker 线程上。 */
final class Interactions {
    private static final int COLOR_GREEN = 0x57C84D;
    private static final int COLOR_RED = 0xE5534B;
    private static final int COLOR_GOLD = 0xF2B33D;
    private static final String ADMIN_PERMS = String.valueOf(1L << 5); // MANAGE_GUILD

    private final AminPlayDiscord plugin;
    private final WebLoginFlow webLogin;
    private final PingRules pingRules = new PingRules();   // 2026-10-18 public ping cooldowns (in memory)

    Interactions(AminPlayDiscord plugin) {
        this.plugin = plugin;
        webLogin = new WebLoginFlow(new WebLoginFlow.Host() {
            @Override public BotConfig cfg() { return plugin.cfg(); }
            @Override public String applicationId() { return plugin.applicationId(); }
            @Override public DiscordRest rest() { return plugin.rest(); }
            @Override public Map<String, Object> claim(String discordId, String interactionId) throws Exception {
                HubAuthKeys keys = plugin.authKeys();
                return keys == null ? Map.of("status", "unavailable") : keys.claimWebLogin(discordId, interactionId);
            }
        });
    }

    // =============== 指令定义（注册到 Discord） ===============

    /**
     * 2026-10-07 minmin prefix：所有指令都放在 /minmin 下面（/minmin 收集、/minmin 通知组 添加 …）。
     * Discord 只能按整条指令隐藏，所以 /minmin 对所有人可见；管理类子指令在机器人这边检查权限。
     */
    static JsonArray definitions() {
        return definitions(false);
    }

    static JsonArray definitions(boolean webLoginEnabled) {
        JsonArray subs = new JsonArray();
        for (JsonElement e : commands(webLoginEnabled)) {
            JsonObject c = e.getAsJsonObject();
            boolean group = false;
            if (c.has("options")) for (JsonElement o : c.getAsJsonArray("options")) if (o.getAsJsonObject().get("type").getAsInt() == 1) group = true;
            JsonObject s = new JsonObject();
            s.addProperty("type", group ? 2 : 1);
            s.addProperty("name", c.get("name").getAsString());
            s.add("name_localizations", c.get("name_localizations"));
            s.addProperty("description", c.get("description").getAsString());
            if (c.has("options")) s.add("options", c.get("options"));
            subs.add(s);
        }
        JsonObject root = new JsonObject();
        root.addProperty("name", ROOT);
        root.addProperty("description", "Amincraft 机器人（管理类功能只有管理员能用）");
        root.addProperty("type", 1);
        root.add("contexts", single(0));
        root.add("options", subs);
        JsonArray a = new JsonArray();
        a.add(root);
        return a;
    }

    static final String ROOT = "minmin";

    /** 2026-10-08 bot-plots：注册日志里的指令清单（按 commands() 的中文名生成，不再手写）。 */
    static String commandSummary() {
        return commandSummary(false);
    }

    static String commandSummary(boolean webLoginEnabled) {
        List<String> names = new ArrayList<>();
        for (JsonElement e : commands(webLoginEnabled)) {
            JsonObject c = e.getAsJsonObject();
            names.add(c.getAsJsonObject("name_localizations").get("zh-CN").getAsString());
        }
        return "/" + ROOT + " " + String.join(" / ", names);
    }

    private static JsonArray commands(boolean webLoginEnabled) {
        JsonArray a = new JsonArray();
        a.add(cmd("status", "状态", "查看 MC 服务器状态和在线玩家", null));
        a.add(cmd("privacy", "隐私", "选择是否让机器人收集你的消息（反馈汇总、聊天互通）", null,   // 2026-10-08 privacy
                sub("optout", "退出", "以后不再收集你的消息，也不转发到游戏里"),
                sub("optin", "恢复", "恢复收集和转发"),
                sub("status", "查看", "查看你现在的选择")));
        // 2026-10-17 聊天互通不读频道消息时（没有 Message Content Intent），用这个把话发进游戏公屏
        JsonObject sayText = opt(3, "text", "内容", "要发到游戏公屏的话", true, false);
        sayText.addProperty("max_length", SAY_MAX);
        a.add(cmd("say", "说", "在聊天互通频道里把一句话发到游戏公屏", null, sayText));
        a.add(cmd("key", "领取密钥", "领取学习版玩家登录密钥（每个 Discord 账号只发一次，只有你能看到）", null));   // 2026-10-07 auth-gate
        a.add(cmd("linkpremium", "关联正版", "正版玩家：把正版账号关联到这个 Discord（先进服，游戏里会弹窗让你确认）", null,   // 2026-10-11 account-link
                opt(3, "name", "正版名字", "你的正版游戏名（要在线）", true, false)));
        if (webLoginEnabled) a.add(cmd(WebLoginFlow.COMMAND, "网页登录码", "领取已有绑定账号的网页一次性登录码（2 分钟内有效，只有你能看到）", null));
        // 2026-10-08 bot-plots：类脑市地块申请（只给绑定了游戏号的成员；结果只有自己能看到）
        JsonObject reason = opt(3, "reason", "理由", "为什么想要这块地、打算建什么（管理员审批时会看到）", true, false);
        reason.addProperty("max_length", 300);
        a.add(cmd("plotapply", "申请地块", "申请类脑市的一块地（需要绑定游戏号）", null,
                opt(3, "plot", "地块", "地块编号，可以输入关键字搜索", true, true), reason));
        a.add(cmd("plotjoin", "加入申请", "加入别人对一块地的申请，一起共同开发（需要绑定游戏号）", null,
                opt(3, "application", "申请", "要加入的申请（从列表里选，也可以填地块编号）", true, true)));
        a.add(cmd("plotmine", "我的申请", "查看你的类脑市地块申请和结果", null));
        a.add(cmd("lookup", "查询", "查询学习版密钥记录（游戏名或 Discord 成员）", ADMIN_PERMS,
                opt(3, "player", "玩家", "游戏名（含改名前的名字）", false, false),
                opt(6, "member", "成员", "Discord 成员", false, false)));
        a.add(cmd("announce", "公告", "发布服务器公告（弹出填写框；论坛频道会开新贴）", ADMIN_PERMS,
                channelOpt("channel", "频道", "发到哪个频道（不填=默认公告频道）"),
                opt(3, "notify", "通知", "要 @ 谁（订阅名单 / 身份组 / 所有人；不填=不 @）", false, true),
                opt(3, "tag", "标签", "论坛贴子的标签（只对论坛频道有效）", false, true),
                opt(5, "ingame", "游戏内", "同时在游戏内广播", false, false)));
        // 2026-10-12 bot-promo：在哪个频道 / 子区设置，就定时发到哪里
        JsonObject every = opt(4, "every", "间隔", "每隔几小时整点发一次（1–24，从 0 点算起；和「时间」二选一）", false, false);
        every.addProperty("min_value", 1);
        every.addProperty("max_value", 24);
        JsonObject promoText = opt(3, "text", "文字", "自定义介绍文字（不填=默认广告卡片）", false, false);
        promoText.addProperty("max_length", PromoRules.MAX_TEXT);
        a.add(cmd("promo", "定时广告", "在当前频道或子区定时发小游戏服务器的广告", ADMIN_PERMS,
                sub("set", "设置", "在当前频道 / 子区开启定时广告（时间和间隔二选一）",
                        opt(3, "times", "时间", "每天几点发，例如 12:00,20:00（默认北京时间）", false, false), every, promoText),
                sub("preview", "预览", "马上在当前频道 / 子区发一条广告看看效果"),
                sub("off", "关闭", "停止当前频道 / 子区的定时广告"),
                sub("list", "列表", "查看所有定时广告")));
        a.add(cmd("rolepanel", "身份面板", "发布领取身份组 / 订阅通知的按钮面板", ADMIN_PERMS,
                channelOpt("channel", "频道", "发到哪个频道（不填=当前频道）")));
        a.add(cmd("notifygroup", "通知组", "管理通知组（不占身份组，@ 时临时创建）", ADMIN_PERMS,
                sub("add", "添加", "新增一个通知组",
                        opt(3, "name", "名字", "按钮和 @ 显示的名字，例如 版本更新", true, false),
                        opt(3, "emoji", "表情", "按钮上的表情，例如 📦", false, false),
                        opt(3, "description", "说明", "面板上的一句话说明", false, false)),
                sub("remove", "删除", "删除一个通知组（订阅名单一起删除）",
                        opt(3, "group", "通知组", "要删除的通知组", true, true)),
                sub("list", "列表", "查看所有通知组和订阅人数")));
        // 2026-10-18 public ping：对所有人开放；一般成员只能 @ ping.public 里的项目（例如「缺人找我」），有冷却
        a.add(cmd("ping", "通知", "@ 某个身份组 / 通知组（一般成员只能 @ 开放的那几个，例如缺人找我）", null,
                opt(3, "group", "通知组", "要 @ 的身份组 / 通知组", true, true),
                opt(3, "message", "内容", "跟在 @ 后面的一句话，例如 起床战争 3 缺 1", false, false),
                pingChannelOpt()));
        a.add(cmd("cmd", "执行", "在 MC 服务器控制台执行指令", ADMIN_PERMS,
                opt(3, "command", "指令", "要执行的指令，例如 say 大家好", true, false)));
        a.add(cmd("ban", "封禁", "封禁 MC 玩家，或封禁 Discord 成员（连同他绑定的学习版游戏号）", ADMIN_PERMS,
                opt(3, "player", "玩家", "玩家名（和「成员」二选一）", false, true),
                opt(6, "member", "成员", "Discord 成员：封他绑定的所有游戏号，并且不能再领密钥", false, false),   // 2026-10-07 auth-gate
                opt(3, "reason", "原因", "封禁原因", false, false),
                opt(3, "duration", "时长", "例如 30m、12h、7d、1d12h；不填=永久", false, false)));
        a.add(cmd("unban", "解封", "解除 MC 玩家或 Discord 成员的封禁", ADMIN_PERMS,
                opt(3, "player", "玩家", "玩家名（和「成员」二选一）", false, true),
                opt(6, "member", "成员", "Discord 成员", false, false)));   // 2026-10-07 auth-gate
        a.add(cmd("kick", "踢出", "把在线玩家踢出服务器", ADMIN_PERMS,
                opt(3, "player", "玩家", "玩家名", true, true),
                opt(3, "reason", "原因", "踢出原因", false, false)));
        a.add(cmd("banlist", "封禁列表", "查看被封禁的玩家", ADMIN_PERMS));
        a.add(cmd("collect", "收集", "收集反馈频道里一段时间内的发言和附件，结果发到只有你能看到的私密子区", ADMIN_PERMS,
                collectChannelOpt(),   // 2026-10-08 privacy: no member filter, feedback channels only
                opt(3, "from", "开始", "例如 今天、昨天、3d、2026-10-06、10-06 20:00（日本时间；不填=今天 0 点）", false, false),
                opt(3, "to", "结束", "例如 现在、2026-10-06、10-06 23:00（不填=现在）", false, false),
                opt(5, "threads", "含子区", "选了频道时，是否连它下面的子区 / 贴子一起收（默认是）", false, false),
                opt(5, "files", "附件", "是否收集图片等附件（默认是）", false, false)));
        return a;
    }

    private static JsonObject cmd(String name, String zh, String desc, String perms, JsonObject... options) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.add("name_localizations", zh(zh));
        o.addProperty("description", desc);
        o.addProperty("type", 1);
        o.add("contexts", single(0)); // 只在服务器里可用
        if (perms != null) o.addProperty("default_member_permissions", perms);
        JsonArray opts = new JsonArray();
        for (JsonObject x : options) opts.add(x);
        if (!opts.isEmpty()) o.add("options", opts);
        return o;
    }

    private static JsonObject opt(int type, String name, String zh, String desc, boolean required, boolean autocomplete) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("name", name);
        o.add("name_localizations", zh(zh));
        o.addProperty("description", desc);
        o.addProperty("required", required);
        if (autocomplete) o.addProperty("autocomplete", true);
        return o;
    }

    private static JsonObject channelOpt(String name, String zh, String desc) {
        JsonObject o = opt(7, name, zh, desc, false, false);
        JsonArray types = new JsonArray();
        types.add(0); // 文字频道
        types.add(5); // 公告频道
        types.add(15); // 论坛频道
        o.add("channel_types", types);
        return o;
    }

    private static JsonObject sub(String name, String zh, String desc, JsonObject... options) {
        JsonObject o = new JsonObject();
        o.addProperty("type", 1);
        o.addProperty("name", name);
        o.add("name_localizations", zh(zh));
        o.addProperty("description", desc);
        JsonArray opts = new JsonArray();
        for (JsonObject x : options) opts.add(x);
        if (!opts.isEmpty()) o.add("options", opts);
        return o;
    }

    private static JsonObject collectChannelOpt() {
        JsonObject o = opt(7, "channel", "频道", "要收集的反馈频道 / 论坛 / 贴子（只能选设置好的反馈频道）", true, false);
        JsonArray types = new JsonArray();
        for (int t : new int[]{0, 2, 5, 10, 11, 12, 13, 15, 16}) types.add(t);
        o.add("channel_types", types);
        return o;
    }

    private static JsonObject pingChannelOpt() {
        JsonObject o = opt(7, "channel", "频道", "发到哪个频道或贴子（不填=当前频道）", false, false);
        JsonArray types = new JsonArray();
        for (int t : new int[]{0, 5, 10, 11, 12}) types.add(t); // 文字、公告、各种贴子/子区
        o.add("channel_types", types);
        return o;
    }

    private static JsonObject zh(String s) {
        JsonObject o = new JsonObject();
        o.addProperty("zh-CN", s);
        o.addProperty("zh-TW", s);
        return o;
    }

    private static JsonArray single(int v) {
        JsonArray a = new JsonArray();
        a.add(v);
        return a;
    }

    // =============== 分发 ===============

    void handle(JsonObject d) {
        // Isolate sensitive responses from the legacy handler's exception/logging path.
        // The only runtime caller is the existing official Gateway INTERACTION_CREATE dispatch.
        if (WebLoginFlow.isCommand(d)) {
            webLogin.handle(d);
            return;
        }
        Ctx ctx = new Ctx(d);
        try {
            BotConfig cfg = plugin.cfg();
            if (ctx.type == 3 && ctx.data.has("custom_id") && ctx.data.get("custom_id").getAsString().startsWith("authlogin|")) {   // 2026-10-07 auth-gate: DM button
                loginButton(ctx);
                return;
            }
            if (ctx.type == 3 && ctx.data.has("custom_id") && ctx.data.get("custom_id").getAsString().startsWith(PlotRules.CODEV)) {   // 2026-10-08 bot-plots: co-developer DM button
                plugin.plots().handle(d);
                return;
            }
            if (!d.has("guild_id") || !cfg.guildId.equals(d.get("guild_id").getAsString())) {
                if (ctx.type != 4) ctx.text("这个机器人只在指定的服务器里工作。");
                return;
            }
            switch (ctx.type) {
                case 2 -> command(ctx);
                case 3 -> component(ctx);
                case 4 -> autocomplete(ctx);
                case 5 -> modal(ctx);
                default -> {
                }
            }
        } catch (DiscordRest.DiscordException e) {
            plugin.getLogger().warning("Discord 交互失败：" + e.getMessage());
            ctx.textQuietly("❌ " + e.friendly());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "处理 Discord 交互出错", e);
            ctx.textQuietly("❌ 出错了：" + e.getClass().getSimpleName() + " " + BotRules.truncate(String.valueOf(e.getMessage()), 300));
        }
    }

    private void command(Ctx ctx) throws Exception {
        String name = ctx.command;
        if (!name.equals("status") && !name.equals("key") && !name.equals("linkpremium") && !name.equals("privacy") && !name.equals("say") && !name.equals("ping") && !PlotDesk.COMMANDS.contains(name) && !ctx.isAdmin()) {   // 2026-10-18 public ping: checked inside ping()   // 2026-10-08 bot-plots: plot commands for everyone (bound accounts checked in PlotDesk)   // 2026-10-08 privacy: for everyone   // 2026-10-07 auth-gate: 领取密钥 is for everyone
            ctx.ephemeral = true;
            ctx.text("⛔ 你没有权限使用这个指令。");
            return;
        }
        switch (name) {
            case "status" -> status(ctx);
            case "key" -> claimKey(ctx);   // 2026-10-07 auth-gate
            case "linkpremium" -> linkPremium(ctx, ctx.option("name"));   // 2026-10-11 account-link
            case "privacy" -> privacy(ctx);   // 2026-10-08 privacy
            case "say" -> say(ctx);   // 2026-10-17 bridge without Message Content Intent
            case "plotapply", "plotjoin", "plotmine" -> plugin.plots().handle(ctx.d);   // 2026-10-08 bot-plots
            case "lookup" -> lookupKeys(ctx);
            case "collect" -> collect(ctx);
            case "announce" -> announceModal(ctx);
            case "promo" -> promo(ctx);   // 2026-10-12 bot-promo
            case "rolepanel" -> rolePanel(ctx);
            case "notifygroup" -> notifyGroup(ctx);
            case "ping" -> ping(ctx);
            case "cmd" -> console(ctx);
            case "ban" -> ban(ctx);
            case "unban" -> unban(ctx);
            case "kick" -> kick(ctx);
            case "banlist" -> banList(ctx);
            default -> ctx.text("未知指令");
        }
    }

    // =============== /status ===============

    private void status(Ctx ctx) throws Exception {
        ctx.defer(false);
        JsonObject embed = plugin.sync(plugin::statusEmbed);
        ctx.reply(message(null, embed));
    }

    // =============== 2026-10-12 bot-promo：/minmin 定时广告 ===============

    private void promo(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        PromoPoster poster = plugin.promo();
        if (poster == null) {
            ctx.text("定时广告还没准备好，请稍后再试。");
            return;
        }
        BotConfig cfg = plugin.cfg();
        if (!PromoRules.mayManage(cfg.promoUsers, ctx.userId())) {   // 2026-10-12 服主：只有服主能设置
            ctx.text(cfg.promoUsers.isEmpty() ? "定时广告还没指定谁能设置：请在机器人 config.yml 的 promo.allowed-users 里填上 Discord 用户 id。"
                    : "定时广告只有服主可以设置。");
            return;
        }
        PromoStore store = poster.store();
        java.time.ZoneId zone = cfg.promoZone;
        String channel = ctx.d.get("channel_id").getAsString();
        String sub = ctx.subcommand();
        Instant now = Instant.now();
        switch (sub == null ? "" : sub) {
            case "set" -> {
                String times = ctx.option("times"), every = ctx.option("every");
                boolean hasTimes = times != null && !times.isBlank();
                if (hasTimes == (every != null)) {
                    ctx.text("「时间」和「间隔」要填一个（只能填一个）。例如 时间:12:00,20:00 或 间隔:3");
                    return;
                }
                PromoRules.Rule rule;
                String text;
                try {
                    rule = hasTimes ? PromoRules.Rule.daily(PromoRules.parseTimes(times)) : PromoRules.Rule.every(Integer.parseInt(every));
                    text = PromoRules.cleanText(ctx.option("text"));
                } catch (IllegalArgumentException e) {
                    ctx.text("❌ " + e.getMessage());
                    return;
                }
                PromoStore.Entry old = store.get(channel);
                PromoStore.Entry e = new PromoStore.Entry(channel, rule, text, ctx.userTag(), PromoRules.initialLastSlot(rule, now, zone),
                        old == null ? 0 : old.lastPostedAt(), null);
                if (!store.put(e)) {
                    ctx.text("定时广告已经有 " + PromoStore.MAX + " 个了，请先关掉不用的。");
                    return;
                }
                ctx.text("✅ 已在 <#" + channel + "> 开启定时广告：" + rule.describe() + "（" + zone.getId() + "）\n"
                        + "下一次：" + PromoRules.fmtSlot(PromoRules.nextSlot(rule, now, zone), zone)
                        + "　内容：" + (text == null ? "默认广告卡片" : "自定义文字 + 地址/在线人数/玩法") + "\n"
                        + "离上一条广告不到 " + cfg.promoMinGapMinutes + " 分钟时会跳过那一次。可以先用「预览」看看效果；机器人需要在这里有「发送消息」「嵌入链接」权限。");
                plugin.adminLog("📣 " + ctx.actorName() + "（<@" + ctx.userId() + ">）在 <#" + channel + "> 设置了定时广告：" + rule.describe());
            }
            case "preview" -> {
                ctx.defer(true);
                PromoStore.Entry e = store.get(channel);
                poster.postNow(channel, e == null ? null : e.text(), now);
                ctx.text("✅ 已发送一条广告" + (e == null ? "（这里还没有开启定时广告）" : ""));
            }
            case "off" -> {
                boolean had = store.remove(channel);
                ctx.text(had ? "✅ 已关闭 <#" + channel + "> 的定时广告。" : "这里没有开启定时广告。");
                if (had) plugin.adminLog("📣 " + ctx.actorName() + "（<@" + ctx.userId() + ">）关闭了 <#" + channel + "> 的定时广告");
            }
            case "list" -> {
                List<PromoStore.Entry> all = store.all();
                if (all.isEmpty()) {
                    ctx.text("还没有定时广告。在想发广告的频道或子区里用 /minmin 定时广告 设置。");
                    return;
                }
                StringBuilder b = new StringBuilder("**定时广告**（" + zone.getId() + "）\n");
                for (PromoStore.Entry e : all) {
                    b.append("• <#").append(e.channelId()).append("> ").append(e.rule().describe())
                            .append(e.text() == null ? " · 默认卡片" : " · 自定义文字");
                    if (e.active()) b.append(" · 下一次 ").append(PromoRules.fmtSlot(PromoRules.nextSlot(e.rule(), now, zone), zone));
                    else b.append(" · ⚠️ 已停用：").append(e.disabled());
                    b.append("\n");
                }
                ctx.text(b.toString());
            }
            default -> ctx.text("未知子指令");
        }
    }

    // =============== /announce ===============

    private void announceModal(Ctx ctx) throws IOException {
        BotConfig cfg = plugin.cfg();
        String channel = ctx.option("channel");
        if (channel == null) channel = cfg.announceChannel.isEmpty() ? ctx.d.get("channel_id").getAsString() : cfg.announceChannel;
        String notify = ctx.option("notify");
        if (notify != null && !notify.equals("everyone") && cfg.entry(notify) == null) {
            ctx.ephemeral = true;
            ctx.text("找不到通知对象「" + notify + "」，请从下拉列表里选。");
            return;
        }
        String tag = ctx.option("tag");
        boolean ingame = "true".equals(ctx.option("ingame"));

        String customId = "ann|" + channel + "|" + (notify == null ? "-" : notify) + "|" + (tag == null ? "-" : tag) + "|" + (ingame ? 1 : 0);
        if (customId.length() > 100) {
            ctx.ephemeral = true;
            ctx.text("参数太长了，请把 config.yml 里这个订阅的 key 改短一点。");
            return;
        }
        JsonArray comps = new JsonArray();
        comps.add(textInput("标题", "title", 1, true, 100, null));
        comps.add(textInput("正文", "body", 2, true, 4000, "支持 Discord 格式：**粗体**、换行、链接"));
        comps.add(textInput("图片链接（可选）", "image", 1, false, 500, "https://..."));
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        data.addProperty("title", "发布公告");
        data.add("components", comps);
        ctx.callback(9, data);
    }

    /** Label(18) 包着 TextInput(4) —— Discord 现行的弹窗写法。 */
    private static JsonObject textInput(String label, String id, int style, boolean required, int max, String placeholder) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", id);
        input.addProperty("style", style);
        input.addProperty("required", required);
        input.addProperty("max_length", max);
        if (placeholder != null) input.addProperty("placeholder", placeholder);
        JsonObject wrap = new JsonObject();
        wrap.addProperty("type", 18);
        wrap.addProperty("label", label);
        wrap.add("component", input);
        return wrap;
    }

    private void modal(Ctx ctx) throws Exception {
        String id = ctx.data.get("custom_id").getAsString();
        if (id.startsWith(PlotRules.MODAL)) {   // 2026-10-08 bot-plots: reject reason
            plugin.plots().handle(ctx.d);
            return;
        }
        if (id.equals("plink|modal")) {   // 2026-10-11 account-link
            Map<String, String> v = new HashMap<>();
            collectValues(ctx.data.get("components"), v);
            linkPremium(ctx, v.getOrDefault("name", "").trim());
            return;
        }
        if (!id.startsWith("ann|")) return;
        if (!ctx.isAdmin()) {
            ctx.ephemeral = true;
            ctx.text("⛔ 你没有权限发布公告。");
            return;
        }
        String[] parts = id.split("\\|");
        String channel = parts[1];
        String notify = parts[2].equals("-") ? null : parts[2];
        String tag = parts[3].equals("-") ? null : parts[3];
        boolean ingame = parts[4].equals("1");
        Map<String, String> values = new HashMap<>();
        collectValues(ctx.data.get("components"), values);
        String title = values.getOrDefault("title", "").trim();
        String body = values.getOrDefault("body", "").trim();
        String image = values.getOrDefault("image", "").trim();
        ctx.defer(true);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", BotRules.truncate(title, 256));
        embed.addProperty("description", BotRules.truncate(body, 4000));
        embed.addProperty("color", COLOR_GOLD);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonObject footer = new JsonObject();
        footer.addProperty("text", plugin.cfg().serverName + " · " + ctx.actorName() + " 发布");
        embed.add("footer", footer);
        boolean imageOk = image.startsWith("https://") || image.startsWith("http://");
        if (imageOk) {
            JsonObject img = new JsonObject();
            img.addProperty("url", image);
            embed.add("image", img);
        }

        String guild = plugin.cfg().guildId;
        BotConfig.PanelRole entry = notify == null || notify.equals("everyone") ? null : plugin.cfg().entry(notify);
        Notifier.Mention mention;
        if (notify == null) mention = Notifier.Mention.none();
        else if (notify.equals("everyone")) mention = Notifier.everyone();
        else if (entry == null) {
            ctx.text("找不到通知对象「" + notify + "」，可能已经从 config.yml 删掉了。");
            return;
        } else if (entry.subscribe()) {
            int n = plugin.subscriptions().count(entry.key());
            if (n > 0) ctx.text("⏳ 正在准备通知 " + n + " 人…");
            mention = plugin.notifier().prepareSubscription(guild, entry, msg -> {
                try {
                    ctx.text(msg);
                } catch (IOException ignored) {
                }
            });
        } else {
            mention = Notifier.role(plugin.resolveRoleId(entry));
        }

        JsonObject msg = message(mention.content(), embed);
        msg.add("allowed_mentions", mention.allowed() != null ? mention.allowed() : noMentions());
        PostResult posted;
        try {
            posted = post(guild, channel, title, tag, msg);
        } catch (IOException e) {
            plugin.notifier().finish(guild, mention, null, null);
            throw e;
        }

        if (ingame) {
            plugin.sync(() -> {
                Component c = Component.text("[公告] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text(title, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
                        .append(Component.newline())
                        .append(Component.text(body, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false));
                Bukkit.broadcast(c);
                return null;
            });
        }
        StringBuilder done = new StringBuilder("✅ 公告已发布：").append(posted.link());
        if (entry != null && entry.subscribe()) {
            done.append(mention.count() > 0 ? "\n已通知「" + entry.name() + "」的 " + mention.count() + " 位订阅者（临时身份组 15 秒后自动删除）"
                    : "\n「" + entry.name() + "」还没有人订阅，所以没有 @ 任何人");
            if (mention.gone() > 0) done.append("\n有 ").append(mention.gone()).append(" 人已经退出服务器，已从名单移除");
        }
        if (ingame) done.append("\n游戏内也已广播");
        if (!image.isEmpty() && !imageOk) done.append("\n（图片链接无效，已忽略）");
        ctx.text(done.toString());
        plugin.adminLog("📢 " + ctx.actorName() + " 发布了公告「" + BotRules.truncate(title, 80) + "」 " + posted.link());
        plugin.notifier().finish(guild, mention, posted.messagePath(), mention.plainText());
    }

    record PostResult(String link, String messagePath) {}

    /** 文字频道：发一条消息；论坛频道：开一个新贴（标题 = 公告标题）。 */
    private PostResult post(String guild, String channel, String title, String tag, JsonObject msg) throws IOException {
        JsonObject ch = plugin.rest().get("/channels/" + channel).getAsJsonObject();
        int type = ch.get("type").getAsInt();
        if (type == 15 || type == 16) {
            JsonObject body = new JsonObject();
            body.addProperty("name", BotRules.truncate(title, 100));
            body.add("message", msg);
            if (tag != null) {
                JsonArray tags = new JsonArray();
                tags.add(tag);
                body.add("applied_tags", tags);
            }
            String thread = plugin.rest().post("/channels/" + channel + "/threads", body).getAsJsonObject().get("id").getAsString();
            return new PostResult("https://discord.com/channels/" + guild + "/" + thread, "/channels/" + thread + "/messages/" + thread);
        }
        String id = plugin.rest().post("/channels/" + channel + "/messages", msg).getAsJsonObject().get("id").getAsString();
        return new PostResult("https://discord.com/channels/" + guild + "/" + channel + "/" + id, "/channels/" + channel + "/messages/" + id);
    }

    private static void collectValues(JsonElement e, Map<String, String> out) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectValues(x, out);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("custom_id") && o.has("value") && !o.get("value").isJsonNull()) {
                out.put(o.get("custom_id").getAsString(), o.get("value").getAsString());
            }
            collectValues(o.get("component"), out);
            collectValues(o.get("components"), out);
        }
    }

    // =============== /rolepanel 与按钮 ===============

    private void rolePanel(Ctx ctx) throws Exception {
        ctx.defer(true);
        String channel = ctx.option("channel");
        if (channel == null) channel = ctx.d.get("channel_id").getAsString();
        List<BotConfig.PanelRole> roles = plugin.resolvePanelRoles();
        if (roles.isEmpty()) {
            ctx.text("面板上还没有任何项目。先用 /minmin 通知组 添加 新建一个通知组。");
            return;
        }
        BotConfig cfg = plugin.cfg();
        PostResult posted = post(cfg.guildId, channel, cfg.panelTitle, null, panelMessage(cfg, roles));
        plugin.subscriptions().addPanel(posted.messagePath());
        ctx.text("✅ 面板已发布：" + posted.link() + "\n以后用 /minmin 通知组 添加或删除时，这个面板会自动更新。");
    }

    /** 面板：上面是常驻身份组，下面是通知组。 */
    static JsonObject panelMessage(BotConfig cfg, List<BotConfig.PanelRole> all) {
        List<BotConfig.PanelRole> roles = new ArrayList<>(), subs = new ArrayList<>();
        for (BotConfig.PanelRole r : all) (r.subscribe() ? subs : roles).add(r);
        StringBuilder desc = new StringBuilder(cfg.panelText);
        if (!roles.isEmpty()) {
            desc.append("\n\n**身份**");
            for (BotConfig.PanelRole r : roles) desc.append(panelLine(r, "<@&" + r.id() + ">"));
        }
        if (!subs.isEmpty()) {
            desc.append("\n\n**通知**（订阅后，发布相关消息时会 @ 你）");
            for (BotConfig.PanelRole r : subs) desc.append(panelLine(r, "**" + r.name() + "**"));
        }
        JsonArray rows = new JsonArray();
        addButtons(rows, roles, 1);
        addButtons(rows, subs, 2);
        if (cfg.keysEnabled && cfg.keysPanelButton && rows.size() < 5) {   // 2026-10-07 auth-gate
            desc.append("\n\n**学习版玩家**：点 🔑 领取登录密钥（每个 Discord 账号只发一次，只有你能看到）");
            desc.append("\n**正版玩家**：进服后点 🔗 关联正版账号，在游戏里点「确认」（可以用 Discord 登录网站，学习版号和正版号会合成一个人）");   // 2026-10-11 account-link
            JsonObject b = new JsonObject();
            b.addProperty("type", 2);
            b.addProperty("style", 2);
            b.addProperty("label", "领取学习版登录密钥");
            b.addProperty("custom_id", "key|claim");
            JsonObject em = new JsonObject();
            em.addProperty("name", "🔑");
            b.add("emoji", em);
            JsonArray row = new JsonArray();
            row.add(b);
            JsonObject link = new JsonObject();   // 2026-10-11 account-link
            link.addProperty("type", 2);
            link.addProperty("style", 2);
            link.addProperty("label", "关联正版账号");
            link.addProperty("custom_id", "plink|open");
            JsonObject le = new JsonObject();
            le.addProperty("name", "🔗");
            link.add("emoji", le);
            row.add(link);
            JsonObject ar = new JsonObject();
            ar.addProperty("type", 1);
            ar.add("components", row);
            rows.add(ar);
        }
        JsonObject embed = new JsonObject();
        embed.addProperty("title", cfg.panelTitle);
        embed.addProperty("description", BotRules.truncate(desc.toString(), 4000));
        embed.addProperty("color", COLOR_GREEN);
        JsonObject msg = message(null, embed);
        msg.add("components", rows);
        msg.add("allowed_mentions", noMentions());
        return msg;
    }

    private static String panelLine(BotConfig.PanelRole r, String label) {
        return "\n" + (r.emoji().isEmpty() ? "•" : r.emoji()) + " " + label + (r.description().isEmpty() ? "" : " — " + r.description());
    }

    /** 每行 5 个按钮；Discord 一条消息最多 5 行。 */
    private static void addButtons(JsonArray rows, List<BotConfig.PanelRole> list, int style) {
        JsonArray row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 5 == 0) {
                if (rows.size() >= 5) return;
                row = new JsonArray();
                JsonObject ar = new JsonObject();
                ar.addProperty("type", 1);
                ar.add("components", row);
                rows.add(ar);
            }
            BotConfig.PanelRole r = list.get(i);
            JsonObject b = new JsonObject();
            b.addProperty("type", 2);
            b.addProperty("style", style);
            b.addProperty("label", BotRules.truncate(r.name(), 80));
            b.addProperty("custom_id", r.subscribe() ? "sub|" + r.key() : "role|" + r.id());
            if (!r.emoji().isEmpty()) {
                JsonObject em = new JsonObject();
                em.addProperty("name", r.emoji());
                b.add("emoji", em);
            }
            row.add(b);
        }
    }

    // =============== /通知组 /通知 ===============

    private void notifyGroup(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        String sub = ctx.subcommand();
        BotConfig cfg = plugin.cfg();
        if ("list".equals(sub)) {
            StringBuilder b = new StringBuilder("**面板项目**\n");
            for (BotConfig.PanelRole r : cfg.panelRoles) {
                b.append(r.emoji().isEmpty() ? "•" : r.emoji()).append(" ").append(r.name()).append(r.subscribe()
                        ? "（通知组 · " + plugin.subscriptions().count(r.key()) + " 人订阅）"
                        : "（常驻身份组 <@&" + r.id() + ">）").append("\n");
            }
            if (cfg.panelRoles.isEmpty()) b.append("（空）");
            ctx.text(b.toString());
            return;
        }
        ctx.defer(true);
        List<BotConfig.PanelRole> list = new ArrayList<>(cfg.panelRoles);
        String done;
        if ("add".equals(sub)) {
            String name = ctx.option("name").trim();
            String emoji = ctx.option("emoji") == null ? "" : ctx.option("emoji").trim();
            String desc = ctx.option("description") == null ? "" : ctx.option("description").trim();
            if (name.isEmpty() || name.length() > 50 || name.contains("|")) {
                ctx.text("名字不能为空，不能超过 50 个字，也不能有 | 符号。");
                return;
            }
            for (BotConfig.PanelRole r : list) {
                if (r.name().equals(name)) {
                    ctx.text("已经有叫「" + name + "」的项目了。");
                    return;
                }
            }
            if (list.size() >= 25) {
                ctx.text("面板最多 25 个按钮，已经满了。先删掉不用的通知组。");
                return;
            }
            String key = "g" + Long.toString(System.currentTimeMillis(), 36);
            list.add(new BotConfig.PanelRole(key, name, "subscribe", "", emoji, desc));
            done = "✅ 已添加通知组「" + name + "」（不占身份组）。";
        } else {
            BotConfig.PanelRole entry = cfg.entry(ctx.option("group"));
            if (entry == null) {
                ctx.text("找不到这个通知组，请从下拉列表里选。");
                return;
            }
            if (!entry.subscribe()) {
                ctx.text("「" + entry.name() + "」是常驻身份组，不能用 /minmin 通知组 删除。");
                return;
            }
            list.removeIf(r -> r.key().equals(entry.key()));
            plugin.subscriptions().drop(entry.key());
            done = "🗑️ 已删除通知组「" + entry.name() + "」和它的订阅名单。";
        }
        plugin.sync(() -> {
            plugin.savePanel(list);
            return null;
        });
        int updated = plugin.refreshPanels();
        ctx.text(done + (updated > 0 ? "\n已自动更新 " + updated + " 个面板。" : "\n还没有发过面板：用 /minmin 身份面板 发布后，大家就能订阅。"));
        plugin.adminLog("🔔 " + ctx.actorName() + "：" + done);
    }

    private void ping(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        BotConfig cfg = plugin.cfg();
        BotConfig.PanelRole entry = cfg.entry(ctx.option("group"));
        String current = ctx.d.get("channel_id").getAsString();
        boolean admin = ctx.isAdmin();
        if (!admin) {   // 2026-10-18 public ping
            String refusal = pingRules.refusal(cfg, entry, ctx.userId(), ctx.option("channel"), current, System.currentTimeMillis());
            if (refusal != null) {
                ctx.text(refusal);
                return;
            }
        }
        if (entry == null) {
            ctx.text("找不到这个通知组，请从下拉列表里选。");
            return;
        }
        String text = ctx.option("message");
        String channel = ctx.option("channel");
        if (channel == null) channel = current;
        String guild = cfg.guildId;
        ctx.defer(true);
        Notifier.Mention m;
        if (entry.subscribe()) {
            m = plugin.notifier().prepareSubscription(guild, entry, msg -> {
                try {
                    ctx.text(msg);
                } catch (IOException ignored) {
                }
            });
            if (m.content() == null) {
                ctx.text("「" + entry.name() + "」还没有人订阅，没有可以 @ 的人。");
                return;
            }
        } else {
            m = Notifier.role(plugin.resolveRoleId(entry));
        }
        String tail = !admin ? PingRules.tail(ctx.userId(), text, cfg.pingMaxLength)   // 2026-10-18 public ping: show who called
                : text == null || text.isBlank() ? "" : " " + BotRules.truncate(text.trim(), 1800);
        JsonObject msg = message(m.content() + tail, null);
        msg.add("allowed_mentions", m.allowed());
        String id;
        try {
            id = plugin.rest().post("/channels/" + channel + "/messages", msg).getAsJsonObject().get("id").getAsString();
        } catch (DiscordRest.DiscordException e) {
            plugin.notifier().finish(guild, m, null, null);
            if (e.code == 50008 || e.code == 50024) {
                ctx.text("这里不能直接发消息。论坛请进到具体的贴子里再用 /minmin 通知。");
                return;
            }
            throw e;
        }
        if (!admin) pingRules.record(ctx.userId(), entry.key(), System.currentTimeMillis());   // 2026-10-18 public ping: cooldown only after it was posted
        String link = "https://discord.com/channels/" + guild + "/" + channel + "/" + id;
        ctx.text("✅ 已 @ 「" + entry.name() + "」" + (m.count() > 0 ? "（" + m.count() + " 人）" : "") + "：" + link
                + (m.gone() > 0 ? "\n有 " + m.gone() + " 人已经退出服务器，已从名单移除" : ""));
        plugin.adminLog("📣 " + ctx.actorName() + " @ 了「" + entry.name() + "」 " + link);
        plugin.notifier().finish(guild, m, "/channels/" + channel + "/messages/" + id, m.plainText() + tail);
    }

    private void component(Ctx ctx) throws IOException {
        String id = ctx.data.get("custom_id").getAsString();
        if (PlotDesk.ownsComponent(id)) {   // 2026-10-08 bot-plots: [同意]/[驳回]
            plugin.plots().handle(ctx.d);
            return;
        }
        if (id.equals("plink|open")) {   // 2026-10-11 account-link: panel button → ask for the 正版 name
            JsonArray comps = new JsonArray();
            comps.add(textInput("你的正版游戏名", "name", 1, true, 16, "要先进服在线"));
            JsonObject data = new JsonObject();
            data.addProperty("custom_id", "plink|modal");
            data.addProperty("title", "关联正版账号");
            data.add("components", comps);
            ctx.callback(9, data);
            return;
        }
        ctx.ephemeral = true;
        if (id.equals("key|claim")) {   // 2026-10-07 auth-gate: panel button
            try {
                claimKey(ctx);
            } catch (Exception e) {
                throw e instanceof IOException io ? io : new IOException(e);
            }
            return;
        }
        if (id.startsWith("sub|")) {
            BotConfig.PanelRole entry = plugin.cfg().entry(id.substring(4));
            if (entry == null || !entry.subscribe()) {
                ctx.text("这个按钮已经失效了，请联系管理员重新发布面板。");
                return;
            }
            boolean now = plugin.subscriptions().toggle(entry.key(), ctx.userId());
            ctx.text(now ? "✅ 已订阅「" + entry.name() + "」。发布相关公告时会 @ 你。"
                    : "已取消订阅「" + entry.name() + "」。");
            return;
        }
        if (!id.startsWith("role|")) return;
        String roleId = id.substring(5);
        if (!plugin.cfg().hasPanelRole(roleId)) {
            ctx.text("这个按钮已经失效了，请联系管理员重新发布面板。");
            return;
        }
        ctx.defer(true);
        String userId = ctx.userId();
        boolean has = false;
        for (JsonElement r : ctx.d.getAsJsonObject("member").getAsJsonArray("roles")) {
            if (r.getAsString().equals(roleId)) has = true;
        }
        String path = "/guilds/" + plugin.cfg().guildId + "/members/" + userId + "/roles/" + roleId;
        if (has) {
            plugin.rest().delete(path);
            ctx.text("已取消身份组 <@&" + roleId + ">");
        } else {
            plugin.rest().put(path, null);
            ctx.text("✅ 已领取身份组 <@&" + roleId + ">");
        }
    }

    // =============== /cmd ===============

    private void console(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        String command = BotRules.stripSlash(ctx.option("command"));
        if (command.isEmpty()) {
            ctx.text("指令不能为空。");
            return;
        }
        if (BotRules.isBlocked(command, plugin.cfg().blockedCommands)) {
            ctx.text("⛔ `" + BotRules.commandRoot(command) + "` 被禁止从 Discord 执行（config.yml → commands.blocked）。");
            return;
        }
        ctx.defer(true);
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        boolean ok = plugin.sync(() -> Bukkit.dispatchCommand(
                Bukkit.createCommandSender(c -> out.add(PlainTextComponentSerializer.plainText().serialize(c))), command));
        Thread.sleep(800); // 有些指令的反馈是稍后才发出的
        String text;
        synchronized (out) {
            text = String.join("\n", out);
        }
        if (text.isBlank()) text = ok ? "（指令已执行，没有输出）" : "（指令不存在或执行失败）";
        ctx.text("`/" + BotRules.truncate(command, 200).replace("`", "'") + "`\n```\n"
                + BotRules.escapeCodeBlock(BotRules.truncate(text, 1700)) + "\n```");
        plugin.getLogger().info(ctx.actorName() + " 通过 Discord 执行：/" + command);
        plugin.adminLog("🛠️ " + ctx.actorName() + " 执行了 `/" + BotRules.truncate(command, 300).replace("`", "'") + "`");
    }

    // =============== /ban /unban /kick /banlist ===============

    private void ban(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        String name = ctx.option("player");
        String reason = ctx.option("reason");
        if (reason == null || reason.isBlank()) reason = "违反服务器规则";
        String member = ctx.option("member");   // 2026-10-07 auth-gate
        if (member != null || name == null) {
            banMember(ctx, member, reason);
            return;
        }
        if (!BotRules.validPlayerName(name)) {
            ctx.text("玩家名格式不对（只能是 1~16 位字母、数字、下划线）。");
            return;
        }
        Duration duration;
        try {
            duration = BotRules.parseDuration(ctx.option("duration"));
        } catch (IllegalArgumentException e) {
            ctx.text("时长格式看不懂：`" + ctx.option("duration") + "`。示例：30m、12h、7d、1d12h，不填=永久。");
            return;
        }
        ctx.defer(true);
        String source = "Discord:" + ctx.actorName();
        Date expires = duration == null ? null : Date.from(Instant.now().plus(duration));
        String finalReason = reason;
        // 2026-10-07 auth-gate: offline-mode servers — resolve the REAL target (online / bound cracked account / cache / Mojang
        // premium UUID) instead of Bukkit.getOfflinePlayer(name), which in offline mode would give the offline UUID of a premium name.
        BanTarget t = resolveBanTarget(name);
        HubAuthKeys keys = plugin.authKeys();
        String result;
        if (t.discordId() != null && keys != null) {
            Map<String, Object> r = keys.banDiscord(t.discordId(), finalReason, ctx.actorName(), expires == null ? 0 : expires.getTime());
            result = "（学习版绑定账号：Discord <@" + t.discordId() + "> 一起封禁，不能再领密钥；封了 " + r.get("names") + "）";
        } else {
            result = plugin.sync(() -> {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    online.ban(finalReason, expires, source, true);
                    return "（玩家在线，已被踢出）";
                }
                profileBanList().addBan(Bukkit.createProfile(t.uuid(), t.name()), finalReason, expires, source);
                return t.note();
            });
        }
        String shown = t.name();
        ctx.text("🔨 已封禁 **" + shown + "**，时长：" + BotRules.formatDuration(duration) + "，原因：" + reason + result);
        plugin.adminLog("🔨 " + ctx.actorName() + " 封禁了 **" + shown + "**（" + BotRules.formatDuration(duration) + "）原因：" + reason);
    }

    private void unban(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        String name = ctx.option("player");
        String member = ctx.option("member");   // 2026-10-07 auth-gate
        HubAuthKeys keys = plugin.authKeys();
        if (member != null || name == null) {
            if (member == null) {
                ctx.text("请填「玩家」或「成员」。");
                return;
            }
            if (keys == null) {
                ctx.text("游戏服务器的密钥功能不可用（MiniGameHub 没有运行）。");
                return;
            }
            ctx.defer(true);
            Map<String, Object> r = keys.unbanDiscord(member, ctx.actorName());
            if (!"ok".equals(r.get("status"))) {
                ctx.text("<@" + member + "> 没有被封禁。");
                return;
            }
            ctx.text("✅ 已解封 Discord <@" + member + ">，并解封他绑定的游戏号：" + r.get("names"));
            plugin.adminLog("✅ " + ctx.actorName() + " 解封了 Discord <@" + member + ">（游戏号 " + r.get("names") + "）");
            return;
        }
        if (keys != null) {   // a bound cracked account: lift the Discord ban too (both ways)
            Map<String, Object> rp = keys.resolvePlayer(name);
            if (rp.get("discordId") instanceof String did) {
                ctx.defer(true);
                Map<String, Object> r = keys.unbanDiscord(did, ctx.actorName());
                if ("ok".equals(r.get("status"))) {
                    ctx.text("✅ 已解封 **" + name + "**（学习版绑定账号，Discord <@" + did + "> 一起解封）");
                    plugin.adminLog("✅ " + ctx.actorName() + " 解封了 **" + name + "** 和 Discord <@" + did + ">");
                    return;
                }
            }
        }
        if (!ctx.deferred) ctx.defer(true);
        String found = plugin.sync(() -> {
            for (BanEntry<?> e : profileBans()) {
                PlayerProfile p = target(e);
                if (p != null && p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                    profileBanList().pardon(p);
                    return p.getName();
                }
            }
            return null;
        });
        if (found == null) {
            ctx.text("**" + name + "** 不在封禁列表里。");
            return;
        }
        ctx.text("✅ 已解封 **" + found + "**");
        plugin.adminLog("✅ " + ctx.actorName() + " 解封了 **" + found + "**");
    }

    private void kick(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        String name = ctx.option("player");
        String reason = ctx.option("reason");
        String finalReason = reason == null || reason.isBlank() ? "你被管理员请出了服务器" : reason;
        ctx.defer(true);
        String kicked = plugin.sync(() -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) return null;
            p.kick(Component.text(finalReason));
            return p.getName();
        });
        if (kicked == null) {
            ctx.text("**" + name + "** 现在不在线。");
            return;
        }
        ctx.text("👢 已踢出 **" + kicked + "**，原因：" + finalReason);
        plugin.adminLog("👢 " + ctx.actorName() + " 踢出了 **" + kicked + "**，原因：" + finalReason);
    }

    private void banList(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        ctx.defer(true);
        List<String> lines = plugin.sync(() -> {
            List<BanEntry<?>> entries = profileBans();
            entries.sort(Comparator.comparing((BanEntry<?> e) -> e.getCreated()).reversed());
            List<String> out = new ArrayList<>();
            for (BanEntry<?> e : entries) {
                PlayerProfile p = target(e);
                String n = p == null || p.getName() == null ? "(未知玩家)" : p.getName();
                String until = e.getExpiration() == null ? "永久" : "<t:" + e.getExpiration().toInstant().getEpochSecond() + ":f> 到期";
                out.add("• **" + n + "** — " + BotRules.truncate(String.valueOf(e.getReason()), 80) + "（" + until + "）");
            }
            return out;
        });
        if (lines.isEmpty()) {
            ctx.text("封禁列表是空的。");
            return;
        }
        StringBuilder b = new StringBuilder("**封禁列表**（共 " + lines.size() + " 人）\n");
        for (String l : lines) {
            if (b.length() + l.length() > 1900) {
                b.append("\n…还有更多");
                break;
            }
            b.append(l).append("\n");
        }
        ctx.text(b.toString());
    }

    private static ProfileBanList profileBanList() {
        return Bukkit.getBanList(BanListType.PROFILE);
    }

    /** 主线程调用。 */
    private static List<BanEntry<?>> profileBans() {
        return new ArrayList<>(profileBanList().getEntries());
    }

    private static PlayerProfile target(BanEntry<?> e) {
        return e.getBanTarget() instanceof PlayerProfile p ? p : null;
    }

    // =============== 2026-10-07 auth-gate：学习版密钥 / 按 Discord 封禁 / 正版 UUID ===============

    record BanTarget(java.util.UUID uuid, String name, String discordId, String note) {
    }

    /**
     * 封禁对象：在线玩家 → 绑定密钥的学习版号（离线 UUID + Discord）→ 服务器缓存 → Mojang 正版 UUID → 都没有才用离线 UUID。
     * worker 线程调用（可能要访问 Mojang）。
     */
    BanTarget resolveBanTarget(String name) throws Exception {
        HubAuthKeys keys = plugin.authKeys();
        if (keys != null) {
            Map<String, Object> r = keys.resolvePlayer(name);
            String kind = String.valueOf(r.get("kind"));
            String did = r.get("discordId") instanceof String s ? s : null;
            if (r.get("uuid") instanceof String u && !kind.equals("unknown")) {
                return new BanTarget(java.util.UUID.fromString(u), String.valueOf(r.get("name")), kind.equals("bound-cracked") || kind.equals("online") ? did : null, "");
            }
        } else {
            OfflinePlayer c = plugin.sync(() -> Bukkit.getOfflinePlayerIfCached(name));
            if (c != null) return new BanTarget(c.getUniqueId(), c.getName() == null ? name : c.getName(), null, "");
        }
        String[] premium = MojangLookup.uuidOf(name);
        if (premium != null) return new BanTarget(java.util.UUID.fromString(premium[0]), premium[1], null, "\n（这个名字是正版账号，已按正版 UUID 封禁；他还没进过服务器）");
        java.util.UUID off = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new BanTarget(off, name, null, "\n⚠️ 这个玩家从没进过服务器，也不是正版名字（或查不到 Mojang），已按学习版 UUID 封禁，请确认名字拼写正确。");
    }

    private void banMember(Ctx ctx, String member, String reason) throws Exception {
        if (member == null) {
            ctx.text("请填「玩家」或「成员」。");
            return;
        }
        HubAuthKeys keys = plugin.authKeys();
        if (keys == null) {
            ctx.text("游戏服务器的密钥功能不可用（MiniGameHub 没有运行），不能按 Discord 成员封禁。");
            return;
        }
        Duration duration;
        try {
            duration = BotRules.parseDuration(ctx.option("duration"));
        } catch (IllegalArgumentException e) {
            ctx.text("时长格式看不懂：`" + ctx.option("duration") + "`。示例：30m、12h、7d、1d12h，不填=永久。");
            return;
        }
        ctx.defer(true);
        long until = duration == null ? 0 : Instant.now().plus(duration).toEpochMilli();
        Map<String, Object> r = keys.banDiscord(member, reason, ctx.actorName(), until);
        if (!"ok".equals(r.get("status"))) {
            ctx.text("❌ 封禁失败：" + r.get("status"));
            return;
        }
        Object names = r.get("names");
        String acc = names instanceof List<?> l && !l.isEmpty() ? "，绑定的游戏号 " + String.join("、", l.stream().map(String::valueOf).toList()) + " 已封禁" : "（还没有绑定游戏号）";
        ctx.text("🔨 已封禁 Discord <@" + member + ">" + acc + "，不能再领密钥。时长：" + BotRules.formatDuration(duration) + "，原因：" + reason);
        plugin.adminLog("🔨 " + ctx.actorName() + " 封禁了 Discord <@" + member + ">" + acc + "（" + BotRules.formatDuration(duration) + "）原因：" + reason);
    }

    private void claimKey(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        BotConfig cfg = plugin.cfg();
        if (!cfg.keysEnabled) {
            ctx.text("学习版密钥功能没有开启。");
            return;
        }
        HubAuthKeys keys = plugin.authKeys();
        if (keys == null) {
            ctx.text("游戏服务器的密钥功能现在不可用（MiniGameHub 没有运行），请稍后再试。");
            return;
        }
        JsonObject m = ctx.member();
        String joined = m.has("joined_at") && !m.get("joined_at").isJsonNull() ? m.get("joined_at").getAsString() : null;
        String why = KeyRules.ineligible(ctx.userId(), joined, System.currentTimeMillis(), cfg.keysMinAccountDays, cfg.keysMinMemberDays);
        if (why != null) {
            ctx.text("⏳ " + why);
            return;
        }
        ctx.defer(true);
        Map<String, Object> r = keys.claim(ctx.userId(), ctx.userTag());
        Boolean dm = null;
        if (r.get("key") instanceof String key && cfg.keysDmCopy) dm = plugin.sendDm(ctx.userId(), Interactions.message(KeyRules.dmCopy(key), null));
        ctx.text(KeyRules.claimReply(r, dm));   // the key goes only into this ephemeral reply (+ the DM copy); never logged
        if ("new".equals(r.get("status"))) plugin.adminLog("🔑 " + ctx.actorName() + "（<@" + ctx.userId() + ">）领取了学习版登录密钥");
    }

    /** 2026-10-11 account-link: 「关联正版账号」 — the game shows the player a confirm dialog; the result comes back as a DM. */
    private void linkPremium(Ctx ctx, String name) throws Exception {
        ctx.ephemeral = true;
        HubAuthKeys keys = plugin.authKeys();
        if (keys == null) {
            ctx.text("游戏服务器现在不可用（MiniGameHub 没有运行），请稍后再试。");
            return;
        }
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            ctx.text("请填正确的正版游戏名（字母、数字、下划线，最多 16 个字符）。");
            return;
        }
        ctx.defer(true);
        ctx.text(KeyRules.premiumReply(keys.requestPremiumLink(ctx.userId(), ctx.userTag(), name)));
    }

    private void lookupKeys(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        HubAuthKeys keys = plugin.authKeys();
        if (keys == null) {
            ctx.text("游戏服务器的密钥功能不可用（MiniGameHub 没有运行）。");
            return;
        }
        String q = ctx.option("member") != null ? ctx.option("member") : ctx.option("player");
        if (q == null) {
            ctx.text("请填「玩家」或「成员」。");
            return;
        }
        List<Map<String, Object>> rows = keys.lookup(q);
        ctx.text(rows.isEmpty() ? "没有找到 " + (ctx.option("member") != null ? "<@" + q + ">" : "**" + q + "**") + " 的密钥记录。"
                : BotRules.truncate(String.join("\n", rows.stream().map(KeyRules::describe).toList()), 1900));
    }

    /** DM 里的「是我 / 不是我」按钮（可选的 Discord 登录确认）。 */
    private void loginButton(Ctx ctx) throws IOException {
        String[] p = ctx.data.get("custom_id").getAsString().split("\\|");
        HubAuthKeys keys = plugin.authKeys();
        String text;
        if (p.length < 3 || keys == null) text = "这个按钮已经失效了。";
        else {
            Map<String, Object> r;
            try {
                r = keys.confirmLogin(p[2], ctx.userId(), p[1].equals("yes"));
            } catch (Exception e) {
                r = Map.of("status", "error");
            }
            text = switch (String.valueOf(r.get("status"))) {
                case "ok" -> "✅ 已登录 **" + r.get("name") + "**，玩得开心！";
                case "denied" -> "已拒绝，用 **" + r.get("name") + "** 登录的人已被踢出。如果是别人知道了你的名字，可以找管理员看看。";
                case "offline" -> "这个玩家已经下线了。";
                case "wrong-user" -> "这个按钮不是给你的。";
                default -> "这个登录请求已经过期了，重新进服会再发一次。";
            };
        }
        JsonObject data = new JsonObject();
        data.addProperty("content", text);
        data.add("components", new JsonArray());
        ctx.callback(7, data);   // edit the DM: buttons gone
    }

    /** 优先用服务器缓存；没有缓存时向 Mojang 查询（可能慢，所以在 worker 线程里做）。 */
    private static OfflinePlayer lookup(String name) {
        OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(name);
        return p != null ? p : Bukkit.getOfflinePlayer(name);
    }

    // =============== 自动补全 ===============

    private void autocomplete(Ctx ctx) throws Exception {
        String cmd = ctx.command;
        if (PlotDesk.COMMANDS.contains(cmd)) {   // 2026-10-08 bot-plots
            plugin.plots().handle(ctx.d);
            return;
        }
        String focused = "";
        String typed = "";
        for (JsonObject o : ctx.leafOptions()) {
            if (o.has("focused") && o.get("focused").getAsBoolean()) {
                focused = o.get("name").getAsString();
                typed = o.get("value").getAsString();
            }
        }
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String[]> pairs = new ArrayList<>(); // {显示名, 值}
        if (focused.equals("group")) {
            boolean onlyGroups = cmd.equals("notifygroup");
            boolean onlyPublic = cmd.equals("ping") && !ctx.isAdmin();   // 2026-10-18 public ping
            for (BotConfig.PanelRole r : plugin.cfg().panelRoles) {
                if (onlyGroups && !r.subscribe()) continue;
                if (onlyPublic && !plugin.cfg().pingPublic.contains(r.key())) continue;
                String label = r.subscribe() ? r.name() + "（通知组 · " + plugin.subscriptions().count(r.key()) + " 人）" : r.name() + "（常驻身份组）";
                pairs.add(new String[]{label, r.key()});
            }
        } else if (cmd.equals("announce") && focused.equals("notify")) {
            for (BotConfig.PanelRole r : plugin.cfg().panelRoles) {
                String label = r.subscribe() ? r.name() + "（订阅 · " + plugin.subscriptions().count(r.key()) + " 人）" : r.name() + "（身份组）";
                pairs.add(new String[]{label, r.key()});
            }
            pairs.add(new String[]{"@everyone（所有人）", "everyone"});
        } else if (cmd.equals("announce") && focused.equals("tag")) {
            String channel = ctx.option("channel");
            if (channel == null) channel = plugin.cfg().announceChannel;
            if (!channel.isEmpty()) {
                JsonObject ch = plugin.rest().get("/channels/" + channel).getAsJsonObject();
                if (ch.has("available_tags")) {
                    for (JsonElement t : ch.getAsJsonArray("available_tags")) {
                        JsonObject o = t.getAsJsonObject();
                        String emoji = o.has("emoji_name") && !o.get("emoji_name").isJsonNull() ? o.get("emoji_name").getAsString() + " " : "";
                        pairs.add(new String[]{emoji + o.get("name").getAsString(), o.get("id").getAsString()});
                    }
                }
            }
        } else {
            List<String> names = plugin.sync(() -> {
                List<String> out = new ArrayList<>();
                if (cmd.equals("unban")) {
                    for (BanEntry<?> e : profileBans()) {
                        PlayerProfile p = target(e);
                        if (p != null && p.getName() != null) out.add(p.getName());
                    }
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                return out;
            });
            names.sort(String.CASE_INSENSITIVE_ORDER);
            for (String n : names) pairs.add(new String[]{n, n});
        }
        JsonArray choices = new JsonArray();
        pairs.stream().filter(p -> p[0].toLowerCase(Locale.ROOT).contains(prefix)).limit(25).forEach(p -> {
            JsonObject c = new JsonObject();
            c.addProperty("name", BotRules.truncate(p[0], 100));
            c.addProperty("value", p[1]);
            choices.add(c);
        });
        JsonObject data = new JsonObject();
        data.add("choices", choices);
        ctx.callback(8, data);
    }

    // =============== /minmin 隐私（2026-10-08 privacy） ===============

    private void privacy(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        OptOut o = plugin.optOut();
        String sub = ctx.subcommand();
        if ("optout".equals(sub)) {
            o.set(ctx.userId(), true);
            ctx.text("✅ 已退出：以后机器人不会再收集你的消息（反馈汇总），聊天互通也不会再把你的消息转发到游戏里。"
                    + "想删除已经收集的内容，请私信管理员。随时可以用 /minmin 隐私 恢复 改回来。");
        } else if ("optin".equals(sub)) {
            o.set(ctx.userId(), false);
            ctx.text("✅ 已恢复：你的消息会照常出现在反馈汇总和聊天互通里。");
        } else {
            ctx.text(o.has(ctx.userId()) ? "你现在是「已退出」：机器人不收集你的消息，也不转发到游戏里。"
                    : "你现在是「正常」：你在反馈频道的消息可能会被整理进反馈汇总；在互通频道的消息会转发到游戏里。可以用 /minmin 隐私 退出。");
        }
    }

    // =============== 2026-10-17 /minmin 说：Discord → 游戏公屏，不需要 Message Content Intent ===============

    static final int SAY_MAX = 200;

    /** 不能发时的提示；null = 可以发。 */
    static String sayRefusal(BotConfig cfg, String channelId, boolean optedOut) {
        if (!cfg.bridgeChat || cfg.bridgeChannel.isEmpty()) return "聊天互通没有打开。";
        if (!cfg.bridgeChannel.equals(channelId)) return "请到聊天互通频道 <#" + cfg.bridgeChannel + "> 里用这个指令。";
        if (optedOut) return "你用 /minmin 隐私 退出了互通，消息不会发到游戏里。可以用 /minmin 隐私 恢复 改回来。";
        return null;
    }

    private void say(Ctx ctx) throws Exception {
        String text = ctx.option("text");
        text = text == null ? "" : text.replace('\n', ' ').trim();
        String refusal = text.isEmpty() ? "内容不能为空。" : sayRefusal(plugin.cfg(), ctx.d.get("channel_id").getAsString(), plugin.optOut().has(ctx.userId()));
        if (refusal != null) {
            ctx.ephemeral = true;
            ctx.text(refusal);
            return;
        }
        String name = ctx.actorName();
        plugin.relayToGame(BotRules.truncate(name, 32), BotRules.truncate(text, SAY_MAX));
        JsonObject msg = message("💬 **" + name.replace("_", "\\_").replace("*", "\\*") + "** → 游戏：" + text, null);
        msg.add("allowed_mentions", noMentions());
        ctx.reply(msg);
    }

    // =============== /minmin 收集（2026-10-07 message collect） ===============

    private void collect(Ctx ctx) throws Exception {
        ctx.ephemeral = true;
        BotConfig cfg = plugin.cfg();
        java.time.Instant[] range;
        try {
            range = BotRules.collectRange(ctx.option("from"), ctx.option("to"), cfg.collectZone, java.time.ZonedDateTime.now(cfg.collectZone));
        } catch (IllegalArgumentException e) {
            ctx.text("时间看不懂。可以写：今天、昨天、3d（最近 3 天）、12h、2026-10-06、10-06 20:00、20:00（日本时间）。");
            return;
        }
        String host = collectHost(ctx);
        if (host == null) {
            ctx.text("私密子区只能开在普通文字频道里。请在文字频道（或文字频道下的子区）里使用，"
                    + "或者让管理员在控制台执行 `discordbot set channels.collect <频道ID>` 指定一个固定频道。");
            return;
        }
        if (!plugin.collectLock().tryAcquire()) {
            ctx.text("已经有一个收集正在进行，等它完成再来。");
            return;
        }
        try {
            ctx.defer(true);
            plugin.cleanupExports();   // 2026-10-08 privacy
            Collector.Request req = new Collector.Request(cfg.guildId, null, ctx.option("channel"),
                    range[0], range[1], !"false".equals(ctx.option("files")), !"false".equals(ctx.option("threads")), ctx.actorName());
            Collector.Result r = plugin.collector().collect(req, msg -> {
                try {
                    ctx.text(msg);
                } catch (IOException ignored) {
                    // 交互令牌过期（超过 15 分钟）就不再显示进度，结果照样发到子区
                }
            });
            String thread;
            try {
                thread = postCollectResult(host, ctx.userId(), req, r);
            } finally {
                Collector.deleteDir(r.dir(), null);   // 2026-10-08 privacy: no local copy
            }
            ctx.text("✅ 收集完成：" + r.messages() + " 条消息、" + r.files().size() + " 个附件 → <#" + thread + ">");
            plugin.adminLog("📥 " + ctx.actorName() + " 收集了消息：" + r.scope() + "（" + r.messages() + " 条）");
        } catch (IllegalArgumentException e) {   // 2026-10-08 privacy: not a feedback channel
            ctx.text("⛔ " + e.getMessage());
        } finally {
            plugin.collectLock().release();
        }
    }

    /** 私密子区开在哪：当前文字频道；在子区里就用它的上级文字频道；都不行就用 channels.collect。 */
    private String collectHost(Ctx ctx) throws IOException {
        String cid = ctx.d.get("channel_id").getAsString();
        JsonObject ch = ctx.d.has("channel") && ctx.d.get("channel").isJsonObject() ? ctx.d.getAsJsonObject("channel")
                : plugin.rest().get("/channels/" + cid).getAsJsonObject();
        int type = ch.has("type") ? ch.get("type").getAsInt() : -1;
        if (type == 0) return cid;
        if (Collector.THREAD_TYPES.contains(type) && ch.has("parent_id") && !ch.get("parent_id").isJsonNull()) {
            String pid = ch.get("parent_id").getAsString();
            JsonObject parent = plugin.rest().get("/channels/" + pid).getAsJsonObject();
            if (parent.get("type").getAsInt() == 0) return pid;
        }
        String fallback = plugin.cfg().collectChannel;
        return fallback.isEmpty() ? null : fallback;
    }

    /** 开私密子区，发摘要 + 记录文件 + 附件。返回子区 id。 */
    String postCollectResult(String host, String userId, Collector.Request req, Collector.Result r) throws IOException {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(plugin.cfg().collectZone);
        JsonObject body = new JsonObject();
        body.addProperty("name", BotRules.truncate("📥 收集 " + f.format(req.from()) + "～" + f.format(req.to()) + " " + r.scope(), 100));
        body.addProperty("type", 12);
        body.addProperty("invitable", false);
        body.addProperty("auto_archive_duration", 10080);
        String thread = plugin.rest().post("/channels/" + host + "/threads", body).getAsJsonObject().get("id").getAsString();
        plugin.rest().put("/channels/" + thread + "/thread-members/" + userId, null);
        String path = "/channels/" + thread + "/messages";
        long limit = uploadLimit(req.guildId());

        StringBuilder d = new StringBuilder();
        d.append("**范围**：").append(r.scope()).append("\n");
        d.append("**时间**：").append(f.format(req.from())).append(" ～ ").append(f.format(req.to())).append("（").append(plugin.cfg().collectZone.getId()).append("）\n");
        d.append("**结果**：").append(r.messages()).append(" 条消息，").append(r.authors()).append(" 位发言者，")
                .append(r.files().size()).append(" 个附件").append(r.bigFiles().isEmpty() ? "" : "（另有 " + r.bigFiles().size() + " 个太大，见下方链接）").append("\n");
        if (!r.byAuthor().isEmpty()) {
            d.append("\n**发言最多**：");
            r.byAuthor().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                    .forEach(e -> d.append(e.getKey()).append("（").append(e.getValue()).append("） "));
            d.append("\n**最热闹的位置**：");
            r.byLocation().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                    .forEach(e -> d.append(e.getKey()).append("（").append(e.getValue()).append("） "));
            d.append("\n");
        }
        if (r.truncated()) d.append("\n⚠️ 超过数量上限，只收了最早的一部分，请缩小时间段再收一次。");
        if (r.emptyContent() > 0) d.append("\n⚠️ 有 ").append(r.emptyContent()).append(" 条消息内容是空的：请在 Developer Portal → Bot 打开 **Message Content Intent**。");
        if (!r.skipped().isEmpty()) d.append("\n机器人看不到、跳过了：").append(BotRules.truncate(String.join("、", r.skipped()), 300));
        d.append("\n\n收集结果只保存在这个私密子区里，服务器上不留副本。");   // 2026-10-08 privacy
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "📥 消息收集完成");
        embed.addProperty("description", BotRules.truncate(d.toString(), 4000));
        embed.addProperty("color", COLOR_GREEN);
        JsonObject summary = message(null, embed);
        summary.add("allowed_mentions", noMentions());

        List<java.nio.file.Path> docs = new ArrayList<>();
        long md = java.nio.file.Files.size(r.transcript()), js = java.nio.file.Files.size(r.json());
        if (md <= limit) docs.add(r.transcript());
        if (md + js <= limit) docs.add(r.json());
        if (docs.isEmpty()) {
            embed.addProperty("description", BotRules.truncate(d + "\n\n记录文件太大，Discord 放不下。请缩小时间段，或者分贴子分别收集。", 4000));
            plugin.rest().post(path, summary);
        } else {
            plugin.rest().postFiles(path, summary, docs);
        }

        long[] sizes = new long[r.files().size()];
        for (int i = 0; i < sizes.length; i++) sizes[i] = r.files().get(i).size();
        int n = 0;
        for (List<Integer> batch : BotRules.batches(sizes, limit)) {
            StringBuilder c = new StringBuilder();
            List<java.nio.file.Path> paths = new ArrayList<>();
            for (int i : batch) {
                Collector.SavedFile sf = r.files().get(i);
                paths.add(sf.path());
                c.append(++n).append(". ").append(sf.name()).append(" — ").append(sf.author()).append(" ")
                        .append(f.format(sf.time())).append(" [原消息](<").append(sf.messageLink()).append(">)\n");
            }
            JsonObject m = message(BotRules.truncate(c.toString(), 2000), null);
            m.add("allowed_mentions", noMentions());
            plugin.rest().postFiles(path, m, paths);
        }
        List<Collector.BigFile> big = new ArrayList<>(r.bigFiles());
        for (Collector.SavedFile sf : r.files()) if (sf.size() > limit) big.add(new Collector.BigFile(sf.name(), sf.size(), sf.messageLink()));
        StringBuilder c = new StringBuilder();
        for (Collector.BigFile b : big) {
            String line = "📎 " + b.name() + "（" + BotRules.formatBytes(b.size()) + "）[原消息](<" + b.messageLink() + ">)\n";
            if (c.length() + line.length() > 1900) {
                JsonObject m = message(c.toString(), null);
                m.add("allowed_mentions", noMentions());
                plugin.rest().post(path, m);
                c.setLength(0);
            }
            c.append(line);
        }
        if (c.length() > 0) {
            JsonObject m = message("**太大没法重新上传的附件**（请看原消息）：\n" + c, null);
            m.add("allowed_mentions", noMentions());
            plugin.rest().post(path, m);
        }
        return thread;
    }

    /** Discord 单条消息的上传上限（按服务器加成等级），留一点余量。2026-10-12 bot-posting: shared with console posts via DiscordRest. */
    private long uploadLimit(String guild) {
        return plugin.rest().uploadLimit(guild);
    }

    // =============== 工具 ===============

    static JsonObject message(String content, JsonObject embed) {
        JsonObject m = new JsonObject();
        if (content != null) m.addProperty("content", content);
        if (embed != null) {
            JsonArray es = new JsonArray();
            es.add(embed);
            m.add("embeds", es);
        }
        return m;
    }

    static JsonObject noMentions() {
        JsonObject a = new JsonObject();
        a.add("parse", new JsonArray());
        return a;
    }

    static int colorFor(boolean up) {
        return up ? COLOR_GREEN : COLOR_RED;
    }

    /** 一次交互的上下文：记住是否已经 defer，自动选择「回复」还是「编辑原消息」。 */
    private final class Ctx {
        final JsonObject d;
        final JsonObject data;
        final int type;
        boolean deferred;
        boolean responded;
        boolean ephemeral;

        /** 实际的指令名：/minmin 收集 → "collect"（老的独立指令名也照样认）。 */
        final String command;

        Ctx(JsonObject d) {
            this.d = d;
            this.type = d.get("type").getAsInt();
            this.data = d.has("data") ? d.getAsJsonObject("data") : new JsonObject();
            String name = data.has("name") ? data.get("name").getAsString() : "";
            JsonObject first = firstOption(data);
            this.command = name.equals(ROOT) && first != null ? first.get("name").getAsString() : name;
        }

        private JsonObject firstOption(JsonObject o) {
            if (o == null || !o.has("options") || o.getAsJsonArray("options").isEmpty()) return null;
            JsonObject f = o.getAsJsonArray("options").get(0).getAsJsonObject();
            int t = f.get("type").getAsInt();
            return t == 1 || t == 2 ? f : null;
        }

        String option(String name) {
            for (JsonObject o : leafOptions()) {
                if (o.get("name").getAsString().equals(name) && o.has("value")) return o.get("value").getAsString();
            }
            return null;
        }

        /** 子指令名（/minmin 通知组 添加 → "add"），没有就是 null。 */
        String subcommand() {
            if (data.has("name") && data.get("name").getAsString().equals(ROOT)) {
                JsonObject first = firstOption(data);
                if (first == null || first.get("type").getAsInt() != 2) return null;
                JsonObject inner = firstOption(first);
                return inner == null ? null : inner.get("name").getAsString();
            }
            if (!data.has("options")) return null;
            for (JsonElement e : data.getAsJsonArray("options")) {
                JsonObject o = e.getAsJsonObject();
                int t = o.get("type").getAsInt();
                if (t == 1 || t == 2) return o.get("name").getAsString();
            }
            return null;
        }

        /** 摊平子指令，返回真正带值的选项。 */
        List<JsonObject> leafOptions() {
            List<JsonObject> out = new ArrayList<>();
            collectLeaves(data.get("options"), out);
            return out;
        }

        private void collectLeaves(JsonElement e, List<JsonObject> out) {
            if (e == null || !e.isJsonArray()) return;
            for (JsonElement x : e.getAsJsonArray()) {
                JsonObject o = x.getAsJsonObject();
                int t = o.has("type") ? o.get("type").getAsInt() : 0;
                if (t == 1 || t == 2) collectLeaves(o.get("options"), out);
                else out.add(o);
            }
        }

        JsonObject member() {
            return d.has("member") ? d.getAsJsonObject("member") : new JsonObject();
        }

        JsonObject user() {
            JsonObject m = member();
            if (m.has("user")) return m.getAsJsonObject("user");
            return d.has("user") ? d.getAsJsonObject("user") : new JsonObject();   // 2026-10-07 auth-gate: DM interaction
        }

        /** Discord 用户名（记录用）。 */
        String userTag() {
            JsonObject u = user();
            return u.has("username") ? u.get("username").getAsString() : "";
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

        boolean isAdmin() {
            JsonObject m = member();
            if (m.has("permissions") && BotRules.hasAdminBits(m.get("permissions").getAsString())) return true;
            List<String> admins = plugin.cfg().adminRoles;
            if (m.has("roles")) for (JsonElement r : m.getAsJsonArray("roles")) if (admins.contains(r.getAsString())) return true;
            return false;
        }

        void callback(int cbType, JsonObject cbData) throws IOException {
            JsonObject body = new JsonObject();
            body.addProperty("type", cbType);
            if (cbData != null) body.add("data", cbData);
            plugin.rest().post("/interactions/" + d.get("id").getAsString() + "/" + d.get("token").getAsString() + "/callback", body);
            responded = true;
        }

        void defer(boolean eph) throws IOException {
            ephemeral = eph;
            JsonObject data = new JsonObject();
            if (eph) data.addProperty("flags", 64);
            // 按钮用 type 5 也可以：会发一条新的（仅自己可见）回复，而不是改面板消息
            callback(5, data);
            deferred = true;
        }

        void reply(JsonObject msg) throws IOException {
            if (!msg.has("allowed_mentions")) msg.add("allowed_mentions", noMentions());
            if (deferred) {
                plugin.rest().patch("/webhooks/" + plugin.applicationId() + "/" + d.get("token").getAsString() + "/messages/@original", msg);
            } else {
                if (ephemeral) msg.addProperty("flags", 64);
                callback(4, msg);
            }
        }

        void text(String s) throws IOException {
            reply(message(BotRules.truncate(s, 2000), null));
        }

        void textQuietly(String s) {
            if (type == 4) return;
            try {
                if (!deferred && responded) return;
                ephemeral = true;
                text(s);
            } catch (IOException ignored) {
            }
        }
    }
}
