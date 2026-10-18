package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;

/**
 * 2026-10-12 bot-promo：定时广告的设置（plugins/AminPlayDiscord/promo-schedules.json），不依赖 Bukkit。
 * 只存频道 id、时间规则、自定义文字、谁设置的、上次发到哪个时间点；不存任何频道消息内容。
 */
final class PromoStore {
    static final int MAX = 50;

    /** 一个频道 / 子区的定时广告。disabled 非空 = 因为发不出去（频道没了 / 没权限）已自动停用。 */
    record Entry(String channelId, PromoRules.Rule rule, String text, String setBy, long lastSlot, long lastPostedAt, String disabled) {
        Entry withPosted(long slot, long at) {
            return new Entry(channelId, rule, text, setBy, Math.max(lastSlot, slot), at, disabled);
        }

        Entry withSlot(long slot) {
            return new Entry(channelId, rule, text, setBy, Math.max(lastSlot, slot), lastPostedAt, disabled);
        }

        Entry withDisabled(String why) {
            return new Entry(channelId, rule, text, setBy, lastSlot, lastPostedAt, why);
        }

        boolean active() {
            return disabled == null || disabled.isEmpty();
        }
    }

    private final File file;
    private final Logger log;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    PromoStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        if (file == null || !file.exists()) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("schedules")) {
                try {
                    Entry e = read(el.getAsJsonObject());
                    entries.put(e.channelId(), e);
                } catch (RuntimeException bad) {
                    if (log != null) log.warning("定时广告：跳过一条看不懂的设置：" + bad.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            if (log != null) log.warning("读取定时广告设置失败（" + file.getName() + "）：" + e.getMessage());
        }
    }

    synchronized Entry get(String channelId) {
        return entries.get(channelId);
    }

    synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    /** 新建或替换；超过上限返回 false。 */
    synchronized boolean put(Entry e) {
        if (!entries.containsKey(e.channelId()) && entries.size() >= MAX) return false;
        entries.put(e.channelId(), e);
        save();
        return true;
    }

    synchronized boolean remove(String channelId) {
        if (entries.remove(channelId) == null) return false;
        save();
        return true;
    }

    /** 只在设置还在时更新（发送途中被 关闭 / 重新设置 时不覆盖新的设置）。 */
    synchronized void update(Entry before, Entry after) {
        Entry cur = entries.get(before.channelId());
        if (cur == null || !cur.rule().equals(before.rule()) || !java.util.Objects.equals(cur.text(), before.text())) {
            // 规则变了：只把「发过」的时间记下来，用于防刷屏
            if (cur != null && after.lastPostedAt() > cur.lastPostedAt()) {
                entries.put(cur.channelId(), new Entry(cur.channelId(), cur.rule(), cur.text(), cur.setBy(), cur.lastSlot(), after.lastPostedAt(), cur.disabled()));
                save();
            }
            return;
        }
        entries.put(after.channelId(), after);
        save();
    }

    /** 预览也算一次发出（防止预览后马上又定时发一条）。 */
    synchronized void markPostedNow(String channelId, long at) {
        Entry cur = entries.get(channelId);
        if (cur == null) return;
        entries.put(channelId, new Entry(cur.channelId(), cur.rule(), cur.text(), cur.setBy(), cur.lastSlot(), at, cur.disabled()));
        save();
    }

    private void save() {
        if (file == null) return;
        JsonArray arr = new JsonArray();
        for (Entry e : entries.values()) arr.add(write(e));
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("schedules", arr);
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) Files.createDirectories(dir.toPath());
            File tmp = new File(dir, file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (log != null) log.warning("保存定时广告设置失败：" + e.getMessage());
        }
    }

    private static JsonObject write(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("channel", e.channelId());
        if (!e.rule().times().isEmpty()) {
            JsonArray t = new JsonArray();
            for (LocalTime x : e.rule().times()) t.add(PromoRules.fmt(x));
            o.add("times", t);
        } else {
            o.addProperty("every-hours", e.rule().everyHours());
        }
        if (e.text() != null) o.addProperty("text", e.text());
        if (e.setBy() != null) o.addProperty("set-by", e.setBy());
        o.addProperty("last-slot", e.lastSlot());
        o.addProperty("last-posted-at", e.lastPostedAt());
        if (!e.active()) o.addProperty("disabled", e.disabled());
        return o;
    }

    private static Entry read(JsonObject o) {
        String ch = o.get("channel").getAsString().replaceAll("[^0-9]", "");
        if (ch.isEmpty()) throw new IllegalArgumentException("没有频道 id");
        PromoRules.Rule rule;
        if (o.has("times")) {
            List<String> parts = new ArrayList<>();
            for (JsonElement t : o.getAsJsonArray("times")) parts.add(t.getAsString());
            rule = PromoRules.Rule.daily(PromoRules.parseTimes(String.join(",", parts)));
        } else {
            rule = PromoRules.Rule.every(o.get("every-hours").getAsInt());
        }
        String text = o.has("text") ? PromoRules.cleanText(o.get("text").getAsString()) : null;
        String by = o.has("set-by") ? o.get("set-by").getAsString() : null;
        long slot = o.has("last-slot") ? o.get("last-slot").getAsLong() : 0;
        long at = o.has("last-posted-at") ? o.get("last-posted-at").getAsLong() : 0;
        String dis = o.has("disabled") ? o.get("disabled").getAsString() : null;
        return new Entry(ch, rule, text, by, slot, at, dis);
    }
}
