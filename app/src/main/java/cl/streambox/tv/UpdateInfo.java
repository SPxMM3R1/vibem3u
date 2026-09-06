package cl.streambox.tv;

import java.net.URI;

final class UpdateInfo {
    private final String tagName;
    private final String versionName;
    private final URI downloadUri;
    private final long sizeBytes;

    UpdateInfo(String tagName, String versionName, URI downloadUri, long sizeBytes) {
        this.tagName = tagName;
        this.versionName = versionName;
        this.downloadUri = downloadUri;
        this.sizeBytes = sizeBytes;
    }

    static UpdateInfo fromCachedApk(String versionName) {
        String normalized = versionName == null ? "" : versionName.trim();
        String tagName = normalized.startsWith("v") ? normalized : "v" + normalized;
        URI downloadUri = URI.create(
                "https://github.com/SPxMM3R1/vibem3u/releases/download/"
                        + tagName
                        + "/VibeM3U-"
                        + tagName
                        + ".apk"
        );
        return new UpdateInfo(tagName, normalized, downloadUri, 0L);
    }

    String getTagName() {
        return tagName;
    }

    String getVersionName() {
        return versionName;
    }

    URI getDownloadUri() {
        return downloadUri;
    }

    long getSizeBytes() {
        return sizeBytes;
    }
}
