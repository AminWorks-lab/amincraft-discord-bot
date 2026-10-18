package cn.aminplay.discord;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-10-07 auth-gate：MiniGameHub 注册的 Bukkit 服务 cn.friendhub.auth.CrackedKeyApi（学习版玩家 Discord 密钥表），只用 JDK 类型。
 * 和 {@link HubReplaySource} 一样用反射调用，本插件不需要拿 MiniGameHub 编译；MiniGameHub 没装 / 太旧时 {@link #find()} 返回 null。
 * 密钥只在 claim() 的返回值里出现：只发给那一个 Discord 用户（仅自己可见的回复 + 私信备份），不写日志。
 */
class HubAuthKeys {
    static final String API = "cn.friendhub.auth.CrackedKeyApi";

    private final Object provider;
    private final Method claim, lookup, banDiscord, unbanDiscord, resolvePlayer, isVerified, confirmLogin, setEventSink, status;
    private final Method claimWebLogin;
    private final Method requestPremiumLink;   // 2026-10-11 account-link (optional: older MiniGameHub has none)

    /** 测试用：子类不走反射。 */
    HubAuthKeys() {
        provider = null;
        claim = lookup = banDiscord = unbanDiscord = resolvePlayer = isVerified = confirmLogin = setEventSink = status = null;
        claimWebLogin = null;
        requestPremiumLink = null;
    }

    private HubAuthKeys(Class<?> api, Object provider) throws NoSuchMethodException {
        this.provider = provider;
        claim = api.getMethod("claim", String.class, String.class);
        lookup = api.getMethod("lookup", String.class);
        banDiscord = api.getMethod("banDiscord", String.class, String.class, String.class, long.class);
        unbanDiscord = api.getMethod("unbanDiscord", String.class, String.class);
        resolvePlayer = api.getMethod("resolvePlayer", String.class);
        isVerified = api.getMethod("isVerified", String.class);
        confirmLogin = api.getMethod("confirmLogin", String.class, String.class, boolean.class);
        setEventSink = api.getMethod("setEventSink", Consumer.class);
        status = api.getMethod("status");
        // Additive capability: old MiniGameHub providers must keep the original key flow working.
        Method web;
        try {
            web = api.getMethod("claimWebLogin", String.class, String.class);
        } catch (NoSuchMethodException ignored) {
            web = null;
        }
        claimWebLogin = web;
        Method link;
        try {
            link = api.getMethod("requestPremiumLink", String.class, String.class, String.class);
        } catch (NoSuchMethodException ignored) {
            link = null;
        }
        requestPremiumLink = link;
    }

    static HubAuthKeys find() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals(API)) continue;
                Object p = Bukkit.getServicesManager().load(c);
                if (p != null) return new HubAuthKeys(c, p);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("status", "error");
    }

    Map<String, Object> claim(String discordId, String discordName) throws Exception { return map(call(claim, discordId, discordName)); }

    /** Trusted Gateway caller only. This never claims, looks up, or reuses a legacy binding key. */
    Map<String, Object> claimWebLogin(String discordId, String interactionId) throws Exception {
        return claimWebLogin == null ? Map.of("status", "unavailable") : map(call(claimWebLogin, discordId, interactionId));
    }

    /** 2026-10-11 account-link: shows the online 正版 player a confirm dialog. */
    Map<String, Object> requestPremiumLink(String discordId, String discordName, String name) throws Exception {
        return requestPremiumLink == null ? Map.of("status", "disabled") : map(call(requestPremiumLink, discordId, discordName, name));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> lookup(String query) throws Exception {
        Object o = call(lookup, query);
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    Map<String, Object> banDiscord(String id, String reason, String by, long until) throws Exception { return map(call(banDiscord, id, reason, by, until)); }
    Map<String, Object> unbanDiscord(String id, String by) throws Exception { return map(call(unbanDiscord, id, by)); }
    Map<String, Object> resolvePlayer(String name) throws Exception { return map(call(resolvePlayer, name)); }
    boolean isVerified(String uuid) throws Exception { return Boolean.TRUE.equals(call(isVerified, uuid)); }
    Map<String, Object> confirmLogin(String token, String discordId, boolean yes) throws Exception { return map(call(confirmLogin, token, discordId, yes)); }
    void setEventSink(Consumer<Map<String, Object>> sink) throws Exception { call(setEventSink, sink); }
    Map<String, Object> status() throws Exception { return map(call(status)); }
}
