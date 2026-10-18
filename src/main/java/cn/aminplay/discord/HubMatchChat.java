package cn.aminplay.discord;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-10-15 match-chat：MiniGameHub 注册的 Bukkit 服务 cn.friendhub.chat.MatchChatApi（对局房间聊天），只用 JDK 类型。
 * 和 {@link HubAuthKeys} 一样用反射调用；MiniGameHub 没装 / 太旧时 {@link #find()} 返回 null，对局子区和互通过滤都静默关闭。
 * 契约见 docs/plans/2026-10-15-match-chat.md 第 2 节。
 */
class HubMatchChat {
    static final String API = "cn.friendhub.chat.MatchChatApi";

    private final Object provider;
    private final Method setSink, post, inMatch;

    /** 测试用：子类不走反射。 */
    HubMatchChat() {
        provider = null;
        setSink = post = inMatch = null;
    }

    private HubMatchChat(Class<?> api, Object provider) throws NoSuchMethodException {
        this.provider = provider;
        setSink = api.getMethod("setSink", Consumer.class);
        post = api.getMethod("post", String.class, String.class, String.class, String.class, String.class);
        inMatch = api.getMethod("inMatch", String.class);
    }

    static HubMatchChat find() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals(API)) continue;
                Object p = Bukkit.getServicesManager().load(c);
                if (p != null) return new HubMatchChat(c, p);
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    private Object call(Method m, Object... args) throws Exception {
        try {
            return m.invoke(provider, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    /** 主线程调用。null = 清除。 */
    void setSink(Consumer<Map<String, Object>> sink) throws Exception { call(setSink, sink); }

    /** Discord → 游戏。任意线程可调。status: ok | no-match | no-room | ghost-only | error */
    @SuppressWarnings("unchecked")
    Map<String, Object> post(String match, String room, String discordId, String displayName, String text) throws Exception {
        Object o = call(post, match, room, discordId, displayName, text);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("status", "error");
    }

    /** 玩家（uuid 字符串）是否在进行中的对局里（含观战）。 */
    boolean inMatch(String uuid) throws Exception { return Boolean.TRUE.equals(call(inMatch, uuid)); }
}
