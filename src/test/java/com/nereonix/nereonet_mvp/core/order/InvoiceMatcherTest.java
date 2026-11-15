package com.nereonix.nereonet_mvp.core.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.order.InvoiceMatcher.Candidate;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatcher.Conversion;
import com.nereonix.nereonet_mvp.core.order.InvoiceMatcher.Outcome;

/**
 * P63, matching invoice lines to the order: names normalised (case, accents, punctuation, plurals) with pack sizes
 * apart; MATCHED only when clearly ahead, CHECK when likely, nothing (PICK) otherwise; the pack factor from the
 * description; the supplier by its name.
 */
class InvoiceMatcherTest {

    private static final List<Candidate> ORDER = List.of(
            new Candidate(1L, "Tomatoes"), new Candidate(2L, "Tomato paste"), new Candidate(3L, "Basmati rice"),
            new Candidate(4L, "Eggs"), new Candidate(5L, "Chicken fillet"), new Candidate(6L, "Chicken"),
            new Candidate(7L, "Mineral water"), new Candidate(8L, "Feta cheese"));

    @Test
    void namesAreNormalisedAndPackSizesKeptApart() {
        InvoiceMatcher.Name name = InvoiceMatcher.name("BASMATI RICE 10KG, Ρύζι μπασμάτι");
        assertThat(name.words()).containsExactly("basmati", "rice", "ρυζι", "μπασματι");
        assertThat(name.packs()).singleElement().satisfies(p -> {
            assertThat(p.count()).isEqualTo(1);
            assertThat(p.size()).isEqualByComparingTo("10");
            assertThat(p.unit()).isEqualTo("kg");
            assertThat(p.label()).isEqualTo("10 kg");
        });
        assertThat(name.key()).isEqualTo("basmati rice 10kg ρυζι μπασματι");
        // A multi pack, a decimal comma, grams, centilitres; plurals dropped.
        assertThat(InvoiceMatcher.name("WATER 6X1,5LT").packs()).singleElement()
                .satisfies(p -> assertThat(p.label()).isEqualTo("6 × 1.5 lt"));
        assertThat(InvoiceMatcher.name("Spaghetti 500 g").packs().getFirst().label()).isEqualTo("500 g");
        assertThat(InvoiceMatcher.name("Cola 33cl").packs().getFirst().label()).isEqualTo("330 ml");
        assertThat(InvoiceMatcher.name("Tomatoes / Potatoes / Cherries / Eggs / Glass").words())
                .containsExactly("tomato", "potato", "cherry", "egg", "glass");
        // The same description in other case, accents and punctuation: the same key.
        assertThat(InvoiceMatcher.key("Basmati Rice - 10 kg")).isEqualTo(InvoiceMatcher.key("BASMATI RICE 10KG"));
    }

    @Test
    void aClearBestIsMatched() {
        assertThat(InvoiceMatcher.best("TOMATOES / Ντομάτες", ORDER))
                .satisfies(o -> assertThat(o.match()).isEqualTo(InvoiceMatch.MATCHED))
                .extracting(Outcome::id).isEqualTo(1L);
        assertThat(InvoiceMatcher.best("RICE BASMATI 10KG", ORDER).id()).isEqualTo(3L);
        assertThat(InvoiceMatcher.best("Eggs, large", ORDER).match()).isEqualTo(InvoiceMatch.MATCHED);
        // A shorter name inside the best one does not make it uncertain: Tomato paste, not Tomatoes; Chicken fillet,
        // not Chicken.
        assertThat(InvoiceMatcher.best("Tomato paste / Πελτές", ORDER))
                .satisfies(o -> assertThat(o.match()).isEqualTo(InvoiceMatch.MATCHED)).extracting(Outcome::id).isEqualTo(2L);
        assertThat(InvoiceMatcher.best("Chicken fillet, frozen / Φιλέτο κοτόπουλο", ORDER))
                .satisfies(o -> assertThat(o.match()).isEqualTo(InvoiceMatch.MATCHED)).extracting(Outcome::id).isEqualTo(5L);
        assertThat(InvoiceMatcher.best("MINERAL WATER 6X1.5LT", ORDER).id()).isEqualTo(7L);
    }

    @Test
    void aLikelyButUncertainOneIsChecked() {
        // "Feta" is half of Feta cheese: likely, not certain.
        assertThat(InvoiceMatcher.best("FETA 2KG", ORDER))
                .satisfies(o -> assertThat(o.match()).isEqualTo(InvoiceMatch.CHECK)).extracting(Outcome::id).isEqualTo(8L);
        assertThat(InvoiceMatcher.best("TOMATO 400G", List.of(new Candidate(1L, "Tomato paste"),
                new Candidate(2L, "Sunflower oil")))).satisfies(o -> assertThat(o.match()).isEqualTo(InvoiceMatch.CHECK))
                .extracting(Outcome::id).isEqualTo(1L);
        // Two equally likely: nothing (the cook chooses), never a guess.
        List<Candidate> twoRices = List.of(new Candidate(1L, "Basmati rice"), new Candidate(2L, "Rice flour"));
        assertThat(InvoiceMatcher.best("RICE 10KG", twoRices).match()).isEqualTo(InvoiceMatch.PICK);
        // Only a word in common with a different product: not even likely ("White cheese" is not Feta cheese for sure,
        // olive oil is not sunflower oil).
        assertThat(InvoiceMatcher.best("CHEESE WHITE 2KG", ORDER).found()).isFalse();
    }

    @Test
    void nothingLikelyIsLeftToTheCook() {
        assertThat(InvoiceMatcher.best("Dishwashing liquid", ORDER)).isEqualTo(Outcome.NONE);
        assertThat(InvoiceMatcher.best("Olive oil 5 lt", List.of(new Candidate(1L, "Sunflower oil")))).isEqualTo(Outcome.NONE);
        assertThat(InvoiceMatcher.best("", ORDER).found()).isFalse();
        assertThat(InvoiceMatcher.best("12345", ORDER).found()).isFalse();
        assertThat(InvoiceMatcher.best("Tomatoes", List.of()).found()).isFalse();
    }

    @Test
    void thePackFactorComesFromTheDescription() {
        // A bag of 10 kg for an item in kg: 10.
        assertThat(InvoiceMatcher.conversion("RICE 10KG", "BAG", "kg")).satisfies(c -> {
            assertThat(c.factor()).isEqualByComparingTo("10");
            assertThat(c.pack()).isEqualTo("10 kg");
            assertThat(c.unitOne()).isEqualTo("bag");
            assertThat(c.unitMany()).isEqualTo("bags");
            assertThat(c.converted()).isTrue();
        });
        // 1 kg pieces for kg: 1, still a conversion to review.
        assertThat(InvoiceMatcher.conversion("SUGAR 1KG", "PCS", "kg")).satisfies(c -> {
            assertThat(c.factor()).isEqualByComparingTo("1");
            assertThat(c.unitMany()).isEqualTo("packs");
            assertThat(c.converted()).isTrue();
        });
        // 6 x 1.5 lt for an item in lt: 9; for bottles (pcs): 6.
        assertThat(InvoiceMatcher.conversion("WATER 6X1.5LT", "PACK", "lt").factor()).isEqualByComparingTo("9");
        assertThat(InvoiceMatcher.conversion("WATER 6X1.5LT", "PACK", "pcs").factor()).isEqualByComparingTo("6");
        // 500 g packets for kg: 0.5; 5 lt cans for lt: 5; boxes.
        assertThat(InvoiceMatcher.conversion("Spaghetti 500 g", "PKT", "kg").factor()).isEqualByComparingTo("0.5");
        assertThat(InvoiceMatcher.conversion("Olive oil 5 lt", "CAN", "lt").unitMany()).isEqualTo("cans");
        assertThat(InvoiceMatcher.conversion("Tea 2KG", "BOX", "kg").unitMany()).isEqualTo("boxes");
        // The same unit: nothing converted, the row counts in the item's unit.
        assertThat(InvoiceMatcher.conversion("Tomatoes", "KG", "kg")).isEqualTo(Conversion.SAME);
        assertThat(InvoiceMatcher.conversion("Eggs", "PCS", "pcs")).isEqualTo(Conversion.SAME);
        assertThat(InvoiceMatcher.conversion("Eggs", "pcs.", "pc")).isEqualTo(Conversion.SAME);
        assertThat(InvoiceMatcher.conversion("Rice", "KGS", "kg")).isEqualTo(Conversion.SAME);
        // Another unit of the same measure: grams for kg.
        assertThat(InvoiceMatcher.conversion("Saffron", "G", "kg")).satisfies(c -> {
            assertThat(c.factor()).isEqualByComparingTo("0.001");
            assertThat(c.unitMany()).isEqualTo("g");
        });
        // No unit on the invoice: the item's, unless a pack size makes it unclear.
        assertThat(InvoiceMatcher.conversion("Tomatoes", null, "kg")).isEqualTo(Conversion.SAME);
        assertThat(InvoiceMatcher.conversion("RICE 10KG", " ", "kg")).isNull();
        // Cannot be worked out: weighed for an item counted, a pack without its size, litres for kg.
        assertThat(InvoiceMatcher.conversion("Eggs", "KG", "pcs")).isNull();
        assertThat(InvoiceMatcher.conversion("RICE", "BAG", "kg")).isNull();
        assertThat(InvoiceMatcher.conversion("MILK 1LT", "PCS", "kg")).isNull();
        assertThat(InvoiceMatcher.conversion("TEA BAGS", "BOX", "pcs")).isNull();
    }

    @Test
    void aLearnedFactorIsUsedAndNeverReviewedAgain() {
        assertThat(InvoiceMatcher.learned("RICE 10KG", "BAG", "kg", new BigDecimal("10"))).satisfies(c -> {
            assertThat(c.factor()).isEqualByComparingTo("10");
            assertThat(c.unitMany()).isEqualTo("bags");
            assertThat(c.converted()).isFalse();
        });
        // Learned where the description gives no size: the factor as learned.
        assertThat(InvoiceMatcher.learned("RICE", "BAG", "kg", new BigDecimal("25"))).satisfies(c -> {
            assertThat(c.factor()).isEqualByComparingTo("25");
            assertThat(c.pack()).isNull();
            assertThat(c.unitOne()).isEqualTo("bag");
        });
        assertThat(InvoiceMatcher.learned("RICE", "BAG", "kg", null)).isNull();
        assertThat(InvoiceMatcher.learned("Tomatoes", "KG", "kg", BigDecimal.ONE).packed()).isFalse();
    }

    @Test
    void theSupplierIsFoundByItsName() {
        List<Candidate> suppliers = List.of(new Candidate(1L, "Kestrel Ship Supply"), new Candidate(2L, "Nordic Chandlers"),
                new Candidate(3L, "Kestrel"));
        assertThat(InvoiceMatcher.supplier("KESTREL SHIP SUPPLY S.A.", suppliers)).isEqualTo(1L);
        assertThat(InvoiceMatcher.supplier("Nordic Chandlers Ltd", suppliers)).isEqualTo(2L);
        assertThat(InvoiceMatcher.supplier("Atlas Provisions", suppliers)).isNull();
        assertThat(InvoiceMatcher.supplier(null, suppliers)).isNull();
        assertThat(InvoiceMatcher.supplierKey("Atlas Provisions S.A.")).isEqualTo("atlas provisions");
    }
}
