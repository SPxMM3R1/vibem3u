package cl.streambox.tv.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cl.streambox.tv.Channel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * El auxiliar local (vista previa del editor) debe aceptar y rechazar las mismas filas de
 * proveedor que el editor, el runner y la app: contracts/layout-provider-rows.json.
 */
public final class LocalCatalogContractTest {
    private static final Path CONTRACT = Path.of("..", "app", "src", "test", "resources", "contracts",
            "layout-provider-rows.json");

    @Test public void helperAgreesWithEveryContractCase() throws Exception {
        JSONArray cases = new JSONObject(Files.readString(CONTRACT, StandardCharsets.UTF_8)).getJSONArray("cases");
        List<String> disagreements = new ArrayList<>();
        for (int index = 0; index < cases.length(); index++) {
            JSONObject item = cases.getJSONObject(index);
            String accepted = acceptedStableId(item.getJSONObject("row"));
            if (item.getBoolean("valid") && !item.getString("stableId").equals(accepted)) {
                disagreements.add(item.getString("id") + ": se esperaba " + item.getString("stableId")
                        + " y el auxiliar entregó " + (accepted == null ? "nada" : accepted));
            } else if (!item.getBoolean("valid") && accepted != null) {
                disagreements.add(item.getString("id") + ": el auxiliar aceptó una fila inválida (" + accepted + ")");
            }
        }
        assertTrue(String.join("\n", disagreements), disagreements.isEmpty());
        assertEquals(7, cases.length());
    }

    private static String acceptedStableId(JSONObject row) {
        try {
            Channel channel = LocalCatalogServer.providerChannelFromPayload(row);
            return channel.getAttributes().get("x-resolver-stable-id");
        } catch (RuntimeException rejected) {
            return null;
        }
    }
}
