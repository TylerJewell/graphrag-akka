package io.akka.graphrag.domain;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Counts tokens the way SPEC-001 §3 rule 38 requires. An interface rather than a static call
 * because every trimming decision in §3 turns on it, and a test that wants to see the trim
 * boundary move should not have to build an 8,000-token string to get there.
 */
@FunctionalInterface
public interface Tokenizer {

    int countTokens(String text);

    /**
     * {@code cl100k_base}, the encoding question-log row 4 checked against tiktoken over 176
     * strings from the source's own fixture with no disagreement.
     */
    static Tokenizer cl100k() {
        Encoding encoding = Encodings.newDefaultEncodingRegistry()
                .getEncoding(EncodingType.CL100K_BASE);
        return encoding::countTokens;
    }
}
