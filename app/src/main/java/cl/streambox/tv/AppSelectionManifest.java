package cl.streambox.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds the token-free selection document published by the Android app.
 *
 * <p>This is a declaration of provider identity and order, not a playlist of
 * playable URLs. It deliberately does not publish {@code tvg-id}, a logo
 * decision or an EPG mapping: those are canonical presentation decisions of
 * the Lista M3U runner. VibeM3U keeps the provider resource id so the runner
 * can identify the selected catalogue item without mistaking a rotating slug
 * for an XMLTV identity.</p>
 */
public final class AppSelectionManifest {
    public static final int SCHEMA_VERSION = 1;

    private AppSelectionManifest() {}

    public static Snapshot build(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        Context application = context.getApplicationContext();
        TvVooSelectionStore tvvoo = new TvVooSelectionStore(application);
        HighflySelectionStore highfly = new HighflySelectionStore(application);
        String highflyManifest = new ResolverPreferences(application).highflyManifestUrl();
        String signature = signature(tvvoo, highfly, highflyManifest);

        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("source", "vibem3u-android");
            root.put("selectionSignature", signature);
            root.put("publishedAt", nowUtc());

            JSONArray sources = new JSONArray();
            sources.put(source(
                    "tvvoo",
                    tvvoo.isEnabled(),
                    tvvoo.getSelectedCatalogChannels()
            ));
            JSONObject highflySource = source(
                    "highfly",
                    highfly.isEnabled(),
                    highfly.getSelectedCatalogChannels()
            );
            highflySource.put("manifestUrl", highflyManifest);
            sources.put(highflySource);
            root.put("sources", sources);
            return new Snapshot(signature, root.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("No se pudo construir la selección.", impossible);
        }
    }

    static String signature(
            TvVooSelectionStore tvvoo,
            HighflySelectionStore highfly,
            String highflyManifest
    ) {
        String value = "schema=" + SCHEMA_VERSION
                + "\ntvvoo=" + (tvvoo == null ? "" : tvvoo.signature())
                + "\nhighfly=" + (highfly == null ? "" : highfly.signature())
                + "\nmanifest=" + (highflyManifest == null ? "" : highflyManifest.trim());
        return sha256(value);
    }

    private static JSONObject source(
            String provider,
            boolean enabled,
            List<?> channels
    ) throws JSONException {
        JSONObject source = new JSONObject();
        source.put("provider", provider);
        source.put("enabled", enabled);
        JSONArray rows = new JSONArray();
        if (channels != null) {
            for (int index = 0; index < channels.size(); index++) {
                Object channel = channels.get(index);
                JSONObject row;
                if (channel instanceof TvVooCatalogChannel) {
                    row = tvvooRow((TvVooCatalogChannel) channel, index + 1);
                } else if (channel instanceof HighflyCatalogChannel) {
                    row = highflyRow((HighflyCatalogChannel) channel, index + 1);
                } else {
                    continue;
                }
                row.put("provider", provider);
                rows.put(row);
            }
        }
        source.put("channels", rows);
        return source;
    }

    static JSONObject tvvooRow(TvVooCatalogChannel value, int order) throws JSONException {
        if (value == null) throw new IllegalArgumentException("value");
        JSONObject row = new JSONObject();
        row.put("catalogKey", value.getStableId());
        row.put("providerResourceId", value.getStableId());
        row.put("alias", value.getAlias());
        row.put("country", value.getCountry());
        row.put("countryKey", value.getCountryKey());
        row.put("name", value.getName());
        row.put("group", value.getGroup());
        row.put("category", value.getCategory());
        JSONArray aliases = new JSONArray();
        for (String alias : value.getResolverAliases()) aliases.put(alias);
        // `aliases` is the current public contract. Keep the legacy spelling
        // while older runners are migrated.
        row.put("aliases", aliases);
        row.put("resolverAliases", aliases);
        row.put("identityState", "canonical");
        row.put("order", order);
        return row;
    }

    static JSONObject highflyRow(HighflyCatalogChannel value, int order) throws JSONException {
        if (value == null) throw new IllegalArgumentException("value");
        JSONObject row = new JSONObject();
        row.put("catalogKey", value.getStableId());
        row.put("providerResourceId", value.getResourceId());
        row.put("resolverSlug", value.getSlug());
        row.put("name", value.getName());
        row.put("group", value.getGroup());
        row.put("category", value.getCategory());
        row.put("identityState", value.getIdentityState());
        if (!value.getCountryKey().isEmpty()) {
            row.put("countryKey", value.getCountryKey());
        }
        row.put("order", order);
        return row;
    }

    private static String nowUtc() {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                Locale.ROOT
        );
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (Exception impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static final class Snapshot {
        private final String signature;
        private final String json;

        Snapshot(String signature, String json) {
            this.signature = signature == null ? "" : signature;
            this.json = json == null ? "{}" : json;
        }

        public String getSignature() { return signature; }
        public String getJson() { return json; }
    }
}
