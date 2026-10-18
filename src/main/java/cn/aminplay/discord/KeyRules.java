package cn.aminplay.discord;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** 2026-10-07 auth-gate：学习版密钥相关的纯逻辑（资格、回复文字），由 KeyFlowTest 覆盖。 */
final class KeyRules {
    private KeyRules() {}

    static final long DAY = 86_400_000L;

    /** Discord 雪花 id → 账号创建时间（毫秒）。 */
    static long created(String snowflake) {
        try {
            return (Long.parseUnsignedLong(snowflake) >>> 22) + 1420070400000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** null = 可以领；否则是给用户看的原因。joinedAt = 交互里 member.joined_at（ISO 时间）。 */
    static String ineligible(String userId, String joinedAt, long now, int minAccountDays, int minMemberDays) {
        long c = created(userId);
        if (c < 0) return "无法识别你的 Discord 账号。";
        if (minAccountDays > 0 && now - c < minAccountDays * DAY) {
            long left = (c + minAccountDays * DAY - now + DAY - 1) / DAY;
            return "你的 Discord 账号注册不满 " + minAccountDays + " 天，还要等 " + left + " 天才能领取密钥。";
        }
        if (minMemberDays > 0) {
            long j;
            try {
                j = joinedAt == null || joinedAt.isBlank() ? -1 : Instant.parse(joinedAt).toEpochMilli();
            } catch (DateTimeParseException e) {
                j = -1;
            }
            if (j < 0) return "无法确认你加入本服务器的时间，请在服务器频道里使用这个指令。";
            if (now - j < minMemberDays * DAY) {
                long left = (j + minMemberDays * DAY - now + DAY - 1) / DAY;
                return "你加入本 Discord 服务器不满 " + minMemberDays + " 天，还要等 " + left + " 天才能领取密钥。";
            }
        }
        return null;
    }

    static String howTo(String key) {
        return "1. 用启动器的离线账号（HMCL / PCL）选一个**不是正版玩家用的名字**进服（正版名字会提示「无效会话」）\n"
                + "2. 进服后输入 `/key " + key + "`\n"
                + "3. 再设置密码：`/register 密码 密码`（以后进服 `/login 密码`）\n"
                + "⚠️ 一个 Discord 账号只能绑定一个游戏名；不要把密钥发给别人。学习版连接不加密，密码别和其他地方一样。";
    }

    /** 仅自己可见的回复。dm: null = 没尝试私信，true = 私信备份已发，false = 私信发不出去。 */
    static String claimReply(Map<String, Object> r, Boolean dm) {
        String st = String.valueOf(r.get("status"));
        return switch (st) {
            case "new", "resend" -> "🔑 你的 Amincraft 学习版登录密钥：`" + r.get("key") + "`"
                    + (st.equals("resend") ? "（之前领过，还没绑定，这是同一个密钥）" : "") + "\n" + howTo(String.valueOf(r.get("key")))
                    + (dm == null ? "" : dm ? "\n📩 已私信你一份备份。" : "\n📪 私信备份没发出去（你关闭了服务器成员私信）。请自己保存好这条消息里的密钥。");
            case "bound" -> "你的密钥已经绑定游戏名 **" + r.get("name") + "**，可以直接进服登录。换名字请找管理员。";
            case "banned" -> "⛔ 你的 Discord 账号已被封禁，不能领取密钥。" + (r.get("reason") == null ? "" : "原因：" + r.get("reason"));
            case "disabled" -> "现在服务器不需要密钥（学习版密钥功能没有开启）。";
            default -> "❌ 领取失败，请稍后再试或联系管理员。";
        };
    }

    static String dmCopy(String key) {
        return "🔑 你的 Amincraft 学习版登录密钥（备份）：`" + key + "`\n" + howTo(key);
    }

    /** 2026-10-11 account-link: answer to 「关联正版账号」 (status from MiniGameHub requestPremiumLink). */
    static String premiumReply(Map<String, Object> r) {
        Object name = r.get("name");
        return switch (String.valueOf(r.get("status"))) {
            case "sent" -> "📨 已经在游戏里给 **" + name + "** 弹出确认窗口，请在游戏里点「确认关联」（10 分钟内有效）。";
            case "offline" -> "这个正版账号现在不在线。请先进服，再点一次「关联正版账号」。";
            case "not-premium" -> "**" + name + "** 不是正版登录的账号。学习版玩家请用「领取学习版登录密钥」。";
            case "already" -> "✅ **" + name + "** 已经关联到你的 Discord 了。";
            case "has-other" -> "你的 Discord 已经关联了正版账号 **" + r.get("other") + "**。一个 Discord 只能关联一个正版账号，要换请找管理员。";
            case "taken" -> "这个正版账号已经关联了别的 Discord 账号。如果不是你关联的，请找管理员。";
            case "banned" -> "你的 Discord 账号已被封禁，不能关联。";
            case "too-many" -> "这个正版账号已经和另一个游戏号合并过了，不能再关联。需要调整请找管理员。";
            case "disabled" -> "服务器现在没有开启正版关联。";
            default -> "游戏服务器暂时没法处理，请稍后再试。";
        };
    }
    static String premiumResult(String name, String status) {
        return switch (status) {
            case "ok" -> "✅ 正版账号 **" + name + "** 已关联到你的 Discord。现在可以用 Discord 登录网站；如果你还绑了学习版账号，两个号会合成一个人（钱、地、排名、成就都算同一份）。";
            case "denied" -> "游戏里的 **" + name + "** 拒绝了这次关联，没有任何改动。";
            default -> "**" + name + "** 没有关联成功：这个账号或你的 Discord 已经关联过别的账号了。";
        };
    }

    static String loginConfirm(String name, int seconds) {
        return "🔐 有人用 **" + name + "** 登录 Amincraft，是你吗？\n" + seconds + " 秒内点「是我」就直接登录（不用输密码）；不是你就点「不是我」，对方会被踢出。";
    }

    /** 一条记录给管理员看的一行（不含密钥；IP 只有哈希个数）。 */
    static String describe(Map<String, Object> r) {
        StringBuilder b = new StringBuilder();
        b.append("Discord <@").append(r.get("discordId")).append(">");
        if (Boolean.TRUE.equals(r.get("bound"))) {
            b.append(" → **").append(r.get("name")).append("**（绑定 <t:").append(num(r.get("boundAt")) / 1000).append(":f>）");
        } else b.append(" → 未绑定");
        long seen = num(r.get("lastSeen"));
        if (seen > 0) b.append(" · 最近进服 <t:").append(seen / 1000).append(":R>");
        Object h = r.get("history");
        if (h instanceof java.util.List<?> l && !l.isEmpty()) b.append(" · 曾用名 ").append(String.join("、", l.stream().map(String::valueOf).toList()));
        Object ips = r.get("ipHashes");
        if (ips instanceof java.util.List<?> l) b.append(" · IP 指纹 ").append(l.size()).append(" 个");
        if (Boolean.TRUE.equals(r.get("banned"))) b.append(" · ⛔ 已封禁（").append(r.get("banReason")).append("，来源 ").append(r.get("banSource")).append("）");
        return b.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
