package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 13-16, against the 25 cases probe_03.py ran through pandas itself.
 * The expected strings are pandas' output, not this port's idea of it.
 */
class PandasCsvTest {

    private static final Path ANSWERS =
            Path.of("..", "graphrag-port", "answers", "source-answers-3.json");

    private static Map<String, List<Map<String, Object>>> cases() {
        Map<String, List<Map<String, Object>>> cases = new LinkedHashMap<>();
        cases.put("plain", List.of(row("a", 1L, "b", "x")));
        cases.put("comma_in_value", List.of(row("a", 1L, "b", "x,y")));
        cases.put("quote_in_value", List.of(row("a", 1L, "b", "he said \"hi\"")));
        cases.put("comma_and_quote", List.of(row("a", 1L, "b", "a,\"b\"")));
        cases.put("newline_in_value", List.of(row("a", 1L, "b", "line1\nline2")));
        cases.put("crlf_in_value", List.of(row("a", 1L, "b", "line1\r\nline2")));
        cases.put("cr_in_value", List.of(row("a", 1L, "b", "line1\rline2")));
        cases.put("tab_in_value", List.of(row("a", 1L, "b", "left\tright")));
        cases.put("leading_space", List.of(row("a", 1L, "b", "  padded  ")));
        cases.put("empty_string", List.of(row("a", 1L, "b", "")));
        cases.put("none_value", List.of(row("a", 1L, "b", null)));
        cases.put("int_and_float_same_column",
                List.of(row("a", 1L, "b", 1L), row("a", 2L, "b", 2.5)));
        cases.put("float_whole_number", List.of(row("a", 1.0, "b", 2.0)));
        cases.put("int_column", List.of(row("a", 1L, "b", 2L)));
        cases.put("none_makes_int_column_float",
                List.of(row("a", 1L, "b", 2L), row("a", 2L, "b", null)));
        cases.put("bool_column", List.of(row("a", true, "b", false)));
        cases.put("missing_key_in_second_record",
                List.of(row("a", 1L, "b", 2L), single("a", 3L)));
        cases.put("extra_key_in_second_record",
                List.of(single("a", 1L), row("a", 2L, "b", 3L)));
        cases.put("column_order_from_first_record", List.of(row("b", 1L, "a", 2L)));
        cases.put("large_float", List.of(row("a", 1e22, "b", 0.1 + 0.2)));
        cases.put("negative_and_zero", List.of(row("a", -0.0, "b", -3L)));
        cases.put("unicode", List.of(row("a", 1L, "b", "café — naïve")));
        cases.put("long_int", List.of(row("a", 9007199254740993L, "b", 1L)));
        cases.put("nan_float", List.of(row("a", Double.NaN, "b", 1L)));
        cases.put("list_value", List.of(row("a", 1L, "b", "[1, 2]")));
        return cases;
    }

    private static Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static Map<String, Object> single(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    @Test
    void everyPandasCaseRendersIdentically() throws Exception {
        JsonNode expected = new ObjectMapper()
                .readTree(Files.readString(ANSWERS)).get("to_csv");

        List<String> disagreements = new ArrayList<>();
        for (var entry : cases().entrySet()) {
            String want = expected.get(entry.getKey()).asText();
            String got = PandasCsv.toCsv(entry.getValue());
            if (!want.equals(got)) {
                disagreements.add(entry.getKey() + ": pandas " + escape(want)
                        + " but this port " + escape(got));
            }
        }
        assertThat(disagreements).isEmpty();
        // A guard against the corpus quietly shrinking: every case pandas ran is replayed.
        assertThat(cases().keySet()).containsExactlyInAnyOrderElementsOf(
                () -> expected.fieldNames());
    }

    @Test
    void aNaNFloatIsBlankRatherThanTheWordNaN() {
        // Rule 15. NaN reaches this only through the FLOAT branch, so it is checked there
        // and not only through the column-kind inference above.
        assertThat(PandasCsv.toCsv(List.of(single("a", Double.NaN))))
                .isEqualTo("a\r\n\r\n");
    }

    @Test
    void emptyRecordsRenderAsPandasDoes() {
        assertThat(PandasCsv.toCsv(List.of())).isEqualTo("\r\n");
    }

    @Test
    void pythonReprSwitchesToExponentFormWherePythonDoes() {
        assertThat(PandasCsv.pythonRepr(1e15)).isEqualTo("1000000000000000.0");
        assertThat(PandasCsv.pythonRepr(1e16)).isEqualTo("1e+16");
        assertThat(PandasCsv.pythonRepr(1e-4)).isEqualTo("0.0001");
        assertThat(PandasCsv.pythonRepr(1e-5)).isEqualTo("1e-05");
        assertThat(PandasCsv.pythonRepr(1.5e22)).isEqualTo("1.5e+22");
        assertThat(PandasCsv.pythonRepr(-0.0)).isEqualTo("-0.0");
        assertThat(PandasCsv.pythonRepr(0.0)).isEqualTo("0.0");
        assertThat(PandasCsv.pythonRepr(13.0)).isEqualTo("13.0");
        assertThat(PandasCsv.pythonRepr(0.1 + 0.2)).isEqualTo("0.30000000000000004");
    }

    private static String escape(String s) {
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
