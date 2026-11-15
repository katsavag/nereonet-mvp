package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Extraction;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Item;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Line;

/**
 * P57: the model's answer is checked by our code. Only items sent, valid store quantities, each item once; the rest
 * goes to unmatched by its words. Not JSON: nothing understood. And the fake's simple rules.
 */
class CountAnswerTest {

    private static final List<Item> ITEMS = List.of(new Item(1L, "Tomatoes", "kg"), new Item(2L, "Chicken breast", "kg"),
            new Item(3L, "Eggs", "pcs"), new Item(4L, "Basmati rice", "kg"));

    @Test
    void aValidAnswerBecomesLines() {
        Extraction extraction = CountAnswer.parse("""
                {"counts": [{"item_id": 1, "quantity": 5, "words": "five kilos of tomatoes", "confidence": 0.92},
                            {"item_id": "3", "quantity": "2,5", "words": "  two and a half   eggs ", "confidence": 0.6},
                            {"item_id": 4, "quantity": 12.000, "words": "twelve rice", "confidence": 1}],
                 "unmatched": ["three cans of herring", "  ", 7]}""", ITEMS);

        assertThat(extraction.lines()).containsExactly(
                new Line(1L, new BigDecimal("5"), "five kilos of tomatoes", 0.92),
                new Line(3L, new BigDecimal("2.5"), "two and a half eggs", 0.6),
                new Line(4L, new BigDecimal("12"), "twelve rice", 1.0));
        assertThat(extraction.unmatched()).containsExactly("three cans of herring");
    }

    @Test
    void anInventedItemABadQuantityOrAnItemTwiceGoesToUnmatched() {
        Extraction extraction = CountAnswer.parse("""
                {"counts": [{"item_id": 99, "quantity": 3, "words": "three cans of herring", "confidence": 0.9},
                            {"item_id": 1, "quantity": -2, "words": "minus two tomatoes", "confidence": 0.9},
                            {"item_id": 2, "quantity": 1.2345, "words": "one point two three four five chicken"},
                            {"item_id": 3, "quantity": "a few", "words": "a few eggs", "confidence": 0.9},
                            {"item_id": 4, "quantity": 10, "words": "ten rice", "confidence": 7},
                            {"item_id": 4, "quantity": 2, "words": "two more rice", "confidence": 0.9},
                            {"item_id": 1, "words": "tomatoes"},
                            {"name": "Salt", "quantity": 1}]}""", ITEMS);

        assertThat(extraction.lines()).containsExactly(new Line(4L, new BigDecimal("10"), "ten rice", 0.0));
        assertThat(extraction.unmatched()).containsExactly("three cans of herring", "minus two tomatoes",
                "one point two three four five chicken", "a few eggs", "two more rice", "tomatoes");
    }

    @Test
    void anAnswerThatIsNotValidJsonIsNothingUnderstood() {
        assertThat(CountAnswer.parse("{\"counts\": [{\"item_id\": 1, \"quantity\": 5", ITEMS).isEmpty()).isTrue();
        assertThat(CountAnswer.parse("Sure! Here are the counts: five tomatoes", ITEMS).isEmpty()).isTrue();
        assertThat(CountAnswer.parse("[1, 2]", ITEMS).isEmpty()).isTrue();
        assertThat(CountAnswer.parse(null, ITEMS).isEmpty()).isTrue();
        assertThat(CountAnswer.parse("{}", ITEMS).isEmpty()).isTrue();
    }

    @Test
    void theFakeMatchesNamesWithSimpleRules() {
        FakeCountExtractor fake = new FakeCountExtractor();

        Extraction extraction = fake.extract("Five kilos of tomatoes, eight kilos of chicken and thirty five eggs; "
                + "2.5 kg basmati rice. Three cans of herring. Good morning. Some more tomatoes, 4 tomatoes", ITEMS);

        assertThat(extraction.lines()).containsExactly(
                new Line(1L, new BigDecimal("5"), "Five kilos of tomatoes", FakeCountExtractor.WHOLE_NAME),
                new Line(2L, new BigDecimal("8"), "eight kilos of chicken", FakeCountExtractor.ONE_WORD),
                new Line(3L, new BigDecimal("35"), "thirty five eggs", FakeCountExtractor.WHOLE_NAME),
                new Line(4L, new BigDecimal("2.5"), "2.5 kg basmati rice", FakeCountExtractor.WHOLE_NAME));
        assertThat(extraction.unmatched()).containsExactly("Three cans of herring", "Some more tomatoes", "4 tomatoes");
        assertThat(fake.extract("Good morning, chef.", ITEMS).isEmpty()).isTrue();
        assertThat(fake.extract("a dozen eggs", ITEMS).lines()).extracting(Line::quantity)
                .containsExactly(new BigDecimal("12"));
    }
}
