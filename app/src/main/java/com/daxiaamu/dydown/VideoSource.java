package com.daxiaamu.dydown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Snapshot only the selected item's addresses, in playback-first order. */
public final class VideoSource {
    public final String id;
    public final List<String> urls;
    private VideoSource(String id, List<String> urls) { this.id = id; this.urls = urls; }
    private static void add(LinkedHashSet<String> urls, Object address) {
        Object list = ModelFields.get(address, "url_list");
        if (!(list instanceof List<?>)) return;
        for (Object entry : (List<?>) list) {
            if (entry instanceof String && (((String) entry).startsWith("https://") || ((String) entry).startsWith("http://"))) urls.add((String) entry);
        }
    }
    public static VideoSource from(Object aweme) {
        Object type = ModelFields.get(aweme, "aweme_type");
        if (Integer.valueOf(68).equals(type) || Integer.valueOf(101).equals(type)) return null;
        Object video = ModelFields.get(aweme, "video");
        if (video == null) return null;
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        add(urls, ModelFields.get(video, "play_addr_h264"));
        add(urls, ModelFields.get(video, "play_addr"));
        add(urls, ModelFields.get(video, "download_addr"));
        add(urls, ModelFields.get(video, "new_download_addr"));
        if (urls.isEmpty()) return null;
        Object aid = ModelFields.get(aweme, "aweme_id");
        if (aid == null) aid = ModelFields.get(aweme, "aid");
        String id = aid == null ? Integer.toHexString(urls.hashCode()) : aid.toString().replaceAll("[^a-zA-Z0-9_-]", "");
        if (id.isEmpty()) id = Integer.toHexString(urls.hashCode());
        return new VideoSource(id, new ArrayList<>(urls));
    }
}
