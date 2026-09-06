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

    private static final class ProgrammeHandler extends DefaultHandler {
        private final List<EpgProgramme> programmes = new ArrayList<>();
        private String channelId;
        private long startMillis;
        private long stopMillis;
        private String title;
        private StringBuilder text;
        private boolean readingTitle;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("programme".equalsIgnoreCase(qName)) {
                channelId = attributes.getValue("channel");
                startMillis = parseXmlTvTime(attributes.getValue("start"));
                stopMillis = parseXmlTvTime(attributes.getValue("stop"));
                title = null;
            } else if (channelId != null && "title".equalsIgnoreCase(qName) && title == null) {
                readingTitle = true;
                text = new StringBuilder();
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            if (readingTitle) text.append(chars, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("title".equalsIgnoreCase(qName) && readingTitle) {
                String candidate = text.toString().trim();
                if (!candidate.isEmpty()) title = candidate;
                readingTitle = false;
                text = null;
            } else if ("programme".equalsIgnoreCase(qName)) {
                if (channelId != null && !AppStrings.isBlank(channelId) && title != null
                        && startMillis >= 0 && stopMillis > startMillis) {
                    programmes.add(new EpgProgramme(channelId, title, startMillis, stopMillis));
                }
                channelId = null;
                title = null;
                readingTitle = false;
                text = null;
            }
        }
    }
}
