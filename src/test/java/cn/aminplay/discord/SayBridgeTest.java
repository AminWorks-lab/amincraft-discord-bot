package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;

/** 2026-10-17 聊天互通不读频道消息（没有 Message Content Intent）时：游戏 → Discord 照发，Discord → 游戏用 /minmin 说。 */
public final class SayBridgeTest {
    private static int passed, failed;
    private static final String BRIDGE = "111111111111111111";

    public static void main(String[] args) {
        BotConfig off = cfg(false, false), sendOnly = cfg(true, false), twoWay = cfg(true, true);
        check(!sendOnly.bridgeRead, "bridge.read-messages 默认关");
        check(sendOnly.intents() == DiscordGateway.INTENT_GUILDS, "只发不读的互通不要 Message Content Intent");
        check((twoWay.intents() & DiscordGateway.INTENT_MESSAGE_CONTENT) != 0, "read-messages 开时仍要 Message Content Intent");
        check(off.intents() == DiscordGateway.INTENT_GUILDS, "互通关时不要额外 intent");

        check(Interactions.sayRefusal(off, BRIDGE, false) != null, "互通关时 /说 被拒");
        check(Interactions.sayRefusal(sendOnly, "222222222222222222", false).contains(BRIDGE), "别的频道里 /说 指到互通频道");
        check(Interactions.sayRefusal(sendOnly, BRIDGE, true) != null, "退出互通的人 /说 被拒");
        check(Interactions.sayRefusal(sendOnly, BRIDGE, false) == null, "互通频道里 /说 可以发");
        check(Interactions.sayRefusal(twoWay, BRIDGE, false) == null, "双向时 /说 也能用");

        JsonArray subs = Interactions.definitions(true).get(0).getAsJsonObject().getAsJsonArray("options");
        JsonObject say = null;
        for (var e : subs) if (e.getAsJsonObject().get("name").getAsString().equals("say")) say = e.getAsJsonObject();
        check(say != null && !say.has("default_member_permissions"), "/minmin 说 对所有人开放");
        check(say != null && say.getAsJsonArray("options").get(0).getAsJsonObject().get("max_length").getAsInt() == Interactions.SAY_MAX, "内容有长度上限");
        check(subs.size() <= 25, "子指令不超过 Discord 上限 25（含网页登录码）：" + subs.size());
        check(Interactions.commandSummary().contains("说"), "注册日志列出 说");

        System.out.println("SayBridgeTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static BotConfig cfg(boolean chat, boolean read) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("discord.guild-id", "900");
        c.set("channels.bridge", BRIDGE);
        c.set("bridge.chat", chat);
        if (read) c.set("bridge.read-messages", true);
        return BotConfig.load(c);
    }

    private static void check(boolean condition, String name) {
        if (condition) passed++;
        else { failed++; System.out.println("FAIL " + name); }
    }
}
