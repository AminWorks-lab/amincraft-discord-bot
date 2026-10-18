package cn.aminplay.discord;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** config.yml 的只读快照。 */
final class BotConfig {
    /** type: "subscribe" = 只记名单、@ 时临时建身份组（不占名额）；"role" = 真身份组。 */
    record PanelRole(String key, String name, String type, String id, String emoji, String description) {
        boolean subscribe() {
            return !"role".equals(type);
        }
    }

    final String token;
    final String guildId;
    final String announceChannel;
    final String statusChannel;
    final String bridgeChannel;
    final String logChannel;
    final String plotsChannel;   // 2026-10-08 bot-plots: 类脑市地块申请的管理频道
    final String feedbackChannel;   // 2026-10-17 feedback: 游戏内 /反馈 转发到这里（留空 = 关）
    final String highlightsChannel;   // 2026-10-17 对局精彩回放：精彩片段卡片发到这里（留空 = 关）
    final List<String> adminRoles;
    final List<PanelRole> panelRoles;
    final String panelTitle;
    final String panelText;
    // 2026-10-18 一般成员用 /minmin 通知 能 @ 的面板项目（roles.panel 的 key）和冷却
    final Set<String> pingPublic;
    final int pingUserCooldownSeconds;
    final int pingGroupCooldownSeconds;
    final int pingMaxLength;
    final boolean bridgeChat;
    final boolean bridgeJoinLeave;
    /** 2026-10-17 读互通频道里的普通消息（要 Message Content Intent）；false = 只发不读，Discord → 游戏改用 /minmin 说。 */
    final boolean bridgeRead;
    final boolean pingOnStart;
    final boolean presence;
    final Set<String> blockedCommands;
    final String serverName;
    final String serverAddress;
    // 2026-10-07 daily replay bundle: 论坛回放贴
    final boolean replaysEnabled;
    // 2026-10-07 message collect
    final String collectChannel;
    final java.time.ZoneId collectZone;
    final int collectMaxMessages;
    final long collectMaxFileBytes;
    final long collectMaxTotalBytes;
    final List<String> collectSources;   // 2026-10-08 privacy: only these feedback channels/forums (and their threads)
    final int collectKeepDays;
    final String replaysForum;
    final String replaysThread;
    final String replaysTitle;
    final String replaysTag;
    final int replaysPollSeconds;
    // 2026-10-07 auth-gate：学习版玩家 Discord 密钥
    final boolean keysEnabled;
    final int keysMinAccountDays;
    final int keysMinMemberDays;
    final boolean keysPanelButton;
    final boolean keysDmCopy;
    final boolean webLoginEnabled;
    // 2026-10-12 bot-promo：定时广告（设置本身存在 promo-schedules.json，这里只是时区和卡片内容）
    final java.time.ZoneId promoZone;
    final int promoMinGapMinutes;
    final int promoGraceMinutes;
    final PromoRules.Card promoCard;
    final java.util.Set<String> promoUsers;   // 2026-10-12 服主：只有这些 Discord 用户能设置定时广告
    final MatchThreads.Settings matchChat;   // 2026-10-15 match-chat：对局子区（默认关闭）

    private BotConfig(FileConfiguration c) {
        token = c.getString("discord.token", "").trim();
        guildId = id(c.getString("discord.guild-id", ""));
        announceChannel = id(c.getString("channels.announce", ""));
        statusChannel = id(c.getString("channels.status", ""));
        bridgeChannel = id(c.getString("channels.bridge", ""));
        logChannel = id(c.getString("channels.log", ""));
        plotsChannel = id(c.getString("channels.plots", ""));   // 2026-10-08 bot-plots
        feedbackChannel = id(c.getString("channels.feedback", ""));   // 2026-10-17 feedback
        highlightsChannel = id(c.getString("channels.highlights", ""));   // 2026-10-17 对局精彩回放
        adminRoles = new ArrayList<>();
        for (String s : c.getStringList("roles.admin")) if (!id(s).isEmpty()) adminRoles.add(id(s));
        panelRoles = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("roles.panel")) {
            String name = str(m.get("name"));
            if (name.isEmpty()) continue;
            String key = str(m.get("key"));
            if (key.isEmpty()) key = name;
            key = BotRules.truncate(key.replace("|", "_"), 30);
            String type = str(m.get("type")).toLowerCase(Locale.ROOT).equals("role") ? "role" : "subscribe";
            panelRoles.add(new PanelRole(key, name, type, id(str(m.get("id"))), str(m.get("emoji")), str(m.get("description"))));
        }
        panelTitle = c.getString("panel.title", "🔔 订阅通知");
        panelText = c.getString("panel.text", "点下面的按钮订阅，再点一次取消。");
        Set<String> pp = new LinkedHashSet<>();   // 2026-10-18 public ping
        for (String s : c.getStringList("ping.public")) if (!s.trim().isEmpty()) pp.add(s.trim());
        pingPublic = Set.copyOf(pp);
        pingUserCooldownSeconds = Math.max(0, Math.min(86400, c.getInt("ping.user-cooldown-seconds", 600)));
        pingGroupCooldownSeconds = Math.max(0, Math.min(86400, c.getInt("ping.group-cooldown-seconds", 300)));
        pingMaxLength = Math.max(1, Math.min(1800, c.getInt("ping.max-length", 200)));
        bridgeChat = c.getBoolean("bridge.chat", false);
        bridgeJoinLeave = c.getBoolean("bridge.join-leave", true);
        bridgeRead = c.getBoolean("bridge.read-messages", false);   // 2026-10-17 无 Message Content Intent 也能开互通
        pingOnStart = c.getBoolean("notify.ping-on-start", false);
        presence = c.getBoolean("notify.presence", true);
        blockedCommands = new LinkedHashSet<>();
        for (String s : c.getStringList("commands.blocked")) blockedCommands.add(BotRules.commandRoot(s));
        serverName = c.getString("server.name", "Amincraft");   // 2026-10-06 rename Amincraft
        serverAddress = c.getString("server.address", "");
        replaysEnabled = c.getBoolean("replays.enabled", true);   // 2026-10-07 daily replay bundle
        collectChannel = id(c.getString("channels.collect", ""));   // 2026-10-07 message collect
        java.time.ZoneId z;
        try {
            z = java.time.ZoneId.of(c.getString("collect.timezone", "Asia/Tokyo"));
        } catch (java.time.DateTimeException e) {
            z = java.time.ZoneId.of("Asia/Tokyo");
        }
        collectZone = z;
        collectMaxMessages = Math.max(100, c.getInt("collect.max-messages", 20000));
        collectMaxFileBytes = Math.max(1, c.getLong("collect.max-file-mb", 25)) * 1024 * 1024;
        collectMaxTotalBytes = Math.max(1, c.getLong("collect.max-total-mb", 500)) * 1024 * 1024;
        collectSources = new ArrayList<>();   // 2026-10-08 privacy
        for (String s : c.getStringList("collect.sources")) if (!id(s).isEmpty()) collectSources.add(id(s));
        collectKeepDays = Math.max(1, c.getInt("collect.keep-days", 1));   // 2026-10-08 privacy: results are deleted right after posting; this only cleans leftovers
        replaysForum = id(c.getString("replays.forum-channel", ""));   // 2026-10-08 github: no built-in id, set with /discordbot set replays.forum-channel <id>
        replaysThread = id(c.getString("replays.thread-id", ""));
        replaysTitle = c.getString("replays.thread-title", "📼 Amincraft 对局回放");
        replaysTag = c.getString("replays.forum-tag", "").trim();
        replaysPollSeconds = Math.max(15, c.getInt("replays.poll-seconds", 60));
        keysEnabled = c.getBoolean("keys.enabled", true);   // 2026-10-07 auth-gate
        keysMinAccountDays = Math.max(0, c.getInt("keys.min-account-days", 7));
        keysMinMemberDays = Math.max(0, c.getInt("keys.min-member-days", 1));
        keysPanelButton = c.getBoolean("keys.panel-button", true);
        keysDmCopy = c.getBoolean("keys.dm-copy", true);
        webLoginEnabled = c.getBoolean("web-login.enabled", false);
        java.time.ZoneId pz;   // 2026-10-12 bot-promo
        try {
            pz = java.time.ZoneId.of(c.getString("promo.timezone", "Asia/Shanghai"));
        } catch (java.time.DateTimeException e) {
            pz = java.time.ZoneId.of("Asia/Shanghai");
        }
        promoZone = pz;
        promoMinGapMinutes = Math.max(0, c.getInt("promo.min-gap-minutes", 30));
        java.util.Set<String> pu = new java.util.LinkedHashSet<>();
        for (String s : c.getStringList("promo.allowed-users")) if (!id(s).isEmpty()) pu.add(id(s));
        promoUsers = java.util.Set.copyOf(pu);
        promoGraceMinutes = Math.max(1, c.getInt("promo.grace-minutes", 10));
        String promoAddress = c.getString("promo.address", "").trim();
        if (promoAddress.isEmpty()) promoAddress = serverAddress.trim();
        String promoForum = id(c.getString("promo.modpack-forum", ""));
        if (promoForum.isEmpty()) promoForum = id(c.getString("posts.modpack-forum", ""));
        int color;
        try {
            color = Integer.parseInt(c.getString("promo.color", "57C84D").replace("#", "").trim(), 16);
        } catch (NumberFormatException e) {
            color = 0x57C84D;
        }
        promoCard = new PromoRules.Card(
                c.getString("promo.title", "🎮 " + serverName + " 小游戏服务器"),
                c.getString("promo.intro", ""),
                promoAddress,
                List.copyOf(c.getStringList("promo.modes")),
                c.getString("promo.modpack-url", "").trim(),
                promoForum,
                c.getString("promo.footer", ""),
                color & 0xFFFFFF);
        java.util.Set<String> games = new java.util.LinkedHashSet<>();   // 2026-10-15 match-chat
        for (String g : c.getStringList("match-chat.games")) if (!g.trim().isEmpty()) games.add(g.trim());
        matchChat = new MatchThreads.Settings(
                c.getBoolean("match-chat.enabled", false),
                id(c.getString("match-chat.channel", "")),
                c.getBoolean("match-chat.mention", true),
                c.getBoolean("match-chat.private-rooms", true),
                java.util.Set.copyOf(games),
                Math.max(0, Math.min(3600, c.getInt("match-chat.close-delay-seconds", 60))),
                collectZone);
    }

    static BotConfig load(FileConfiguration c) {
        return new BotConfig(c);
    }

    boolean hasPanelRole(String id) {
        for (PanelRole r : panelRoles) if (!r.subscribe() && r.id().equals(id)) return true;
        return false;
    }

    PanelRole entry(String key) {
        for (PanelRole r : panelRoles) if (r.key().equals(key)) return r;
        return null;
    }

    int intents() {
        int i = DiscordGateway.INTENT_GUILDS;
        if (bridgeChat && bridgeRead && !bridgeChannel.isEmpty()) i |= DiscordGateway.INTENT_GUILD_MESSAGES | DiscordGateway.INTENT_MESSAGE_CONTENT;
        if (matchChat.active()) i |= DiscordGateway.INTENT_GUILD_MESSAGES | DiscordGateway.INTENT_MESSAGE_CONTENT;   // 2026-10-15 match-chat：读子区消息
        return i;
    }

    /** 把解析/创建出来的身份组 id 写回 config.yml。 */
    static void writePanelIds(FileConfiguration c, List<PanelRole> roles) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PanelRole r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("name", r.name());
            m.put("type", r.type());
            if (!r.subscribe()) m.put("id", r.id());
            m.put("emoji", r.emoji());
            m.put("description", r.description());
            out.add(m);
        }
        c.set("roles.panel", out);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 允许直接粘贴 <#123> / <@&123> 这类格式。 */
    private static String id(String s) {
        if (s == null) return "";
        String digits = s.replaceAll("[^0-9]", "");
        return digits.toLowerCase(Locale.ROOT);
    }
}
