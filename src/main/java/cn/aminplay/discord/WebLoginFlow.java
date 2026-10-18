package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Existing Gateway INTERACTION_CREATE -> server-authoritative WEB_LOGIN code.
 * No HTTP ingress, target-user option, key lookup, account mutation, or DM/public fallback.
 * The authority owns readiness, binding/ban checks, replay/rate limits, expiry and one-use redemption.
 * Do not log exceptions here: a provider or Discord response may echo a secret payload.
 */
final class WebLoginFlow {
    static final String COMMAND = "weblogin";
    private static final String UNAVAILABLE = "网页登录暂时不可用，请稍后再试。";
    private static final String REJECTED = "无法领取网页登录码，请在指定 Discord 服务器中用自己的账号重新使用这个指令。";

    interface Host {
        BotConfig cfg();
        String applicationId();
        DiscordRest rest();
        Map<String, Object> claim(String discordId, String interactionId) throws Exception;
    }

    private final Host host;

    WebLoginFlow(Host host) {
        this.host = host;
    }

    /** Route even malformed new-command requests away from the legacy exception logger. */
    static boolean isCommand(JsonObject event) {
        JsonObject data = object(event, "data");
        if (COMMAND.equals(string(data, "name"))) return true;
        if (!Interactions.ROOT.equals(string(data, "name"))) return false;
        JsonArray options = array(data, "options");
        if (options == null) return false;
        for (JsonElement option : options) {
            if (option.isJsonObject() && COMMAND.equals(string(option.getAsJsonObject(), "name"))) return true;
        }
        return false;
    }

    void handle(JsonObject event) {
        try {
            BotConfig cfg = host.cfg();
            String app = host.applicationId();
            String interactionId = string(event, "id");
            String token = string(event, "token");
            // No untrusted strings can alter REST paths, and no unverified application can issue a code.
            if (!snowflake(app) || !app.equals(string(event, "application_id"))
                    || !snowflake(interactionId) || token == null || !token.matches("[A-Za-z0-9._-]{1,512}")
                    || !integer(event, "type", 2)) return;
            DiscordRest rest = host.rest();
            if (rest == null) return;

            JsonObject deferred = new JsonObject();
            deferred.addProperty("flags", 64);
            JsonObject callback = new JsonObject();
            callback.addProperty("type", 5);
            callback.add("data", deferred);
            // Failure/uncertainty ends here, before requesting any credential from the authority.
            rest.post("/interactions/" + interactionId + "/" + token + "/callback", callback);

            String text;
            if (!cfg.webLoginEnabled) text = "网页登录码功能尚未开启。";
            else {
                String actor = actor(event, cfg.guildId);
                if (actor == null) text = REJECTED;
                else {
                    Map<String, Object> result;
                    try {
                        result = host.claim(actor, interactionId);
                    } catch (Exception | LinkageError ignored) {
                        result = Map.of("status", "unavailable");
                    }
                    text = reply(result);
                }
            }
            JsonObject message = new JsonObject();
            message.addProperty("content", text);
            message.addProperty("flags", 64);
            message.add("allowed_mentions", Interactions.noMentions());
            // Edit only the acknowledged ephemeral original. Never send DM/channel/webhook follow-ups.
            rest.patch("/webhooks/" + app + "/" + token + "/messages/@original", message);
        } catch (Exception | LinkageError ignored) {
            // No retry via a less-private transport, no exception text, no payload/token/code logging.
            // A successfully issued but undelivered code expires at the authority within 120 seconds.
        }
    }

    /** Only member.user.id in the Gateway envelope represents the guild interaction's caller. */
    static String actor(JsonObject event, String expectedGuild) {
        if (!snowflake(expectedGuild) || !expectedGuild.equals(string(event, "guild_id"))) return null;
        JsonObject data = object(event, "data");
        if (!Interactions.ROOT.equals(string(data, "name"))) return null;
        JsonArray options = array(data, "options");
        if (options == null || options.size() != 1 || !options.get(0).isJsonObject()) return null;
        JsonObject command = options.get(0).getAsJsonObject();
        if (!integer(command, "type", 1) || !COMMAND.equals(string(command, "name"))) return null;
        if (command.has("options")) {
            JsonArray arguments = array(command, "options");
            if (arguments == null || !arguments.isEmpty()) return null;
        }
        JsonObject user = object(object(event, "member"), "user");
        String id = string(user, "id");
        if (!snowflake(id) || !falseOrAbsent(user, "bot") || !falseOrAbsent(user, "system")) return null;
        return id;
    }

    static String reply(Map<String, Object> result) {
        if (result == null) return UNAVAILABLE;
        Object status = result.get("status");
        if (!(status instanceof String value)) return UNAVAILABLE;
        return switch (value) {
            case "ok" -> {
                Object code = result.get("code"), expiry = result.get("expiresInSeconds");
                if (!(code instanceof String secret) || !secret.matches("[A-Za-z0-9_-]{43}")
                        || !(expiry instanceof Number seconds) || seconds.longValue() < 1 || seconds.longValue() > 120
                        || seconds.doubleValue() != seconds.longValue()) yield UNAVAILABLE;
                yield "🔐 你的网页一次性登录码：`" + secret + "`\n"
                        + "请在 Amincraft 经济网页的 Discord 登录框中粘贴；" + seconds.longValue() + " 秒内有效，只能使用一次。\n"
                        + "它只用于网页登录，不是游戏绑定密钥。请勿转发或把它填入网址。已有游戏账号无需重新绑定。";
            }
            case "disabled" -> "网页登录码功能尚未开启。";
            case "unbound" -> "你的 Discord 账号尚未绑定游戏账号。请先完成原有的游戏账号绑定流程；本指令不会创建或更换绑定。";
            case "rate-limited" -> "领取过于频繁，请稍后再试。";
            case "rejected" -> "这次网页登录码请求已失效或未通过校验，请稍后重新使用指令。";
            default -> UNAVAILABLE;
        };
    }

    private static boolean snowflake(String value) {
        if (value == null || !value.matches("[1-9][0-9]{16,19}")) return false;
        try {
            Long.parseUnsignedLong(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean falseOrAbsent(JsonObject object, String name) {
        if (object == null) return false;
        if (!object.has(name)) return true;
        JsonElement element = object.get(name);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && !element.getAsBoolean();
    }

    private static boolean integer(JsonObject object, String name, int value) {
        if (object == null || !object.has(name)) return false;
        JsonElement element = object.get(name);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() && element.getAsString().equals(Integer.toString(value));
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
    }

    private static JsonObject object(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) return null;
        return object.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) return null;
        return object.getAsJsonArray(name);
    }
}
