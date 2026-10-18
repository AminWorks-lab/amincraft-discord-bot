package cn.aminplay.discord;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-10-17 对局精彩回放：MiniGameHub 注册的 Bukkit 服务 cn.friendhub.replay.HighlightApi（只用 JDK 类型）。
 * 和 {@link HubFeedback} 一样按类名反射调用，本插件不需要拿 MiniGameHub 编译。
 * 服务不在（MiniGameHub 没装 / 太旧 / highlights.yml 没开）= {@link #find()} 返回 null。
 */
final class HubHighlights implements HighlightPoster.Hub {
    static final String API = "cn.friendhub.replay.HighlightApi";

    private final Object provider;
    private final Method setEventSink, hide;

    private HubHighlights(Class<?> api, Object provider) throws NoSuchMethodException {
        this.provider = provider;
        setEventSink = api.getMethod("setEventSink", Consumer.class);
        hide = api.getMethod("hide", String.class, List.class);
    }

    static HubHighlights find() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals(API)) continue;
                Object p = Bukkit.getServicesManager().load(c);
                if (p != null) return new HubHighlights(c, p);
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

    void setEventSink(Consumer<Map<String, Object>> sink) throws Exception { call(setEventSink, sink); }

    @Override
    public boolean hide(String highlightId, List<String> uuids) throws Exception { return Boolean.TRUE.equals(call(hide, highlightId, uuids)); }
}
