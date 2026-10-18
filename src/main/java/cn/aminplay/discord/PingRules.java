package cn.aminplay.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-10-18 一般成员也能用 /minmin 通知：只能 @ ping.public 里的面板项目（例如「缺人找我」），
 * 只能发在当前频道，每人、每个项目各有冷却。管理员不受这些限制。
 */
final class PingRules {
    private final Map<String, Long> byUser = new ConcurrentHashMap<>();
    private final Map<String, Long> byGroup = new ConcurrentHashMap<>();

    /** 一般成员能 @ 的项目（按面板顺序）。 */
    static List<BotConfig.PanelRole> publicEntries(BotConfig cfg) {
        List<BotConfig.PanelRole> out = new ArrayList<>();
        for (BotConfig.PanelRole r : cfg.panelRoles) if (cfg.pingPublic.contains(r.key())) out.add(r);
        return out;
    }

    /** 一般成员不能 @ 时的提示；null = 可以。channelOption = 指令里填的频道（没填 = null）。 */
    String refusal(BotConfig cfg, BotConfig.PanelRole entry, String userId, String channelOption, String currentChannel, long nowMs) {
        List<BotConfig.PanelRole> open = publicEntries(cfg);
        if (open.isEmpty()) return "⛔ 现在没有开放给大家 @ 的身份组，只有管理员能用这个指令。";
        if (entry == null || !cfg.pingPublic.contains(entry.key())) {
            List<String> names = new ArrayList<>();
            for (BotConfig.PanelRole r : open) names.add("「" + r.name() + "」");
            return "⛔ 你只能 @ " + String.join("、", names) + "，请从下拉列表里选。";
        }
        if (channelOption != null && !channelOption.equals(currentChannel)) return "⛔ 只能 @ 在你现在所在的频道里，不用填「频道」。";
        long left = Math.max(remaining(byUser.get(userId), cfg.pingUserCooldownSeconds, nowMs),
                remaining(byGroup.get(entry.key()), cfg.pingGroupCooldownSeconds, nowMs));
        if (left > 0) return "⏳ 刚刚有人 @ 过，" + waitText(left) + "后再试。";
        return null;
    }

    /** @ 成功后记下冷却。 */
    void record(String userId, String key, long nowMs) {
        byUser.put(userId, nowMs);
        byGroup.put(key, nowMs);
    }

    /** 一般成员 @ 时跟在后面的话：标明是谁叫的，去掉换行，限制长度。 */
    static String tail(String userId, String text, int maxLength) {
        String t = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        String who = " — <@" + userId + "> 呼叫";
        return t.isEmpty() ? who : who + "：" + BotRules.truncate(t, maxLength);
    }

    private static long remaining(Long last, int seconds, long nowMs) {
        if (last == null || seconds <= 0) return 0;
        return Math.max(0, last + seconds * 1000L - nowMs);
    }

    private static String waitText(long ms) {
        long s = (ms + 999) / 1000;
        return s >= 60 ? ((s + 59) / 60) + " 分钟" : s + " 秒";
    }
}
