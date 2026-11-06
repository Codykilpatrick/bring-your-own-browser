package poc.uiagent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Just enough JSON writing for OTLP/JSON. No dependencies, Java 6. */
public final class Json {
    private Json() { }

    public static String str(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** OTLP attribute list from string, long, double, or boolean values; nulls are skipped. */
    public static String attrs(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"key\":").append(str(e.getKey())).append(",\"value\":{");
            if (v instanceof Boolean) b.append("\"boolValue\":").append(v);
            else if (v instanceof Double || v instanceof Float) b.append("\"doubleValue\":").append(v);
            else if (v instanceof Number) b.append("\"intValue\":\"").append(((Number) v).longValue()).append('"');
            else b.append("\"stringValue\":").append(str(String.valueOf(v)));
            b.append("}}");
        }
        return b.append(']').toString();
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }
}
