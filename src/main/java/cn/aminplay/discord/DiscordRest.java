package cn.aminplay.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Discord REST API v10 的最小封装：带 Bot 鉴权，自动处理 429 限速。 */
final class DiscordRest {
    /** 测试时可用 -Daminplay.discord.api 指向本地假服务器。 */
    static final String API = System.getProperty("aminplay.discord.api", "https://discord.com/api/v10");

    private final HttpClient http;
    private final String token;
    private final String api;   // 2026-10-07 daily replay bundle: per-instance base (tests use a local fake Discord)

    DiscordRest(HttpClient http, String token) {
        this(http, token, API);
    }

    DiscordRest(HttpClient http, String token, String api) {
        this.http = http;
        this.token = token;
        this.api = api;
    }

    JsonElement get(String path) throws IOException { return request("GET", path, null); }
    JsonElement post(String path, JsonElement body) throws IOException { return request("POST", path, body); }
    JsonElement put(String path, JsonElement body) throws IOException { return request("PUT", path, body); }
    JsonElement patch(String path, JsonElement body) throws IOException { return request("PATCH", path, body); }
    JsonElement delete(String path) throws IOException { return request("DELETE", path, null); }

    JsonElement request(String method, String path, JsonElement body) throws IOException {
        if (body == null) return send(method, path, HttpRequest.BodyPublishers::noBody, null, Duration.ofSeconds(10));
        String text = body.toString();
        return send(method, path, () -> HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8), "application/json", Duration.ofSeconds(10));
    }

    /**
     * 2026-10-07 daily replay bundle: a message with file attachments (multipart/form-data: payload_json + files[n]).
     * payload gets attachments[] with the file names (UTF-8 names such as Amincraft-回放-2026-10-07.zip).
     */
    JsonElement postFiles(String path, JsonObject payload, List<Path> files) throws IOException {
        return postFiles(path, payload, files, null);
    }

    /**
     * 2026-10-12 bot-posting: attachmentsIn = name of the object inside payload that gets attachments[] (forum thread creation:
     * "message", the starter message); null = top level as before.
     */
    JsonElement postFiles(String path, JsonObject payload, List<Path> files, String attachmentsIn) throws IOException {
        com.google.gson.JsonArray att = new com.google.gson.JsonArray();
        for (int i = 0; i < files.size(); i++) {
            JsonObject a = new JsonObject();
            a.addProperty("id", i);
            a.addProperty("filename", files.get(i).getFileName().toString());
            att.add(a);
        }
        JsonObject p = payload.deepCopy();
        if (attachmentsIn == null) p.add("attachments", att);
        else p.getAsJsonObject(attachmentsIn).add("attachments", att);
        String boundary = "AmincraftBoundary" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(p.toString().getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < files.size(); i++) {
            String name = files.get(i).getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
            out.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[" + i + "]\"; filename=\"" + name + "\"\r\nContent-Type: " + contentType(name) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(files.get(i)));
        }
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] body = out.toByteArray();
        return send("POST", path, () -> HttpRequest.BodyPublishers.ofByteArray(body), "multipart/form-data; boundary=" + boundary, Duration.ofMinutes(3));
    }

    /** 2026-10-12 bot-posting: moved from Interactions — one message's upload limit by the guild's boost tier, with some headroom. */
    long uploadLimit(String guild) {
        int tier = 0;
        try {
            JsonObject g = get("/guilds/" + guild).getAsJsonObject();
            if (g.has("premium_tier")) tier = g.get("premium_tier").getAsInt();
        } catch (IOException | RuntimeException ignored) {
        }
        return uploadLimitForTier(tier);
    }

    static long uploadLimitForTier(int tier) {
        long mb = tier >= 3 ? 100 : tier == 2 ? 50 : 10;
        return mb * 1024 * 1024 - 256 * 1024;
    }

    /** 2026-10-07 message collect：按扩展名给 Content-Type（图片能在 Discord 里直接预览）。 */
    private static String contentType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json";
        String t = java.net.URLConnection.guessContentTypeFromName(name);
        return t == null ? "application/octet-stream" : t;
    }

    private JsonElement send(String method, String path, Supplier<HttpRequest.BodyPublisher> body, String contentType, Duration timeout) throws IOException {
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path))
                    .timeout(timeout)
                    .header("Authorization", "Bot " + token)
                    .header("User-Agent", "DiscordBot (https://github.com/aminplay, 1.0)");
            if (contentType != null) b.header("Content-Type", contentType);
            b.method(method, body.get());
            HttpResponse<String> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (r.statusCode() == 429) {
                double wait = 1;
                try {
                    wait = JsonParser.parseString(r.body()).getAsJsonObject().get("retry_after").getAsDouble();
                } catch (RuntimeException ignored) {
                }
                sleep((long) (wait * 1000) + 100);
                continue;
            }
            if (r.statusCode() >= 400) throw new DiscordException(r.statusCode(), r.body());
            String s = r.body();
            return s == null || s.isEmpty() ? JsonNull.INSTANCE : JsonParser.parseString(s);
        }
        throw new DiscordException(429, "{\"message\":\"rate limited\"}");
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(Math.min(ms, 30_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    static final class DiscordException extends IOException {
        final int status;
        final int code;

        DiscordException(int status, String body) {
            super("HTTP " + status + " " + body);
            this.status = status;
            int c = 0;
            try {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                if (o.has("code")) c = o.get("code").getAsInt();
            } catch (RuntimeException ignored) {
            }
            this.code = c;
        }

        /** 给 Discord 里的管理员看的中文说明。 */
        String friendly() {
            return switch (code) {
                case 50013 -> "机器人权限不足。请确认机器人有「管理身份组」「发送消息」「嵌入链接」权限，并且机器人的身份组排在要发放的身份组**上面**。";
                case 50001 -> "机器人看不到这个频道（缺少访问权限）。";
                case 10003 -> "找不到这个频道。";
                case 30005 -> "Discord 服务器的身份组已经满了（上限 250），临时身份组建不出来。请删掉一个不用的身份组。";
                case 40067 -> "这个论坛要求发贴必须选标签，请在 /公告 的「标签」里选一个。";
                case 10011 -> "找不到这个身份组，可能已被删除。请在 config.yml 里清空它的 id 后重新发布面板。";
                default -> "Discord 返回错误：" + getMessage();
            };
        }
    }
}
