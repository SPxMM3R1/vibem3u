package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Contrato compartido de filas de proveedor. La fuente es Lista M3U
 * (contracts/layout-provider-rows.json); esta es una copia idéntica que Lista M3U verifica.
 * Los mismos casos los validan el editor (JS) y el runner (Python): una fila válida debe
 * llegar a la app como canal con esa identidad y una inválida no debe reproducirse.
 */
public final class LayoutContractTest {
    @Test
    public void appAgreesWithEveryContractCase() throws Exception {
        JSONObject contract = new JSONObject(readResource("contracts/layout-provider-rows.json"));
        JSONArray cases = contract.getJSONArray("cases");
        List<String> disagreements = new ArrayList<>();
        for (int index = 0; index < cases.length(); index++) {
            JSONObject item = cases.getJSONObject(index);
            boolean expectedValid = item.getBoolean("valid");
            String accepted = acceptedStableId(item.getJSONObject("row"));
            if (expectedValid && !item.getString("stableId").equals(accepted)) {
                disagreements.add(item.getString("id") + ": se esperaba " + item.getString("stableId")
                        + " y la app entregó " + (accepted == null ? "nada" : accepted));
            } else if (!expectedValid && accepted != null) {
                disagreements.add(item.getString("id") + ": la app aceptó una fila inválida (" + accepted + ")");
            }
        }
        assertTrue(String.join("\n", disagreements), disagreements.isEmpty());
        assertEquals(7, cases.length());
    }

    /** Identidad del canal que la app reproduciría, o null si la fila no se reproduce. */
    private static String acceptedStableId(JSONObject row) {
        String document = "{\"schemaVersion\":1,\"channels\":[" + row + "]}";
        try {
            PublishedPlaybackCatalog catalog = PublishedPlaybackCatalog.parse(document);
            List<Channel> channels = catalog.activeProviderChannels();
            if (channels.size() != 1) return null;
            return channels.get(0).getAttributes().get("x-resolver-stable-id");
        } catch (IOException rejected) {
            return null;
        }
    }

    private static String readResource(String name) throws IOException {
        try (InputStream input = LayoutContractTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("Falta el recurso de contrato " + name, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
