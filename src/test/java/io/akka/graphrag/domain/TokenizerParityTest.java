package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rule 38. Every trim boundary and every substitution decision in the rollup is a
 * comparison against a token count, so a tokenizer that is close is a tokenizer that changes
 * answers. The corpus is probe_04.py's, drawn from the source's own fixture text.
 */
class TokenizerParityTest {

    @Test
    void everyCorpusStringCountsWhatTiktokenCounted() {
        Tokenizer tokenizer = Tokenizer.cl100k();
        JsonNode corpus = Fixture.answers("token-corpus.json");

        List<String> disagreements = new ArrayList<>();
        for (JsonNode entry : corpus) {
            String text = entry.get("text").asText();
            int expected = entry.get("tokens").asInt();
            int actual = tokenizer.countTokens(text);
            if (actual != expected) {
                disagreements.add(expected + " vs " + actual + " for "
                        + text.substring(0, Math.min(60, text.length())));
            }
        }
        assertThat(disagreements).isEmpty();
        assertThat(corpus.size()).isEqualTo(176);
    }

    @Test
    void theCorpusContainsTheCharactersThatWouldBreakANaiveTokenizer() {
        // Guards the test above against a corpus of nothing but ASCII words.
        JsonNode corpus = Fixture.answers("token-corpus.json");
        List<String> texts = new ArrayList<>();
        corpus.forEach(entry -> texts.add(entry.get("text").asText()));

        assertThat(texts).anyMatch(t -> t.contains("\r\n"));
        assertThat(texts).anyMatch(t -> t.contains("\""));
        assertThat(texts).anyMatch(t -> t.codePoints().anyMatch(c -> c > 0x7f));
        assertThat(texts).anyMatch(t -> t.codePoints().anyMatch(c -> c > 0xffff));
        assertThat(texts).anyMatch(t -> t.length() > 5_000);
    }
}
