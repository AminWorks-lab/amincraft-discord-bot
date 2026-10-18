package cn.aminplay.discord;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 2026-10-12 bot-promo：每 30 秒检查一次定时广告，到点就往对应频道 / 子区发广告卡片。不依赖 Bukkit（在线人数由 Host 提供）。
 * 频道不存在 / 没权限（403、404）时自动停用这条设置并记录警告，不会崩溃；其它错误下次检查再试（宽限时间内）。
 */
final class PromoPoster {
    interface Host {
        DiscordRest rest();

        ZoneId zone();

        Duration minGap();

        Duration grace();

        PromoRules.Card card();

        /** {在线, 上限}；取不到返回 null。 */
        int[] online() throws Exception;

        void adminLog(String text);
    }

    private final Host host;
    private final PromoStore store;
    private final Logger log;
    private final AtomicBoolean busy = new AtomicBoolean();

    PromoPoster(Host host, PromoStore store, Logger log) {
        this.host = host;
        this.store = store;
        this.log = log;
    }

    PromoStore store() {
        return store;
    }

    /** 组一条广告消息（不 @ 任何人）。 */
    JsonObject message(String customText, Instant now) {
        int[] on = null;
        try {
            on = host.online();
        } catch (Exception e) {
            log.fine("定时广告：取在线人数失败 " + e);
        }
        JsonObject embed = PromoRules.embed(host.card(), customText, on == null ? -1 : on[0], on == null ? 0 : on[1], now);
        JsonObject msg = new JsonObject();   // 不用 Interactions.message：本类要能离线（无 Bukkit）测试
        com.google.gson.JsonArray embeds = new com.google.gson.JsonArray();
        embeds.add(embed);
        msg.add("embeds", embeds);
        JsonObject none = new JsonObject();
        none.add("parse", new com.google.gson.JsonArray());
        msg.add("allowed_mentions", none);
        return msg;
    }

    /** 立刻发一条（预览）。出错抛给调用方显示。 */
    void postNow(String channelId, String customText, Instant now) throws IOException {
        host.rest().post("/channels/" + channelId + "/messages", message(customText, now));
        store.markPostedNow(channelId, now.toEpochMilli());
    }

    /** 定时器调用。返回这次发出的条数。 */
    int tick(Instant now) {
        if (!busy.compareAndSet(false, true)) return 0;
        int sent = 0;
        try {
            for (PromoStore.Entry e : store.all()) {
                if (!e.active()) continue;
                PromoRules.Decision d = PromoRules.decide(e.rule(), now, host.zone(), e.lastSlot(), e.lastPostedAt(), host.minGap(), host.grace());
                switch (d.action()) {
                    case NONE -> {
                    }
                    case SKIP -> {
                        log.info("定时广告：频道 " + e.channelId() + " 跳过 " + PromoRules.fmtSlot(d.slot(), host.zone()) + "（" + d.why() + "）");
                        store.update(e, e.withSlot(d.slot()));
                    }
                    case POST -> {
                        try {
                            host.rest().post("/channels/" + e.channelId() + "/messages", message(e.text(), now));
                            store.update(e, e.withPosted(d.slot(), now.toEpochMilli()));
                            sent++;
                        } catch (DiscordRest.DiscordException ex) {
                            if (gone(ex)) {
                                String why = "HTTP " + ex.status + (ex.code == 0 ? "" : " / " + ex.code) + "：" + ex.friendly();
                                log.warning("定时广告：频道 " + e.channelId() + " 发不出去，已停用这条定时广告（" + why + "）");
                                store.update(e, e.withSlot(d.slot()).withDisabled(BotRules.truncate(why, 200)));
                                host.adminLog("⚠️ 定时广告发不出去，已自动停用：<#" + e.channelId() + ">（" + BotRules.truncate(ex.friendly(), 200) + "）。修好权限后用 /minmin 定时广告 设置 重新开启");
                            } else {
                                log.warning("定时广告：频道 " + e.channelId() + " 发送失败，稍后重试：" + ex.getMessage());
                            }
                        } catch (IOException | RuntimeException ex) {
                            log.warning("定时广告：频道 " + e.channelId() + " 发送失败，稍后重试：" + ex);
                        }
                    }
                }
            }
        } finally {
            busy.set(false);
        }
        return sent;
    }

    /** 频道没了 / 机器人看不到 / 没权限：再试也没用。 */
    static boolean gone(DiscordRest.DiscordException ex) {
        return ex.status == 403 || ex.status == 404 || ex.code == 10003 || ex.code == 50001 || ex.code == 50013 || ex.code == 50083;
    }
}
