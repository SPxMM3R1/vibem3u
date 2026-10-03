package cl.streambox.tv;

/** Página que la TV sirve al teléfono para vincular Premium (sin recursos externos). */
final class PremiumPairingPage {
    private PremiumPairingPage() {}

    private static final String HEAD = "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>VibeM3U · Premium</title><style>"
            + "html,body{margin:0;background:#0E1214;color:#E9F1F4;font-family:Roboto,system-ui,sans-serif}"
            + ".w{max-width:420px;margin:0 auto;padding:48px 24px}"
            + "h1{font-size:24px;margin:0 0 6px}p{color:#9FB0B7;font-size:15px;line-height:1.45;margin:0 0 26px}"
            + "label{display:block;font-size:13px;color:#9FB0B7;margin:0 0 8px;letter-spacing:.5px;text-transform:uppercase}"
            + "input{box-sizing:border-box;width:100%;background:#1B2124;border:0;border-radius:12px;padding:15px 16px;"
            + "font-size:16px;color:#fff;margin-bottom:22px;font-family:ui-monospace,Consolas,monospace}"
            + "input.code{font-size:28px;letter-spacing:18px;text-align:center}"
            + "button{width:100%;background:#00B8E6;color:#021015;font-weight:600;border:0;border-radius:999px;"
            + "padding:15px;font-size:17px}"
            + ".n{font-size:13px;color:#9FB0B7;margin-top:18px;text-align:center}"
            + ".e{background:#3a1d1d;color:#ffb4b4;border-radius:12px;padding:12px 14px;font-size:14px;margin:0 0 20px}"
            + ".ok{font-size:44px;margin:0 0 10px}"
            + "</style></head><body><div class=\"w\">";
    private static final String TAIL = "</div></body></html>";

    static String form(String error) {
        return HEAD
                + "<h1>VibeM3U · Premium</h1>"
                + "<p>Esta página la sirve tu TV. El token queda solo en ella.</p>"
                + (error == null ? "" : "<div class=\"e\">" + escape(error) + "</div>")
                + "<form method=\"post\" action=\"/pair\" autocomplete=\"off\">"
                + "<label for=\"t\">Token o enlace de premium.highfly.to</label>"
                + "<input id=\"t\" name=\"token\" required maxlength=\"600\" spellcheck=\"false\""
                + " autocapitalize=\"off\" placeholder=\"Pega aquí\">"
                + "<label for=\"c\">Código que muestra la TV</label>"
                + "<input id=\"c\" class=\"code\" name=\"code\" required inputmode=\"numeric\""
                + " pattern=\"[0-9]{4}\" maxlength=\"4\" placeholder=\"····\">"
                + "<button type=\"submit\">Enviar a la TV</button></form>"
                + "<div class=\"n\">Si el código no coincide, la TV no acepta el token.</div>"
                + TAIL;
    }

    static String message(String text, boolean ok) {
        return HEAD
                + (ok ? "<div class=\"ok\">✓</div>" : "")
                + "<h1>" + (ok ? "Listo" : "VibeM3U · Premium") + "</h1>"
                + "<p>" + escape(text) + "</p>"
                + TAIL;
    }

    static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '&': out.append("&amp;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(character);
            }
        }
        return out.toString();
    }
}
