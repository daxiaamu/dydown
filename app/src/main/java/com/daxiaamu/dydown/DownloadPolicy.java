package com.daxiaamu.dydown;

import java.util.List;

/** Mutates download fields only; keeps sharing, comments, duet and account privacy intact. */
public final class DownloadPolicy {
    public static boolean hasUrls(Object address) {
        Object value = ModelFields.get(address, "url_list");
        if (!(value instanceof List<?>)) return false;
        for (Object item : (List<?>) value) {
            if (item instanceof String && (((String) item).startsWith("https://") || ((String) item).startsWith("http://"))) return true;
        }
        return false;
    }
    public static boolean prepare(Object aweme) {
        Object video = ModelFields.get(aweme, "video");
        if (video == null) return false;
        // Images and live streams are left to the host's own handlers.
        Object type = ModelFields.get(aweme, "aweme_type");
        if (Integer.valueOf(68).equals(type) || Integer.valueOf(101).equals(type)) return false;
        Object play = ModelFields.get(video, "play_addr");
        if (!hasUrls(play)) play = ModelFields.get(video, "play_addr_h264");
        Object download = ModelFields.get(video, "download_addr");
        if (!hasUrls(download)) {
            if (!hasUrls(play)) return false;
            if (!ModelFields.set(video, "download_addr", play)) return false;
        }
        if (!hasUrls(ModelFields.get(video, "new_download_addr"))) {
            ModelFields.set(video, "new_download_addr", ModelFields.get(video, "download_addr"));
        }
        ModelFields.set(aweme, "prevent_download", false);
        Object control = ModelFields.get(aweme, "video_control");
        ModelFields.set(control, "allow_download", true);
        ModelFields.set(control, "prevent_download_type", 0);
        ModelFields.set(control, "download_ignore_visibility", true);
        // 40.5.0: level 1 = grayed, level 2 = hidden; 0 = normal.
        ModelFields.set(ModelFields.get(control, "download_info"), "level", 0);
        ModelFields.set(ModelFields.get(aweme, "status"), "download_status", 0);
        return true;
    }
    private DownloadPolicy() { }
}
