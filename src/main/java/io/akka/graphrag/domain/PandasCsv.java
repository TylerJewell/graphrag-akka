package io.akka.graphrag.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Renders records the way {@code pandas.DataFrame(records).to_csv(index=False, sep=",")} does —
 * SPEC-001 §3 rules 13-16.
 *
 * <p>Every context string in this port is a CSV block, and every trim boundary and rollup
 * substitution is a token count of one. A rendering that differs by a character moves those
 * counts, so this is byte-for-byte rather than approximately right.
 *
 * <p>Two properties are easy to miss and are the reason this is not a three-line writer.
 * Rendering is decided per column, not per value: one fractional or missing entry anywhere in a
 * column makes every integer in it render with a {@code .0} suffix. And numbers use Python's
 * {@code repr}, whose switch to exponent form happens at different magnitudes than
 * {@link Double#toString}'s.
 */
public final class PandasCsv {

    private PandasCsv() {}

    private enum Kind { INT, FLOAT, BOOL, OBJECT }

    /** Renders records as CSV with a header row, CRLF terminators and no index column. */
    public static String toCsv(List<Map<String, Object>> records) {
        if (records.isEmpty()) {
            return "\r\n";
        }
        List<String> columns = new ArrayList<>(new LinkedHashSet<>(
                records.stream().flatMap(r -> r.keySet().stream()).toList()));

        StringBuilder out = new StringBuilder();
        out.append(String.join(",", columns.stream().map(PandasCsv::field).toList()));
        out.append("\r\n");

        List<Kind> kinds = columns.stream().map(c -> kindOf(records, c)).toList();
        for (Map<String, Object> record : records) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(field(render(record.get(columns.get(i)), kinds.get(i))));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    /**
     * The dtype pandas would infer for one column. A key absent from a record and a key present
     * with a null value are the same thing here: both become NaN, which floats an integer column
     * and leaves an object column alone.
     */
    private static Kind kindOf(List<Map<String, Object>> records, String column) {
        boolean missing = false;
        boolean fractional = false;
        boolean bool = false;
        boolean integral = false;
        for (Map<String, Object> record : records) {
            Object value = record.get(column);
            if (value == null) {
                missing = true;
            } else if (value instanceof Double || value instanceof Float) {
                fractional = true;
            } else if (value instanceof Number) {
                integral = true;
            } else if (value instanceof Boolean) {
                bool = true;
            } else {
                return Kind.OBJECT;
            }
        }
        if (bool) {
            return Kind.BOOL;
        }
        if (!integral && !fractional) {
            return Kind.OBJECT;
        }
        return (fractional || missing) ? Kind.FLOAT : Kind.INT;
    }

    private static String render(Object value, Kind kind) {
        if (value == null) {
            return "";
        }
        return switch (kind) {
            case INT -> Long.toString(((Number) value).longValue());
            // to_csv writes a missing value as an empty field, and a NaN is one.
            case FLOAT -> {
                double d = ((Number) value).doubleValue();
                yield Double.isNaN(d) ? "" : pythonRepr(d);
            }
            case BOOL -> ((Boolean) value) ? "True" : "False";
            case OBJECT -> value.toString();
        };
    }

    /** Minimal quoting: only a comma, a double quote, CR or LF forces quotes. */
    private static String field(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * Python's {@code repr} of a float. Same shortest round-trip digits as
     * {@link Double#toString}, but the point at which it switches to exponent form differs:
     * Python uses fixed notation while the decimal point sits in {@code (-4, 16]}, the JVM
     * while the magnitude sits in {@code [1e-3, 1e7)}.
     */
    public static String pythonRepr(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String java = Double.toString(Math.abs(value));
        String sign = (value < 0 || (value == 0.0 && 1 / value < 0)) ? "-" : "";

        int exponent = 0;
        int e = java.indexOf('E');
        if (e >= 0) {
            exponent = Integer.parseInt(java.substring(e + 1));
            java = java.substring(0, e);
        }
        int dot = java.indexOf('.');
        String digits = java.substring(0, dot) + java.substring(dot + 1);
        // decpt is the decimal point's position in `digits`, so value = 0.digits * 10^decpt.
        int decpt = dot + exponent;

        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') {
            lead++;
            decpt--;
        }
        digits = digits.substring(lead);
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        if (digits.equals("0")) {
            return sign + "0.0";
        }

        if (decpt <= -4 || decpt > 16) {
            String mantissa = digits.length() == 1
                    ? digits
                    : digits.charAt(0) + "." + digits.substring(1);
            int exp = decpt - 1;
            return sign + mantissa + "e" + (exp < 0 ? "-" : "+")
                    + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        }
        if (decpt <= 0) {
            return sign + "0." + "0".repeat(-decpt) + digits;
        }
        if (decpt >= digits.length()) {
            return sign + digits + "0".repeat(decpt - digits.length()) + ".0";
        }
        return sign + digits.substring(0, decpt) + "." + digits.substring(decpt);
    }
}
