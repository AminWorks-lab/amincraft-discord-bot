package cn.aminplay.discord;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-10-17 游戏内一键反馈：MiniGameHub 注册的 Bukkit 服务 cn.friendhub.feedback.FeedbackApi（只用 JDK 类型）。
 * 和 {@link HubAuthKeys}、{@link HubCity} 一样按类名反射调用，本插件不需要拿 MiniGameHub 编译。
 * 服务不在（MiniGameHub 没装 / 太旧 / feedback.yml 没开）= {@link #find()} 返回 null。
 */
final class HubFeedback {
    static final String API = "cn.friendhub.feedback.FeedbackApi";

    private final Object provider;
    private final Method setEventSink, status;

    private HubFeedback(Class<?> api, Object provider) throws NoSuchMethodException {
        this.provider = provider;
        setEventSink = api.getMethod("setEventSink", Consumer.class);
        Method st;
        try {
            st = api.getMethod("status");
        } catch (NoSuchMethodException e) {
            st = null;
        }
        status = st;
    }

    static HubFeedback find() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals(API)) continue;
                Object p = Bukkit.getServicesManager().load(c);
                if (p != null) return new HubFeedback(c, p);
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

    @SuppressWarnings("unchecked")
    Map<String, Object> status() throws Exception {
        if (status == null) return Map.of();
        Object o = call(status);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
