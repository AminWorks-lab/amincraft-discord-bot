package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Amincraft 的 Discord 机器人（插件名 AminPlayDiscord 不改），作为 Paper 插件运行在 MC 服务器里（不需要另外的主机）。
 * 只依赖 JDK 自带的 java.net.http 和 Paper 自带的 Gson。
 */
public final class AminPlayDiscord extends JavaPlugin implements Listener, DiscordGateway.Handler {
    private volatile BotConfig cfg;
    private HttpClient http;
    private DiscordRest rest;
    private DiscordGateway gateway;
    private ExecutorService worker;
    private ExecutorService chatQueue;
    private Interactions interactions;
    private Subscriptions subscriptions;
    private OptOut optOut;   // 2026-10-08 privacy
    private Notifier notifier;
    // 2026-10-07 message collect
    private volatile Collector collector;
    private final java.util.concurrent.Semaphore collectLock = new java.util.concurrent.Semaphore(1);
    private volatile String applicationId;
    private volatile String lastPresence;
    private volatile boolean commandsRegistered;
    private final Object panelLock = new Object();
    private ReplayPoster replayPoster;   // 2026-10-07 daily replay bundle
    // 2026-10-07 auth-gate: MiniGameHub CrackedKeyApi (null = MiniGameHub missing / too old); join messages wait for the login gate
    private volatile HubAuthKeys authKeys;
    private final java.util.Set<java.util.UUID> joinPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<java.util.UUID> joinAnnounced = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean replayBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean postBusy = new java.util.concurrent.atomic.AtomicBoolean();   // 2026-10-12 bot-posting: one console post at a time
    // 2026-10-08 bot-plots: MiniGameHub City application API (null = MiniGameHub missing / too old) + the Discord side of plot applications
    private volatile HubCity city;
    private PlotDesk plots;
    private PromoPoster promo;   // 2026-10-12 bot-promo
    // 2026-10-15 match-chat: MiniGameHub MatchChatApi (null = MiniGameHub missing / too old) + the Discord threads of running matches
    private volatile HubMatchChat matchChat;
    private MatchThreads matchThreads;
    private volatile HubFeedback feedback;   // 2026-10-17 feedback: MiniGameHub FeedbackApi (null = missing / feedback.yml off)
    private FeedbackPoster feedbackPoster;
    private volatile HubHighlights highlights;   // 2026-10-17 对局精彩回放: MiniGameHub HighlightApi (null = missing / highlights.yml off)
    private HighlightPoster highlightPoster;

    // ================= 生命周期 =================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true); // 老配置自动补上新版本加的键
        saveConfig();
        cfg = BotConfig.load(getConfig());
        getServer().getPluginManager().registerEvents(this, this);
        if (cfg.token.isEmpty()) {
            getLogger().warning("还没有填写 Bot Token：请编辑 plugins/AminPlayDiscord/config.yml 的 discord.token，然后重启服务器。");
            return;
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        rest = new DiscordRest(http, cfg.token);
        AtomicInteger n = new AtomicInteger();
        worker = Executors.newFixedThreadPool(4, r -> daemon(r, "AminPlayDiscord-worker-" + n.incrementAndGet()));
        chatQueue = Executors.newSingleThreadExecutor(r -> daemon(r, "AminPlayDiscord-chat"));
        interactions = new Interactions(this);
        subscriptions = new Subscriptions(new java.io.File(getDataFolder(), "subscriptions.yml"), getLogger());
        optOut = new OptOut(new java.io.File(getDataFolder(), "optout.yml"), getLogger());   // 2026-10-08 privacy
        plots = new PlotDesk(new PlotDesk.Host() {   // 2026-10-08 bot-plots
            @Override public DiscordRest rest() { return rest; }
            @Override public BotConfig cfg() { return cfg; }
            @Override public String applicationId() { return applicationId; }
            @Override public PlotDesk.City city() { return AminPlayDiscord.this.city(); }
            @Override public PlotDesk.Accounts accounts() { return PlotDesk.accountsOf(authKeys()); }
            @Override public void adminLog(String text) { AminPlayDiscord.this.adminLog(text); }
        }, new PlotStore(new java.io.File(getDataFolder(), "plots-posted.yml"), getLogger()), getLogger());
        notifier = new Notifier(rest, subscriptions, getLogger());
        collector = newCollector();   // 2026-10-07 message collect
        gateway = new DiscordGateway(http, cfg.token, cfg.intents(), this, getLogger());
        gateway.start();
        // 2026-10-07 daily replay bundle: 每天的回放包发到论坛回放贴（MiniGameHub 不在时什么都不做，包留在 MiniGameHub 那边排队）
        replayPoster = new ReplayPoster(rest, new ReplayPoster.Settings() {
            @Override
            public BotConfig cfg() {
                return cfg;
            }

            @Override
            public void saveThreadId(String id) throws Exception {
                sync(() -> {
                    getConfig().set("replays.thread-id", id);
                    saveConfig();
                    cfg = BotConfig.load(getConfig());
                    return null;
                });
            }
        }, new java.io.File(getDataFolder(), "replays-posted.yml"), getLogger(), 1500);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> replayTick(false, null), 20L * 60, 20L * cfg.replaysPollSeconds);
        if (cfg.presence) Bukkit.getScheduler().runTaskTimer(this, this::refreshPresence, 100L, 1200L);
        // 2026-10-12 bot-promo：定时广告（没有用指令设置过就什么都不发）
        promo = new PromoPoster(new PromoPoster.Host() {
            @Override public DiscordRest rest() { return rest; }
            @Override public java.time.ZoneId zone() { return cfg.promoZone; }
            @Override public Duration minGap() { return Duration.ofMinutes(cfg.promoMinGapMinutes); }
            @Override public Duration grace() { return Duration.ofMinutes(cfg.promoGraceMinutes); }
            @Override public PromoRules.Card card() { return cfg.promoCard; }
            @Override public int[] online() throws Exception { return sync(() -> new int[]{Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers()}); }
            @Override public void adminLog(String text) { AminPlayDiscord.this.adminLog(text); }
        }, new PromoStore(new java.io.File(getDataFolder(), "promo-schedules.json"), getLogger()), getLogger());
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> { if (!worker.isShutdown()) worker.execute(() -> promo.tick(Instant.now())); }, 20L * 30, 20L * 30);
        Bukkit.getScheduler().runTask(this, this::hookAuthKeys);   // 2026-10-07 auth-gate
        Bukkit.getScheduler().runTask(this, this::hookCity);   // 2026-10-08 bot-plots
        matchThreads = new MatchThreads(new MatchThreads.Host() {   // 2026-10-15 match-chat
            @Override public DiscordRest rest() { return rest; }
            @Override public MatchThreads.Settings settings() { return cfg.matchChat; }
            @Override public boolean optedOut(String id) { return optOut != null && optOut.has(id); }
            @Override public HubMatchChat hub() { return matchChat; }
            @Override public void queue(Runnable task) { queueChat(task); }
            @Override public void inbound(Runnable task) { if (!worker.isShutdown()) worker.execute(task); }
            @Override public void later(long seconds, Runnable task) {
                if (isEnabled()) Bukkit.getScheduler().runTaskLaterAsynchronously(AminPlayDiscord.this, () -> queueChat(task), Math.max(1, seconds * 20));
            }
        }, getLogger());
        Bukkit.getScheduler().runTask(this, this::hookMatchChat);
        feedbackPoster = new FeedbackPoster(rest, () -> cfg, getLogger());   // 2026-10-17 feedback
        Bukkit.getScheduler().runTask(this, this::hookFeedback);
        highlightPoster = new HighlightPoster(rest, () -> cfg, id -> optOut != null && optOut.has(id), () -> highlights, getLogger());   // 2026-10-17 对局精彩回放
        Bukkit.getScheduler().runTask(this, this::hookHighlights);
    }

    /** 2026-10-17 对局精彩回放：接收 MiniGameHub 的精彩片段，交给 worker 发到 channels.highlights。 */
    private void hookHighlights() {
        highlights = null;
        HubHighlights h = HubHighlights.find();
        if (h == null || rest == null || highlightPoster == null) return;
        highlights = h;
        try {
            h.setEventSink(e -> {   // main thread: copy and hand over to a worker
                if (!"highlight".equals(String.valueOf(e.get("type")))) return;
                Map<String, Object> copy = new java.util.HashMap<>(e);
                worker.execute(() -> {
                    try {
                        highlightPoster.post(copy);
                    } catch (DiscordRest.DiscordException ex) {
                        getLogger().warning("精彩片段发送失败（" + copy.get("id") + "）：" + ex.friendly());
                    } catch (Exception ex) {
                        getLogger().warning("精彩片段发送失败（" + copy.get("id") + "）：" + ex.getMessage());
                    }
                });
            });
            getLogger().info("已连接 MiniGameHub 精彩回放接口" + (cfg.highlightsChannel.isEmpty() ? "（channels.highlights 未设置，不发送）" : ""));
        } catch (Exception e) {
            getLogger().warning("连接 MiniGameHub 精彩回放接口失败：" + e);
        }
    }

    private void queueChat(Runnable task) {
        try {
            chatQueue.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {   // shutting down
        }
    }

    /** 2026-10-15 match-chat：找 MiniGameHub 的对局聊天接口。主线程调用。没开 match-chat 时也连上（普通互通要用 inMatch 过滤），但不接收事件。 */
    private void hookMatchChat() {
        HubMatchChat h = HubMatchChat.find();
        matchChat = h;
        if (h == null) return;
        boolean receive = rest != null && matchThreads != null && cfg.matchChat.active();
        try {
            h.setSink(receive ? matchThreads::onEvent : null);
            getLogger().info("已连接 MiniGameHub 对局聊天接口" + (receive ? "（对局子区已开启）" : "（只用于聊天互通过滤，对局子区未开启）"));
        } catch (Exception e) {
            getLogger().warning("连接 MiniGameHub 对局聊天接口失败：" + e);
        }
    }

    /** 2026-10-17 feedback：接收 MiniGameHub 的游戏内反馈，交给 worker 发到 channels.feedback。 */
    private void hookFeedback() {
        feedback = null;
        HubFeedback f = HubFeedback.find();
        if (f == null || rest == null || feedbackPoster == null) return;
        feedback = f;
        try {
            f.setEventSink(e -> {   // main thread: copy and hand over to a worker
                if (!"feedback".equals(String.valueOf(e.get("type")))) return;
                Map<String, Object> copy = new java.util.HashMap<>(e);
                worker.execute(() -> {
                    try {
                        feedbackPoster.post(copy);
                    } catch (DiscordRest.DiscordException ex) {
                        getLogger().warning("反馈转发失败（" + copy.get("id") + "）：" + ex.friendly());
                    } catch (Exception ex) {
                        getLogger().warning("反馈转发失败（" + copy.get("id") + "）：" + ex.getMessage());
                    }
                });
            });
            getLogger().info("已连接 MiniGameHub 游戏内反馈接口" + (cfg.feedbackChannel.isEmpty() ? "（channels.feedback 未设置，不转发）" : ""));
        } catch (Exception e) {
            getLogger().warning("连接 MiniGameHub 游戏内反馈接口失败：" + e);
        }
    }

    /** 2026-10-08 bot-plots：找 MiniGameHub 的类脑市申请接口（没有 = 地块功能显示「不可用」）。 */
    HubCity city() {
        HubCity c = city;
        if (c == null) {
            c = HubCity.find();
            city = c;
        }
        return c;
    }

    PlotDesk plots() {
        return plots;
    }

    PromoPoster promo() {   // 2026-10-12 bot-promo
        return promo;
    }

    private void hookCity() {
        city = null;
        HubCity c = city();
        if (c == null || rest == null || plots == null) return;
        try {
            c.setEventSink(e -> {   // main thread: copy and hand over to a worker
                Map<String, Object> copy = new java.util.HashMap<>(e);
                worker.execute(() -> plots.onEvent(copy));
            });
            String missing = c.missing();
            getLogger().info("已连接 MiniGameHub 类脑市申请接口" + (missing.isEmpty() ? "" : "（缺少：" + missing + "，这些功能不可用）"));
        } catch (Exception e) {
            getLogger().warning("连接 MiniGameHub 类脑市申请接口失败：" + e);
        }
        worker.execute(() -> {
            int n = plots.catchUp(20);
            if (n > 0) getLogger().info("已把 " + n + " 个待审地块申请补发到管理频道");
        });
    }

    /** 2026-10-07 auth-gate：找 MiniGameHub 的密钥接口，并接收它的事件（登录门禁通过、Discord 登录确认、封禁同步）。 */
    HubAuthKeys authKeys() {
        HubAuthKeys k = authKeys;
        if (k == null) {
            k = HubAuthKeys.find();
            authKeys = k;
        }
        return k;
    }

    private void hookAuthKeys() {
        authKeys = null;
        HubAuthKeys k = authKeys();
        if (k == null || rest == null) return;
        try {
            k.setEventSink(this::onHubEvent);
            getLogger().info("已连接 MiniGameHub 学习版密钥接口");
        } catch (Exception e) {
            getLogger().warning("连接 MiniGameHub 学习版密钥接口失败：" + e);
        }
    }

    @EventHandler
    public void onServiceRegister(org.bukkit.event.server.ServiceRegisterEvent e) {   // 2026-10-07 auth-gate: MiniGameHub (re)started
        if (e.getProvider().getService().getName().equals(HubAuthKeys.API)) Bukkit.getScheduler().runTask(this, this::hookAuthKeys);
        if (e.getProvider().getService().getName().equals(HubCity.API)) Bukkit.getScheduler().runTask(this, this::hookCity);   // 2026-10-08 bot-plots
        if (e.getProvider().getService().getName().equals(HubMatchChat.API)) Bukkit.getScheduler().runTask(this, this::hookMatchChat);   // 2026-10-15 match-chat
        if (e.getProvider().getService().getName().equals(HubFeedback.API)) Bukkit.getScheduler().runTask(this, this::hookFeedback);   // 2026-10-17 feedback
        if (e.getProvider().getService().getName().equals(HubHighlights.API)) Bukkit.getScheduler().runTask(this, this::hookHighlights);   // 2026-10-17 对局精彩回放
    }

    @EventHandler
    public void onServiceUnregister(org.bukkit.event.server.ServiceUnregisterEvent e) {
        if (e.getProvider().getService().getName().equals(HubAuthKeys.API)) authKeys = null;
        if (e.getProvider().getService().getName().equals(HubCity.API)) city = null;   // 2026-10-08 bot-plots
        if (e.getProvider().getService().getName().equals(HubMatchChat.API)) matchChat = null;   // 2026-10-15 match-chat
        if (e.getProvider().getService().getName().equals(HubFeedback.API)) feedback = null;   // 2026-10-17 feedback
        if (e.getProvider().getService().getName().equals(HubHighlights.API)) highlights = null;   // 2026-10-17 对局精彩回放
    }

    /** 主线程（MiniGameHub 调用），不能阻塞。 */
    private void onHubEvent(Map<String, Object> e) {
        String type = String.valueOf(e.get("type"));
        switch (type) {
            case "verified" -> {
                try {
                    java.util.UUID u = java.util.UUID.fromString(String.valueOf(e.get("uuid")));
                    if (joinPending.remove(u)) announceJoin(u, String.valueOf(e.get("name")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            case "login-confirm" -> {
                String token = String.valueOf(e.get("token")), user = String.valueOf(e.get("discordId")), name = String.valueOf(e.get("name"));
                int secs = e.get("timeoutSeconds") instanceof Number n ? n.intValue() : 120;
                worker.execute(() -> {
                    JsonObject msg = Interactions.message(KeyRules.loginConfirm(name, secs), null);
                    JsonArray row = new JsonArray();
                    row.add(button(3, "是我", "authlogin|yes|" + token));
                    row.add(button(4, "不是我", "authlogin|no|" + token));
                    JsonObject ar = new JsonObject();
                    ar.addProperty("type", 1);
                    ar.add("components", row);
                    JsonArray rows = new JsonArray();
                    rows.add(ar);
                    msg.add("components", rows);
                    Boolean ok = sendDm(user, msg);
                    if (!Boolean.TRUE.equals(ok)) getLogger().info("Discord 登录确认私信没发出去（" + name + "：对方关闭了私信？），玩家可以用密码登录");
                });
            }
            case "premium-link" -> {   // 2026-10-11 account-link: the in-game answer, as a DM to the Discord user
                String user = String.valueOf(e.get("discordId")), name = String.valueOf(e.get("name")), status = String.valueOf(e.get("status"));
                worker.execute(() -> sendDm(user, Interactions.message(KeyRules.premiumResult(name, status), null)));
                if ("ok".equals(status)) adminLog("🔗 Discord <@" + user + "> 关联了正版账号 " + name);
            }
            case "ban-sync" -> adminLog((Boolean.TRUE.equals(e.get("banned")) ? "🔨 游戏内封禁同步：" : "✅ 游戏内解封同步：") + "Discord <@" + e.get("discordId") + "> ↔ " + e.get("names") + "（" + e.get("source") + "）");
            default -> {
            }
        }
    }

    private static JsonObject button(int style, String label, String id) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);
        b.addProperty("style", style);
        b.addProperty("label", label);
        b.addProperty("custom_id", id);
        return b;
    }

    /** 私信。worker 线程调用。true = 已发；false = 对方关了私信 / 不在服务器；null 不会返回。 */
    Boolean sendDm(String userId, JsonObject msg) {
        if (rest == null) return false;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("recipient_id", userId);
            String ch = rest.post("/users/@me/channels", body).getAsJsonObject().get("id").getAsString();
            msg.add("allowed_mentions", Interactions.noMentions());
            rest.post("/channels/" + ch + "/messages", msg);
            return true;
        } catch (DiscordRest.DiscordException e) {
            if (e.code != 50007 && e.status != 403) getLogger().warning("私信失败：" + e.friendly());
            return false;
        } catch (IOException e) {
            getLogger().warning("私信失败：" + e.getMessage());
            return false;
        }
    }

    @Override
    public void onDisable() {
        try {   // 2026-10-07 auth-gate
            HubAuthKeys k = authKeys;
            if (k != null) k.setEventSink(null);
        } catch (Exception ignored) {
        }
        try {   // 2026-10-08 bot-plots
            HubCity c = city;
            if (c != null) c.setEventSink(null);
        } catch (Exception ignored) {
        }
        try {   // 2026-10-15 match-chat
            HubMatchChat m = matchChat;
            if (m != null) m.setSink(null);
        } catch (Exception ignored) {
        }
        try {   // 2026-10-17 feedback
            HubFeedback f = feedback;
            if (f != null) f.setEventSink(null);
        } catch (Exception ignored) {
        }
        try {   // 2026-10-17 对局精彩回放
            HubHighlights h = highlights;
            if (h != null) h.setEventSink(null);
        } catch (Exception ignored) {
        }
        if (rest != null && !cfg.statusChannel.isEmpty()) {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "🔴 " + cfg.serverName + " 已关闭");
            embed.addProperty("description", "服务器正在关闭或重启，稍后再来～");
            embed.addProperty("color", Interactions.colorFor(false));
            embed.addProperty("timestamp", Instant.now().toString());
            try {
                worker.submit(() -> rest.post("/channels/" + cfg.statusChannel + "/messages", Interactions.message(null, embed)))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().warning("发送关服通知失败：" + e.getMessage());
            }
        }
        if (gateway != null) gateway.shutdown();
        if (worker != null) worker.shutdownNow();
        if (chatQueue != null) chatQueue.shutdownNow();
        if (http != null) http.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ================= Gateway 事件 =================

    @Override
    public void dispatch(String type, JsonObject d) {
        switch (type) {
            case "READY" -> onReady(d);
            case "INTERACTION_CREATE" -> worker.execute(() -> interactions.handle(d));
            case "MESSAGE_CREATE" -> onDiscordMessage(d);
            default -> {
            }
        }
    }

    @Override
    public void fatal(int code, String reason) {
        String hint = switch (code) {
            case 4004 -> "Bot Token 不对。请到 Discord Developer Portal → Bot → Reset Token，把新的填进 config.yml。";
            case 4014 -> "开启了聊天互通或对局子区，但没打开 Message Content Intent。请到 Developer Portal → Bot → Privileged Gateway Intents 打开它，或者把 bridge.read-messages / match-chat.enabled 改成 false（互通照样能用：游戏消息照发，Discord 里用 /minmin 说 发进游戏）。";
            default -> "Discord 拒绝了连接（" + code + " " + reason + "）。";
        };
        getLogger().severe(hint + " 机器人已停止，修好后重启服务器。");
    }

    private void onReady(JsonObject d) {
        applicationId = d.getAsJsonObject("application").get("id").getAsString();
        String botName = d.getAsJsonObject("user").get("username").getAsString();
        getLogger().info("已登录 Discord：" + botName);
        if (cfg.guildId.isEmpty()) {
            JsonArray guilds = d.getAsJsonArray("guilds");
            if (guilds.size() == 1) {
                String gid = guilds.get(0).getAsJsonObject().get("id").getAsString();
                Bukkit.getScheduler().runTask(this, () -> {
                    getConfig().set("discord.guild-id", gid);
                    saveConfig();
                    cfg = BotConfig.load(getConfig());
                    getLogger().info("机器人只在一个 Discord 服务器里，已自动记下 guild-id=" + gid);
                    worker.execute(this::registerCommands);
                });
            } else {
                getLogger().warning("请在 config.yml 填写 discord.guild-id（机器人在 " + guilds.size() + " 个服务器里，不知道用哪个）。");
            }
            return;
        }
        if (!commandsRegistered) worker.execute(this::registerCommands);
        String guild = cfg.guildId;
        worker.execute(() -> notifier.cleanupPending(guild));
        if (cfg.presence) Bukkit.getScheduler().runTask(this, () -> {
            lastPresence = null;
            refreshPresence();
        });
    }

    private void registerCommands() {
        try {
            rest.put("/applications/" + applicationId + "/guilds/" + cfg.guildId + "/commands", Interactions.definitions(cfg.webLoginEnabled));
            commandsRegistered = true;
            getLogger().info("Discord 斜杠指令已注册：" + Interactions.commandSummary(cfg.webLoginEnabled));   // 2026-10-08 bot-plots: list the real commands
        } catch (DiscordRest.DiscordException e) {
            getLogger().warning("注册斜杠指令失败：" + e.friendly()
                    + "（邀请机器人时要勾选 applications.commands）");
        } catch (IOException e) {
            getLogger().warning("注册斜杠指令失败：" + e.getMessage());
        }
    }

    // ================= MC → Discord =================

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        if (e.getType() != ServerLoadEvent.LoadType.STARTUP || rest == null || cfg.statusChannel.isEmpty()) return;
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🟢 " + cfg.serverName + " 已开启");
        StringBuilder desc = new StringBuilder("服务器已经开好，快来玩吧！");
        if (!cfg.serverAddress.isEmpty()) desc.append("\n地址：`").append(cfg.serverAddress).append("`");
        desc.append("\n版本：").append(Bukkit.getMinecraftVersion());
        String hub = pluginVersion("MiniGameHub");
        if (hub != null) desc.append(" · MiniGameHub ").append(hub);
        embed.addProperty("description", desc.toString());
        embed.addProperty("color", Interactions.colorFor(true));
        embed.addProperty("timestamp", Instant.now().toString());
        String ch = cfg.statusChannel;
        String guild = cfg.guildId;
        BotConfig.PanelRole first = cfg.pingOnStart && !cfg.panelRoles.isEmpty() && !guild.isEmpty() ? cfg.panelRoles.get(0) : null;
        worker.execute(() -> {
            Notifier.Mention m = Notifier.Mention.none();
            try {
                if (first != null) m = first.subscribe() ? notifier.prepareSubscription(guild, first, x -> {}) : Notifier.role(resolveRoleId(first));
            } catch (Exception ex) {
                getLogger().warning("开服通知的 @ 准备失败：" + ex.getMessage());
            }
            JsonObject msg = Interactions.message(m.content(), embed);
            msg.add("allowed_mentions", m.allowed() != null ? m.allowed() : Interactions.noMentions());
            String id = null;
            try {
                id = rest.post("/channels/" + ch + "/messages", msg).getAsJsonObject().get("id").getAsString();
            } catch (DiscordRest.DiscordException ex) {
                getLogger().warning("发送开服通知失败：" + ex.friendly());
            } catch (IOException ex) {
                getLogger().warning("发送开服通知失败：" + ex.getMessage());
            }
            notifier.finish(guild, m, id == null ? null : "/channels/" + ch + "/messages/" + id, m.plainText());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (rest == null || !cfg.bridgeChat || cfg.bridgeChannel.isEmpty()) return;
        if (!MatchThreads.bridgeFromGame(matchChat, e.getPlayer().getUniqueId().toString())) return;   // 2026-10-15 match-chat: chat inside a match never goes to the public bridge
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        bridge("**" + escape(e.getPlayer().getName()) + "**: " + BotRules.truncate(text, 1500));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (rest == null || !cfg.bridgeJoinLeave || cfg.bridgeChannel.isEmpty()) return;
        java.util.UUID u = e.getPlayer().getUniqueId();
        HubAuthKeys k = authKeys();   // 2026-10-07 auth-gate: a cracked player shows up only after passing the login gate
        try {
            if (k != null && !k.isVerified(u.toString())) {
                joinPending.add(u);
                return;
            }
        } catch (Exception ignored) {
        }
        announceJoin(u, e.getPlayer().getName());
    }

    private void announceJoin(java.util.UUID u, String name) {
        joinAnnounced.add(u);
        bridge("➕ **" + escape(name) + "** 进入了服务器（" + Bukkit.getOnlinePlayers().size() + " 人在线）");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        java.util.UUID u = e.getPlayer().getUniqueId();
        joinPending.remove(u);
        boolean announced = joinAnnounced.remove(u);
        if (rest == null || !cfg.bridgeJoinLeave || cfg.bridgeChannel.isEmpty() || !announced) return;   // 2026-10-07 auth-gate: no "left" for someone never announced
        bridge("➖ **" + escape(e.getPlayer().getName()) + "** 离开了服务器（" + (Bukkit.getOnlinePlayers().size() - 1) + " 人在线）");
    }

    private void bridge(String content) {
        JsonObject msg = Interactions.message(content, null);
        msg.add("allowed_mentions", Interactions.noMentions());
        String ch = cfg.bridgeChannel;
        chatQueue.execute(() -> postQuietly(ch, msg));
    }

    private static String escape(String s) {
        return s.replace("_", "\\_").replace("*", "\\*");
    }

    // ================= Discord → MC =================

    private void onDiscordMessage(JsonObject d) {
        MatchThreads mt = matchThreads;   // 2026-10-15 match-chat: a message in a match thread goes to that match room only
        if (mt != null && mt.onDiscordMessage(d)) return;
        if (!cfg.bridgeChat || !cfg.bridgeRead || !cfg.bridgeChannel.equals(str(d, "channel_id"))) return;
        JsonObject author = d.getAsJsonObject("author");
        if (author == null || (author.has("bot") && author.get("bot").getAsBoolean())) return;
        if (d.has("webhook_id")) return;
        if (optOut != null && optOut.has(str(author, "id"))) return;   // 2026-10-08 privacy: opted out of the chat bridge
        String name = str(author, "global_name");
        if (d.has("member") && d.getAsJsonObject("member").has("nick") && !d.getAsJsonObject("member").get("nick").isJsonNull()) {
            name = d.getAsJsonObject("member").get("nick").getAsString();
        }
        if (name.isEmpty()) name = str(author, "username");
        String content = str(d, "content").replace('\n', ' ');
        if (d.has("attachments") && !d.getAsJsonArray("attachments").isEmpty()) content = (content + " [图片/文件]").trim();
        if (content.isEmpty()) return;
        relayToGame(name, content);
    }

    /** Discord → 游戏公屏（普通消息和 /minmin 说 共用；对局中的玩家收不到）。 */
    void relayToGame(String name, String content) {
        Component line = Component.text("[Discord] ", NamedTextColor.BLUE)
                .append(Component.text(BotRules.truncate(name, 32), NamedTextColor.AQUA))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(BotRules.truncate(content, 256), NamedTextColor.WHITE));
        Bukkit.getScheduler().runTask(this, () -> {
            HubMatchChat h = matchChat;
            if (h == null) {
                Bukkit.broadcast(line);
                return;
            }
            // 2026-10-15 match-chat: players in a running match (incl. spectators) do not get the public bridge
            for (Player p : MatchThreads.<Player>bridgeAudience(Bukkit.getOnlinePlayers(), p -> p.getUniqueId().toString(), h)) p.sendMessage(line);
            Bukkit.getConsoleSender().sendMessage(line);
        });
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    // ================= 给 Interactions 用的 =================

    BotConfig cfg() {
        return cfg;
    }

    DiscordRest rest() {
        return rest;
    }

    String applicationId() {
        return applicationId;
    }

    OptOut optOut() {   // 2026-10-08 privacy
        return optOut;
    }

    Subscriptions subscriptions() {
        return subscriptions;
    }

    Notifier notifier() {
        return notifier;
    }

    Collector collector() {
        return collector;
    }

    java.util.concurrent.Semaphore collectLock() {
        return collectLock;
    }

    private Collector newCollector() {
        java.nio.file.Path exports = getDataFolder().toPath().resolve("exports");
        Collector.cleanup(exports, cfg.collectKeepDays, getLogger());   // 2026-10-08 privacy: expired results
        return new Collector(rest, http, exports, cfg.collectZone, cfg.collectMaxMessages,
                cfg.collectMaxFileBytes, cfg.collectMaxTotalBytes, getLogger())
                .policy(new java.util.LinkedHashSet<>(cfg.collectSources), id -> optOut != null && optOut.has(id));
    }

    /** 2026-10-08 privacy：每次收集前顺便清理过期结果。 */
    void cleanupExports() {
        Collector.cleanup(getDataFolder().toPath().resolve("exports"), cfg.collectKeepDays, getLogger());
    }

    /**
     * 控制台：discordbot collect channel=<反馈频道id> [from=<时间>] [to=<时间>] [files=false] [threads=false]
     * 时间里的空格用 _ 代替（from=2026-10-06_20:00）。2026-10-08 privacy：必须写 post= 和 for=，结果只发到私密子区，本地文件发完就删；
     * 结果开私密子区（只有那个用户能看到），本地临时文件发完就删。
     */
    private void collectCommand(CommandSender sender, String[] args) {
        if (rest == null || collector == null) {
            sender.sendMessage(Component.text("机器人没有启动（没有 Token）。", NamedTextColor.RED));
            return;
        }
        Map<String, String> kv = new java.util.HashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) kv.put(args[i].substring(0, eq).toLowerCase(java.util.Locale.ROOT), args[i].substring(eq + 1));
            else if (args[i].equalsIgnoreCase("today") || args[i].equals("今天")) kv.put("from", "今天");
            else if (args[i].equalsIgnoreCase("yesterday") || args[i].equals("昨天")) {
                kv.put("from", "昨天");
                kv.put("to", "昨天");
            }
        }
        java.time.Instant[] range;
        try {
            range = BotRules.collectRange(kv.get("from"), kv.get("to"), cfg.collectZone, java.time.ZonedDateTime.now(cfg.collectZone));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("时间看不懂：今天、昨天、3d、2026-10-06、2026-10-06_20:00", NamedTextColor.YELLOW));
            return;
        }
        if (!collectLock.tryAcquire()) {
            sender.sendMessage(Component.text("已经有一个收集正在进行。", NamedTextColor.YELLOW));
            return;
        }
        String user = kv.get("user") == null ? null : kv.get("user").replaceAll("[^0-9]", "");
        String channel = kv.get("channel") == null ? null : kv.get("channel").replaceAll("[^0-9]", "");
        Collector.Request req = new Collector.Request(cfg.guildId, null,   // 2026-10-08 privacy: no per-member collect (user= ignored)
                channel == null || channel.isEmpty() ? null : channel, range[0], range[1],
                !"false".equalsIgnoreCase(kv.get("files")), !"false".equalsIgnoreCase(kv.get("threads")), "控制台");
        String post = kv.get("post") == null ? null : kv.get("post").replaceAll("[^0-9]", "");
        String forUser = kv.get("for") == null ? null : kv.get("for").replaceAll("[^0-9]", "");
        if (post == null || post.isEmpty() || forUser == null || forUser.isEmpty()) {   // 2026-10-08 privacy: results only go to a private thread
            collectLock.release();
            sender.sendMessage(Component.text("收集结果只发到 Discord 私密子区，本地不保存：请写 post=<文字频道ID> for=<用户ID>。", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("开始收集…", NamedTextColor.AQUA));
        worker.execute(() -> {
            try {
                cleanupExports();   // 2026-10-08 privacy
                Collector.Result r = collector.collect(req, msg -> {});
                sender.sendMessage(Component.text("收集完成：" + r.messages() + " 条、" + r.files().size() + " 个附件、"
                        + r.bigFiles().size() + " 个太大未下载；" + r.scope() + "。", NamedTextColor.GREEN));
                try {
                    String thread = interactions.postCollectResult(post, forUser, req, r);
                    sender.sendMessage(Component.text("已发到私密子区：https://discord.com/channels/" + cfg.guildId + "/" + thread, NamedTextColor.GREEN));
                } finally {
                    Collector.deleteDir(r.dir(), getLogger());   // 2026-10-08 privacy: no local copy
                }
            } catch (Exception e) {
                sender.sendMessage(Component.text("收集失败：" + e.getMessage(), NamedTextColor.RED));
                getLogger().log(Level.WARNING, "收集失败", e);
            } finally {
                collectLock.release();
            }
        });
    }

    /** 真身份组（type: role）的 id；没有就按名字找/创建。 */
    String resolveRoleId(BotConfig.PanelRole entry) throws Exception {
        if (!entry.id().isEmpty()) return entry.id();
        for (BotConfig.PanelRole r : resolvePanelRoles()) if (r.key().equals(entry.key())) return r.id();
        throw new IOException("找不到身份组 " + entry.name());
    }

    /** 在主线程执行并等待结果（Bukkit API 大多只能在主线程用）。 */
    <T> T sync(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        return Bukkit.getScheduler().callSyncMethod(this, task).get(10, TimeUnit.SECONDS);
    }

    /** 主线程调用。 */
    JsonObject statusEmbed() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        JsonObject e = new JsonObject();
        e.addProperty("title", "🟢 " + cfg.serverName + " 运行中");
        e.addProperty("color", Interactions.colorFor(true));
        JsonArray fields = new JsonArray();
        fields.add(field("在线人数", names.size() + " / " + Bukkit.getMaxPlayers(), true));
        fields.add(field("TPS", String.format("%.1f", tps), true));
        String hub = pluginVersion("MiniGameHub");
        fields.add(field("版本", Bukkit.getMinecraftVersion() + (hub == null ? "" : "\nMiniGameHub " + hub), true));
        if (!cfg.serverAddress.isEmpty()) fields.add(field("地址", "`" + cfg.serverAddress + "`", false));
        String list = names.isEmpty() ? "暂时没有人在线" : BotRules.truncate(escape(String.join("、", names)), 1000);
        fields.add(field("在线玩家", list, false));
        e.add("fields", fields);
        e.addProperty("timestamp", Instant.now().toString());
        return e;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    private String pluginVersion(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p == null ? null : p.getPluginMeta().getVersion();
    }

    /** 找到（或创建）面板里的身份组，把 id 写回 config.yml。在 worker 线程调用。 */
    List<BotConfig.PanelRole> resolvePanelRoles() throws Exception {
        synchronized (panelLock) {
            BotConfig c = cfg;
            boolean missing = c.panelRoles.stream().anyMatch(r -> !r.subscribe() && r.id().isEmpty());
            if (!missing) return c.panelRoles;
            JsonArray existing = rest.get("/guilds/" + c.guildId + "/roles").getAsJsonArray();
            List<BotConfig.PanelRole> out = new ArrayList<>();
            for (BotConfig.PanelRole r : c.panelRoles) {
                String id = r.id();
                if (r.subscribe()) {
                    out.add(r);
                    continue;
                }
                if (id.isEmpty()) {
                    for (JsonElement el : existing) {
                        JsonObject o = el.getAsJsonObject();
                        if (o.get("name").getAsString().equals(r.name())) id = o.get("id").getAsString();
                    }
                }
                if (id.isEmpty()) {
                    JsonObject body = new JsonObject();
                    body.addProperty("name", r.name());
                    body.addProperty("permissions", "0");
                    body.addProperty("mentionable", false);
                    body.addProperty("color", 0x57C84D);
                    id = rest.post("/guilds/" + c.guildId + "/roles", body).getAsJsonObject().get("id").getAsString();
                    getLogger().info("已在 Discord 创建身份组「" + r.name() + "」");
                }
                out.add(new BotConfig.PanelRole(r.key(), r.name(), r.type(), id, r.emoji(), r.description()));
            }
            sync(() -> {
                BotConfig.writePanelIds(getConfig(), out);
                saveConfig();
                cfg = BotConfig.load(getConfig());
                return null;
            });
            return out;
        }
    }

    /** 主线程调用：保存面板项目并重新加载配置。 */
    void savePanel(List<BotConfig.PanelRole> list) {
        BotConfig.writePanelIds(getConfig(), list);
        saveConfig();
        cfg = BotConfig.load(getConfig());
    }

    /** 把所有已发出的面板改成最新按钮。worker 线程调用；返回成功更新的个数。 */
    int refreshPanels() {
        if (rest == null) return 0;
        List<String> paths = subscriptions.panels();
        if (paths.isEmpty()) return 0;
        JsonObject msg;
        try {
            msg = Interactions.panelMessage(cfg, resolvePanelRoles());
        } catch (Exception e) {
            getLogger().warning("刷新面板失败：" + e.getMessage());
            return 0;
        }
        int ok = 0;
        for (String path : paths) {
            try {
                try {
                    rest.patch(path, msg);
                } catch (DiscordRest.DiscordException e) {
                    // 2026-10-12: the only panel was in a forum post that Discord had archived (50083) → unarchive once and retry, like PlotDesk
                    if (e.code != 50083) throw e;
                    JsonObject un = new JsonObject();
                    un.addProperty("archived", false);
                    rest.patch("/channels/" + path.split("/")[2], un);
                    getLogger().info("面板所在帖子已归档，已取消归档后刷新：" + path);
                    rest.patch(path, msg);
                }
                ok++;
            } catch (DiscordRest.DiscordException e) {
                if (e.code == 10008 || e.code == 10003) subscriptions.removePanel(path); // 面板已被删除
                else getLogger().warning("刷新面板 " + path + " 失败：" + e.friendly());
            } catch (IOException e) {
                getLogger().warning("刷新面板失败：" + e.getMessage());
            }
        }
        return ok;
    }

    void adminLog(String text) {
        if (rest == null || cfg.logChannel.isEmpty()) return;
        JsonObject msg = Interactions.message(BotRules.truncate(text, 2000), null);
        msg.add("allowed_mentions", Interactions.noMentions());
        String ch = cfg.logChannel;
        worker.execute(() -> postQuietly(ch, msg));
    }

    private void postQuietly(String channel, JsonObject msg) {
        try {
            rest.post("/channels/" + channel + "/messages", msg);
        } catch (DiscordRest.DiscordException e) {
            getLogger().warning("发送到频道 " + channel + " 失败：" + e.friendly());
        } catch (IOException e) {
            getLogger().log(Level.FINE, "发送 Discord 消息失败", e);
        }
    }

    private void refreshPresence() {
        if (gateway == null) return;
        int n = Bukkit.getOnlinePlayers().size();
        String state = n == 0 ? cfg.serverName + " · 等你来玩" : cfg.serverName + " · " + n + " 人在线";
        if (state.equals(lastPresence)) return;
        lastPresence = state;
        gateway.updatePresence(state);
    }

    // ================= 每日回放包（2026-10-07 daily replay bundle） =================

    /** 异步线程调用。now = 管理员 post-now；reply = 结果发给谁（null = 只写日志）。 */
    private void replayTick(boolean now, CommandSender reply) {
        if (replayPoster == null) return;
        if (!replayBusy.compareAndSet(false, true)) {
            if (reply != null) Bukkit.getScheduler().runTask(this, () -> reply.sendMessage(Component.text("正在发送中，稍后再看 /discordbot replays status", NamedTextColor.YELLOW)));
            return;
        }
        try {
            HubReplaySource src = HubReplaySource.find();
            String r = now ? replayPoster.postNow(src) : replayPoster.tick(src, false);
            if (reply != null) Bukkit.getScheduler().runTask(this, () -> reply.sendMessage(Component.text("回放包：" + r, NamedTextColor.AQUA)));
            else if (r.startsWith("已发")) getLogger().info("回放包：" + r);
        } catch (Exception e) {
            getLogger().warning("回放包发送出错：" + e);
        } finally {
            replayBusy.set(false);
        }
    }

    /**
     * 2026-10-12 (服主: 让 bot 可以由你操控发帖发公告): drafts in plugins/AminPlayDiscord/posts/&lt;name&gt;.md (format in {@link PostDrafts}),
     * attachments in posts/files/.
     * 2026-10-12 bot-posting: console only. "posts" lists; "post &lt;频道|别名&gt; &lt;草稿|text:…&gt;" sends a message, "forum &lt;论坛|别名&gt; &lt;草稿&gt;"
     * opens a forum post, "--dry-run" only checks (GET channel/guild, no POST); legacy "post &lt;草稿&gt; [confirm]" uses the draft's target.
     * Every send goes to the console, posts/sent.log and the admin log channel; the same draft to the same channel twice needs --again.
     * Drafts stay where they are (never moved or deleted).
     */
    private void postCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text("发帖只能在服务器控制台用（/discordbot post|forum|posts）。", NamedTextColor.RED));
            return;
        }
        java.nio.file.Path dir = getDataFolder().toPath().resolve("posts");
        try { java.nio.file.Files.createDirectories(dir.resolve("files")); }
        catch (IOException ex) { sender.sendMessage(Component.text("建不了 posts 文件夹：" + ex.getMessage(), NamedTextColor.RED)); return; }
        PostDrafts.Cmd c = PostDrafts.parseArgs(args);
        Map<String, String> aliases = postAliases();
        String modpackForum = getConfig().getString("posts.modpack-forum", "").trim();
        if (c.error() != null || c.kind() == PostDrafts.Kind.HELP) {
            if (c.error() != null) sender.sendMessage(Component.text(c.error(), NamedTextColor.RED));
            for (String line : PostDrafts.USAGE.split("\n")) sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
            sender.sendMessage(Component.text("频道别名：" + aliasText(aliases, modpackForum), NamedTextColor.GRAY));
            return;
        }
        if (c.kind() == PostDrafts.Kind.LIST) {
            List<String> names = new ArrayList<>();
            try (var st = java.nio.file.Files.list(dir)) { st.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".md")).sorted().forEach(n -> names.add(n.substring(0, n.length() - 3))); }
            catch (IOException ignored) { }
            sender.sendMessage(Component.text("草稿（plugins/AminPlayDiscord/posts/*.md）：" + (names.isEmpty() ? "没有" : names.size() + " 个"), NamedTextColor.AQUA));
            for (String n : names) {
                try {
                    PostDrafts.Draft d = PostDrafts.read(dir, n);
                    sender.sendMessage(Component.text("  " + PostDrafts.describe(n, d, PostDrafts.resolveTarget(d.target(), aliases, cfg.announceChannel, modpackForum)), NamedTextColor.WHITE));
                } catch (Exception ex) {
                    sender.sendMessage(Component.text("  " + n + "：有问题 —— " + ex.getMessage(), NamedTextColor.YELLOW));
                }
            }
            sender.sendMessage(Component.text("附件放在 posts/files/；频道别名：" + aliasText(aliases, modpackForum), NamedTextColor.GRAY));
            return;
        }
        PostDrafts.Draft d;
        String source;
        try {
            if (c.draft() == null) { d = PostDrafts.quick(c.quickText(), c.mode(), ""); source = "text:"; }
            else { d = PostDrafts.read(dir, c.draft()); source = "posts/" + c.draft() + ".md"; }
        } catch (java.nio.file.NoSuchFileException ex) {
            sender.sendMessage(Component.text("没有这个草稿：posts/" + c.draft() + ".md（/discordbot posts 看列表）", NamedTextColor.RED));
            return;
        } catch (Exception ex) {
            sender.sendMessage(Component.text("草稿有问题：" + ex.getMessage(), NamedTextColor.RED));
            return;
        }
        String targetName = c.target() != null ? c.target() : d.target();
        PostDrafts.Mode mode = c.target() != null ? c.mode() : d.mode();
        String channel = PostDrafts.resolveTarget(targetName, aliases, cfg.announceChannel, modpackForum);
        if (channel == null) {
            sender.sendMessage(Component.text("不认识的频道「" + targetName + "」或它还没设置。别名：" + aliasText(aliases, modpackForum)
                + "；新增别名：/discordbot set posts.aliases.<名字> <频道id>", NamedTextColor.RED));
            return;
        }
        boolean dry = c.dryRun();
        if (rest == null) {
            sender.sendMessage(Component.text("机器人没启动（没有 Token），只检查了草稿：" + PostDrafts.describe(c.draft() == null ? "text:" : c.draft(), d, channel), NamedTextColor.YELLOW));
            return;
        }
        java.nio.file.Path sentLog = dir.resolve("sent.log");
        if (!dry && c.draft() != null && !c.again()) {
            try {
                if (java.nio.file.Files.exists(sentLog) && PostDrafts.alreadySent(java.nio.file.Files.readAllLines(sentLog, java.nio.charset.StandardCharsets.UTF_8), channel, source)) {
                    sender.sendMessage(Component.text(source + " 已经发到过这个频道（见 posts/sent.log）。确实要再发一次请在命令末尾加 --again", NamedTextColor.YELLOW));
                    return;
                }
            } catch (IOException ignored) { }
        }
        if (!dry && !postBusy.compareAndSet(false, true)) {
            sender.sendMessage(Component.text("上一条还在发送中，等它发完再发。", NamedTextColor.YELLOW));
            return;
        }
        long maxBytes = (long) Math.max(1, Math.min(500, getConfig().getInt("posts.max-file-mb", 100))) << 20;
        String guild = cfg.guildId;
        DraftPoster poster = new DraftPoster(rest, maxBytes, DraftPoster.standardPings(cfg, notifier, subscriptions, this::resolveRoleId), cfg.serverName);
        if (!dry) sender.sendMessage(Component.text("正在发送 " + source + " → " + channel + " …", NamedTextColor.GRAY));
        worker.execute(() -> {
            List<String> out = new ArrayList<>();
            NamedTextColor color = NamedTextColor.GREEN;
            try {
                DraftPoster.Plan plan = poster.plan(d, mode, channel, dir.resolve("files"), guild);
                if (dry) {
                    out.addAll(plan.lines(source));
                    color = NamedTextColor.AQUA;
                } else {
                    DraftPoster.Sent s = poster.send(plan, guild);
                    String when = java.time.ZonedDateTime.now(cfg.collectZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    try {
                        java.nio.file.Files.writeString(sentLog, PostDrafts.logLine(when, channel, s.messageId(), source) + "\n", java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (IOException ex) { getLogger().warning("写 posts/sent.log 失败：" + ex.getMessage()); }
                    out.add("已发送：" + s.link());
                    getLogger().info("控制台发帖 " + when + " " + source + " → #" + plan.channelName() + "（" + channel + "）消息 " + s.messageId() + " " + s.link());
                    adminLog("📮 控制台发帖：" + source + " → <#" + channel + ">" + (mode == PostDrafts.Mode.FORUM ? "「" + BotRules.truncate(d.title(), 80) + "」" : "")
                        + " " + s.link() + "（消息 " + s.messageId() + "，" + when + "）");
                }
            } catch (DiscordRest.DiscordException ex) {
                out.add((dry ? "检查失败：" : "发送失败：") + ex.friendly()); color = NamedTextColor.RED;
                getLogger().warning("控制台发帖 " + source + " 失败：HTTP " + ex.status + " code " + ex.code);
            } catch (Exception ex) {
                out.add((dry ? "检查失败：" : "发送失败：") + ex.getMessage()); color = NamedTextColor.RED;
                if (!dry) getLogger().warning("控制台发帖 " + source + " 失败：" + ex.getMessage());
            } finally {
                if (!dry) postBusy.set(false);
            }
            NamedTextColor col = color;
            Bukkit.getScheduler().runTask(this, () -> { for (String line : out) sender.sendMessage(Component.text(line, col)); });
        });
    }

    /**
     * 2026-10-12 (服主: 「钱、排名、成就会合在一起；两个号都有地块的，合并功能下次更新开放」 — the post was already out): console only,
     * "edit &lt;消息链接&gt; &lt;草稿&gt; [--dry-run]" replaces the text of one message the bot sent (see {@link DraftPoster#planEdit}).
     * Logged like a send (posts/sent.log with "edit:", console, admin log).
     */
    private void editCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text("改帖只能在服务器控制台用（/discordbot edit）。", NamedTextColor.RED));
            return;
        }
        PostDrafts.EditCmd c = PostDrafts.parseEdit(args);
        if (c.error() != null) { sender.sendMessage(Component.text(c.error(), NamedTextColor.RED)); return; }
        java.nio.file.Path dir = getDataFolder().toPath().resolve("posts");
        PostDrafts.Draft d;
        try { d = PostDrafts.read(dir, c.draft()); }
        catch (java.nio.file.NoSuchFileException ex) { sender.sendMessage(Component.text("没有这个草稿：posts/" + c.draft() + ".md", NamedTextColor.RED)); return; }
        catch (Exception ex) { sender.sendMessage(Component.text("草稿有问题：" + ex.getMessage(), NamedTextColor.RED)); return; }
        if (rest == null) { sender.sendMessage(Component.text("机器人没启动（没有 Token），不能改帖。", NamedTextColor.YELLOW)); return; }
        boolean dry = c.dryRun();
        if (!dry && !postBusy.compareAndSet(false, true)) { sender.sendMessage(Component.text("上一条还在发送中，等它发完再改。", NamedTextColor.YELLOW)); return; }
        String source = "posts/" + c.draft() + ".md";
        DraftPoster poster = new DraftPoster(rest, 1, DraftPoster.standardPings(cfg, notifier, subscriptions, this::resolveRoleId), cfg.serverName);
        String guild = cfg.guildId;
        worker.execute(() -> {
            List<String> out = new ArrayList<>();
            NamedTextColor color = NamedTextColor.GREEN;
            try {
                DraftPoster.Edit e = poster.planEdit(d, c.channel(), c.messageId());
                if (dry) { out.addAll(e.lines(source)); color = NamedTextColor.AQUA; }
                else {
                    poster.edit(d, e);
                    String link = "https://discord.com/channels/" + (guild == null || guild.isEmpty() ? "@me" : guild) + "/" + c.channel() + (c.channel().equals(c.messageId()) ? "" : "/" + c.messageId());
                    String when = java.time.ZonedDateTime.now(cfg.collectZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    try {
                        java.nio.file.Files.writeString(dir.resolve("sent.log"), PostDrafts.logLine(when, c.channel(), c.messageId(), "edit:" + source) + "\n", java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    } catch (IOException ex) { getLogger().warning("写 posts/sent.log 失败：" + ex.getMessage()); }
                    out.add("已修改：" + link);
                    getLogger().info("控制台改帖 " + when + " " + source + " → 消息 " + c.messageId() + "（" + c.channel() + "） " + link);
                    adminLog("✏️ 控制台改帖：" + source + " → " + link + "（" + when + "）");
                }
            } catch (DiscordRest.DiscordException ex) {
                out.add((dry ? "检查失败：" : "修改失败：") + ex.friendly()); color = NamedTextColor.RED;
                getLogger().warning("控制台改帖 " + source + " 失败：HTTP " + ex.status + " code " + ex.code);
            } catch (Exception ex) {
                out.add((dry ? "检查失败：" : "修改失败：") + ex.getMessage()); color = NamedTextColor.RED;
            } finally {
                if (!dry) postBusy.set(false);
            }
            NamedTextColor col = color;
            Bukkit.getScheduler().runTask(this, () -> { for (String line : out) sender.sendMessage(Component.text(line, col)); });
        });
    }

    /** 2026-10-12 bot-posting: posts.aliases from config.yml (alias → channel id). */
    private Map<String, String> postAliases() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        org.bukkit.configuration.ConfigurationSection s = getConfig().getConfigurationSection("posts.aliases");
        if (s != null) for (String k : s.getKeys(false)) { Object v = s.get(k); if (v != null) out.put(k, String.valueOf(v).trim()); }
        return out;
    }

    private String aliasText(Map<String, String> aliases, String modpackForum) {
        List<String> parts = new ArrayList<>();
        parts.add("announce=" + (cfg.announceChannel.isEmpty() ? "未设置" : cfg.announceChannel));
        parts.add("modpack=" + (modpackForum.isEmpty() ? "未设置" : modpackForum));
        aliases.forEach((k, v) -> parts.add(k + "=" + (v.isEmpty() ? "未设置" : v)));
        return String.join("，", parts);
    }

    private void replaysCommand(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        if (replayPoster == null) {
            sender.sendMessage(Component.text("回放贴：机器人没启动（没有 Token）。", NamedTextColor.YELLOW));
            return;
        }
        if (sub.equals("post-now")) {
            sender.sendMessage(Component.text("正在发送待发的回放包（没有的话先让 MiniGameHub 把上次截止到现在的对局打包）……", NamedTextColor.GRAY));
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> replayTick(true, sender));
            return;
        }
        HubReplaySource src = HubReplaySource.find();
        for (String line : replayPoster.status(src)) sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/discordbot replays status | post-now；/discordbot set replays.forum-channel <id> | replays.thread-id <id>", NamedTextColor.GRAY));
    }

    // ================= 游戏内 /discordbot =================

    /**
     * /discordbot panel list
     * /discordbot panel add <key> role <身份组id> <名字>
     * /discordbot panel add <key> subscribe <名字>
     * /discordbot panel emoji <key> <表情>   /discordbot panel desc <key> <说明>
     * /discordbot panel remove <key>
     */
    private void panelCommand(CommandSender sender, String[] args) {
        List<BotConfig.PanelRole> list = new ArrayList<>(cfg.panelRoles);
        String sub = args[1].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("list")) {
            if (list.isEmpty()) sender.sendMessage(Component.text("面板是空的。", NamedTextColor.YELLOW));
            for (BotConfig.PanelRole r : list) {
                String extra = r.subscribe() ? "订阅 · " + subscriptions.count(r.key()) + " 人" : "身份组 " + r.id();
                sender.sendMessage(Component.text(r.key() + " = " + r.emoji() + " " + r.name() + "（" + extra + "）" + (r.description().isEmpty() ? "" : " — " + r.description()), NamedTextColor.AQUA));
            }
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("用法：/discordbot panel <list|add|remove|emoji|desc> …", NamedTextColor.YELLOW));
            return;
        }
        String key = args[2].replace("|", "_");
        int idx = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).key().equals(key)) idx = i;
        String rest = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
        switch (sub) {
            case "add" -> {
                String[] p = rest.split(" ", 3);
                BotConfig.PanelRole r;
                if (p.length >= 3 && p[0].equalsIgnoreCase("role")) {
                    r = new BotConfig.PanelRole(key, p[2], "role", p[1].replaceAll("[^0-9]", ""), "", "");
                } else if (p.length >= 2 && p[0].equalsIgnoreCase("subscribe")) {
                    r = new BotConfig.PanelRole(key, rest.substring(p[0].length() + 1), "subscribe", "", "", "");
                } else {
                    sender.sendMessage(Component.text("用法：panel add <key> role <身份组id> <名字>  或  panel add <key> subscribe <名字>", NamedTextColor.YELLOW));
                    return;
                }
                if (idx >= 0) {
                    BotConfig.PanelRole old = list.get(idx);
                    list.set(idx, new BotConfig.PanelRole(key, r.name(), r.type(), r.id(), old.emoji(), old.description()));
                } else list.add(r);
            }
            case "remove" -> {
                if (idx < 0) {
                    sender.sendMessage(Component.text("没有 key=" + key + " 的项目。", NamedTextColor.YELLOW));
                    return;
                }
                list.remove(idx);
            }
            case "emoji", "desc" -> {
                if (idx < 0) {
                    sender.sendMessage(Component.text("没有 key=" + key + " 的项目。", NamedTextColor.YELLOW));
                    return;
                }
                BotConfig.PanelRole o = list.get(idx);
                list.set(idx, sub.equals("emoji")
                        ? new BotConfig.PanelRole(o.key(), o.name(), o.type(), o.id(), rest, o.description())
                        : new BotConfig.PanelRole(o.key(), o.name(), o.type(), o.id(), o.emoji(), rest));
            }
            default -> {
                sender.sendMessage(Component.text("用法：/discordbot panel <list|add|remove|emoji|desc> …", NamedTextColor.YELLOW));
                return;
            }
        }
        savePanel(list);
        if (worker != null) worker.execute(this::refreshPanels);
        sender.sendMessage(Component.text("面板已更新（已发出的面板会自动刷新）：", NamedTextColor.GREEN));
        panelCommand(sender, new String[]{"panel", "list"});
    }

    /** /discordbot admins|sources list | add <id> | remove <id>：roles.admin（管理身份组）/ collect.sources（可收集的反馈频道）。 */
    private void idListCommand(CommandSender sender, String[] args, String path, String what) {
        List<String> list = new java.util.ArrayList<>(getConfig().getStringList(path));
        String op = args[1].toLowerCase(java.util.Locale.ROOT);
        if (op.equals("list")) {
            sender.sendMessage(Component.text(what + "：" + (list.isEmpty() ? "（无）" : String.join("、", list)), NamedTextColor.AQUA));
            return;
        }
        if ((op.equals("add") || op.equals("remove")) && args.length >= 3) {
            String id = args[2].replaceAll("[^0-9]", "");
            if (id.isEmpty()) { sender.sendMessage(Component.text("id 只能是数字", NamedTextColor.RED)); return; }
            boolean changed = op.equals("add") ? !list.contains(id) && list.add(id) : list.remove(id);
            if (changed) { getConfig().set(path, list); saveConfig(); cfg = BotConfig.load(getConfig()); }
            sender.sendMessage(Component.text((changed ? "已" + (op.equals("add") ? "添加 " : "移除 ") : "没有变化：") + id, changed ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("/discordbot " + args[0].toLowerCase(java.util.Locale.ROOT) + " list | add <id> | remove <id>", NamedTextColor.YELLOW));
    }

    /** /discordbot set 能改的项目（故意不包括 token：Token 只在 config.yml 里手动填）。 */
    private static final List<String> SETTABLE = List.of("discord.guild-id", "channels.announce", "channels.status",
            "channels.bridge", "channels.log", "server.name", "server.address", "bridge.chat", "bridge.join-leave", "bridge.read-messages",
            "notify.ping-on-start", "notify.presence", "replays.forum-channel", "replays.thread-id", "replays.enabled",
            "channels.collect", "collect.timezone", "replays.forum-tag", "channels.plots", "posts.modpack-forum",
            "match-chat.enabled", "match-chat.channel", "match-chat.mention", "match-chat.private-rooms", "match-chat.games", "match-chat.close-delay-seconds",   // 2026-10-15 match-chat
            "channels.feedback", "channels.highlights",
            "ping.public", "ping.user-cooldown-seconds", "ping.group-cooldown-seconds", "ping.max-length");   // 2026-10-18 public ping   // 2026-10-17 对局精彩回放: channels.highlights   // 2026-10-17 feedback: channels.feedback   // 2026-10-12 drafts: posts.modpack-forum   // 2026-10-08 bot-plots: channels.plots   // 2026-10-07 message collect   // 2026-10-07 daily replay bundle: replays.*

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            BotConfig old = cfg;
            cfg = BotConfig.load(getConfig());
            if (rest != null) collector = newCollector();   // 2026-10-07 message collect：时区 / 上限改了马上生效
            hookMatchChat();   // 2026-10-15 match-chat: start / stop receiving match events
            if (!old.token.equals(cfg.token) || old.intents() != cfg.intents() || rest == null) {
                sender.sendMessage(Component.text("配置已重新加载。Token 或聊天互通开关有变化，需要重启服务器才会生效。", NamedTextColor.YELLOW));
            } else {
                if ((!old.guildId.equals(cfg.guildId) || old.webLoginEnabled != cfg.webLoginEnabled) && applicationId != null) worker.execute(this::registerCommands);
                sender.sendMessage(Component.text("配置已重新加载。", NamedTextColor.GREEN));
            }
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("set")) {
            String path = args[1];
            boolean alias = path.matches("posts\\.aliases\\.[A-Za-z0-9_-]{1,32}");   // 2026-10-12 bot-posting: /discordbot set posts.aliases.<名字> <频道id>
            if (alias && args.length >= 3 && !args[2].matches("\\d{15,25}")) {
                sender.sendMessage(Component.text("频道别名的值要写频道的数字 id（留空 = 清除）", NamedTextColor.YELLOW));
                return true;
            }
            if (!alias && !SETTABLE.contains(path)) {
                sender.sendMessage(Component.text("能设置的项目：" + String.join("、", SETTABLE) + "、posts.aliases.<名字>", NamedTextColor.YELLOW));
                return true;
            }
            String value = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
            if (path.startsWith("bridge.") || path.startsWith("notify.") || path.equals("replays.enabled")
                    || path.equals("match-chat.enabled") || path.equals("match-chat.mention") || path.equals("match-chat.private-rooms")) getConfig().set(path, Boolean.parseBoolean(value));
            else if (path.equals("match-chat.games") || path.equals("ping.public")) {   // 2026-10-15 match-chat: comma separated gameIds, empty = all   // 2026-10-18 public ping: panel keys
                List<String> games = new ArrayList<>();
                for (String g : value.split("[,，\\s]+")) if (!g.isBlank()) games.add(g.trim());
                getConfig().set(path, games);
            } else if (path.equals("match-chat.close-delay-seconds")) {
                int secs;
                try {
                    secs = Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("match-chat.close-delay-seconds 要写秒数（0-3600）", NamedTextColor.YELLOW));
                    return true;
                }
                getConfig().set(path, Math.max(0, Math.min(3600, secs)));
            } else if (path.startsWith("ping.")) {   // 2026-10-18 public ping: cooldown seconds / max length
                int n;
                try {
                    n = Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text(path + " 要写数字", NamedTextColor.YELLOW));
                    return true;
                }
                getConfig().set(path, Math.max(0, n));
            } else getConfig().set(path, alias && value.isEmpty() ? null : value);   // 2026-10-12 bot-posting: empty alias = removed
            saveConfig();
            sender.sendMessage(Component.text("已设置 " + path + " = " + (value.isEmpty() ? "（空）" : value) + "，正在重新加载…", NamedTextColor.GREEN));
            boolean r = onCommand(sender, command, label, new String[]{"reload"});
            if (path.equals("channels.plots") && !value.isEmpty() && worker != null && plots != null) worker.execute(() -> plots.catchUp(20));   // 2026-10-08 bot-plots
            return r;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("admins")) {   // 2026-10-08 github: roles.admin by command
            idListCommand(sender, args, "roles.admin", "管理身份组");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("sources")) {   // 2026-10-08 privacy: collect.sources by command
            idListCommand(sender, args, "collect.sources", "反馈频道");
            collector = newCollector();
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("collect")) {   // 2026-10-07 message collect
            collectCommand(sender, args);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("panel")) {
            panelCommand(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("edit")) {   // 2026-10-12 edit a message the bot already sent
            editCommand(sender, args);
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("post") || args[0].equalsIgnoreCase("forum") || args[0].equalsIgnoreCase("posts"))) {   // 2026-10-12 drafts + bot-posting: post / forum / posts
            postCommand(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("replays")) {   // 2026-10-07 daily replay bundle
            replaysCommand(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("keys")) {   // 2026-10-07 auth-gate
            HubAuthKeys k = authKeys();
            try {
                sender.sendMessage(Component.text(k == null ? "学习版密钥：MiniGameHub 接口不可用" : "学习版密钥：" + k.status() + "，机器人 keys.enabled=" + cfg.keysEnabled
                        + "，领取门槛 账号 " + cfg.keysMinAccountDays + " 天 / 入群 " + cfg.keysMinMemberDays + " 天", NamedTextColor.AQUA));
            } catch (Exception ex) {
                sender.sendMessage(Component.text("学习版密钥：出错 " + ex, NamedTextColor.RED));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("plots")) {   // 2026-10-08 bot-plots: /discordbot plots [post-open]
            if (plots == null) {
                sender.sendMessage(Component.text("类脑市地块申请：机器人没启动（没有 Token）。", NamedTextColor.YELLOW));
                return true;
            }
            HubCity c = city();
            if (args.length >= 2 && args[1].equalsIgnoreCase("post-open")) {
                sender.sendMessage(Component.text("正在补发还没发到管理频道的待审申请（最多 50 条）…", NamedTextColor.GRAY));
                worker.execute(() -> {
                    int n = plots.catchUp(50);
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(Component.text("已补发 " + n + " 条。", NamedTextColor.GREEN)));
                });
                return true;
            }
            Map<String, Object> st = null;
            try {
                if (c != null) st = c.status();
            } catch (Exception ignored) {
            }
            for (String line : plots.status(st)) sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
            if (c != null && !c.missing().isEmpty()) sender.sendMessage(Component.text("接口缺少的方法：" + c.missing(), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/discordbot plots | plots post-open；/discordbot set channels.plots <id>", NamedTextColor.GRAY));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            String state = rest == null ? "未启动（没有 Token）" : applicationId == null ? "连接中…" : "已连接";
            sender.sendMessage(Component.text("Discord 机器人：" + state + "，guild-id=" + (cfg.guildId.isEmpty() ? "未设置" : cfg.guildId)
                    + "，斜杠指令" + (commandsRegistered ? "已注册" : "未注册"), NamedTextColor.AQUA));
            return true;
        }
        return false;
    }
}
