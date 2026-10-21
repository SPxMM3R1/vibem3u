package cl.streambox.tv;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

public final class EpgParser {
    private static final String[] DATE_PATTERNS = {
            "yyyyMMddHHmmss Z",
            "yyyyMMddHHmm Z",
            "yyyyMMddHHmmss",
            "yyyyMMddHHmm"
    };

    private EpgParser() {}

    public static EpgData parse(InputStream input) throws IOException {
        ProgrammeHandler handler = new ProgrammeHandler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            setFeatureIfSupported(reader, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureIfSupported(reader, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureIfSupported(reader, "http://xml.org/sax/features/external-parameter-entities", false);
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            reader.setContentHandler(handler);
            reader.parse(new InputSource(input));
            return new EpgData(handler.programmes);
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IOException("La programación XMLTV no es válida.", ex);
        }
    }

    private static void setFeatureIfSupported(XMLReader reader, String feature, boolean value) {
        try {
            reader.setFeature(feature, value);
        } catch (SAXException ignored) {
            // Algunas versiones antiguas de Android no exponen todas las opciones SAX.
        }
    }

    static long parseXmlTvTime(String value) {
        if (value == null) return -1;
        // 0.6.26: camino rápido para «yyyyMMddHHmmss ±HHMM» (o ±HH:MM), el formato de
        // todas las guías publicadas. Antes cada fecha creaba varios SimpleDateFormat y una
        // expresión regular: ~140 mil fechas por carga de Lista 2.
        long fast = parseFast(value.trim());
        if (fast != Long.MIN_VALUE) return fast;
        String normalized = value.trim().replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2");
        for (String pattern : DATE_PATTERNS) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date date = format.parse(normalized, position);
            if (date != null && position.getIndex() == normalized.length()) {
                return date.getTime();
            }
        }
        return -1;
    }

    /** Long.MIN_VALUE si no es el formato habitual con zona horaria. */
    static long parseFast(String value) {
        int length = value.length();
        int digits = 0;
        while (digits < length && digits < 14 && Character.isDigit(value.charAt(digits))) digits++;
        if (digits != 14 && digits != 12) return Long.MIN_VALUE;
        int position = digits;
        while (position < length && value.charAt(position) == ' ') position++;
        if (position >= length) return Long.MIN_VALUE;
        char sign = value.charAt(position);
        if (sign != '+' && sign != '-') return Long.MIN_VALUE;
        String zone = value.substring(position + 1).replace(":", "");
        if (zone.length() != 4) return Long.MIN_VALUE;
        for (int index = 0; index < 4; index++) {
            if (!Character.isDigit(zone.charAt(index))) return Long.MIN_VALUE;
        }
        int year = number(value, 0, 4);
        int month = number(value, 4, 2);
        int day = number(value, 6, 2);
        int hour = number(value, 8, 2);
        int minute = number(value, 10, 2);
        int second = digits == 14 ? number(value, 12, 2) : 0;
        int zoneHours = number(zone, 0, 2);
        int zoneMinutes = number(zone, 2, 2);
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59
                || second > 59 || zoneMinutes > 59) {
            return Long.MIN_VALUE;
        }
        long days = daysFromCivil(year, month, day);
        long offsetSeconds = (zoneHours * 3600L + zoneMinutes * 60L) * (sign == '-' ? -1 : 1);
        return ((days * 86400L) + hour * 3600L + minute * 60L + second - offsetSeconds) * 1000L;
    }

    private static int number(String value, int start, int count) {
        int result = 0;
        for (int index = start; index < start + count; index++) {
            result = result * 10 + (value.charAt(index) - '0');
        }
        return result;
    }

    /** Días desde 1970-01-01 (algoritmo de H. Hinnant, calendario gregoriano proléptico). */
    private static long daysFromCivil(int year, int month, int day) {
        year -= month <= 2 ? 1 : 0;
        long era = (year >= 0 ? year : year - 399) / 400;
        long yearOfEra = year - era * 400;
        long dayOfYear = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146097 + dayOfEra - 719468;
    }

    private static final class ProgrammeHandler extends DefaultHandler {
        private final List<EpgProgramme> programmes = new ArrayList<>();
        /** 0.6.26: una sola copia de cada canal, título y sinopsis repetidos. */
        private final java.util.HashMap<String, String> pool = new java.util.HashMap<>();
        private String channelId;
        private long startMillis;
        private long stopMillis;
        private String title;
        private String description;
        private StringBuilder text;
        private boolean readingTitle;
        private boolean readingDescription;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("programme".equalsIgnoreCase(qName)) {
                channelId = attributes.getValue("channel");
                startMillis = parseXmlTvTime(attributes.getValue("start"));
                stopMillis = parseXmlTvTime(attributes.getValue("stop"));
                title = null;
                description = null;
            } else if (channelId != null && "title".equalsIgnoreCase(qName) && title == null) {
                readingTitle = true;
                text = new StringBuilder();
            } else if (channelId != null && "desc".equalsIgnoreCase(qName)
                    && description == null && !readingTitle) {
                readingDescription = true;
                text = new StringBuilder();
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            if (readingTitle) {
                text.append(chars, start, length);
            } else if (readingDescription
                    && text.length() <= EpgProgramme.MAX_DESCRIPTION_CHARS * 2) {
                text.append(chars, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("title".equalsIgnoreCase(qName) && readingTitle) {
                String candidate = text.toString().trim();
                if (!candidate.isEmpty()) title = candidate;
                readingTitle = false;
                text = null;
            } else if ("desc".equalsIgnoreCase(qName) && readingDescription) {
                String candidate = text.toString().trim();
                if (!candidate.isEmpty()) description = candidate;
                readingDescription = false;
                text = null;
            } else if ("programme".equalsIgnoreCase(qName)) {
                if (channelId != null && !AppStrings.isBlank(channelId) && title != null
                        && startMillis >= 0 && stopMillis > startMillis) {
                    programmes.add(new EpgProgramme(
                            EpgSnapshotCache.pooled(pool, channelId),
                            EpgSnapshotCache.pooled(pool, title),
                            startMillis,
                            stopMillis,
                            EpgSnapshotCache.pooled(pool, description)));
                }
                channelId = null;
                title = null;
                description = null;
                readingTitle = false;
                readingDescription = false;
                text = null;
            }
        }
    }
}
