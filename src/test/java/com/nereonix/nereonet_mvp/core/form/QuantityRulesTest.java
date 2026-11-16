package com.nereonix.nereonet_mvp.core.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.form.QuantityRules.Check;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Kind;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.item.Item;

/** P60: one rule for every quantity, by the kind of the item's unit. */
class QuantityRulesTest {

    // The defaults of application.yml.
    private final QuantityRules rules = new QuantityRules(new BigDecimal("2000"), new BigDecimal("2000"),
            new BigDecimal("50000"), new BigDecimal("50000"), new BigDecimal("5000"), BigDecimal.TEN);

    @Test
    void unitsAreNormalisedAndMappedToTheirKind() {
        assertThat(QuantityRules.kindOf("kg")).isEqualTo(Kind.KG);
        assertThat(QuantityRules.kindOf(" KGS. ")).isEqualTo(Kind.KG);
        assertThat(QuantityRules.kindOf("Kilo")).isEqualTo(Kind.KG);
        assertThat(QuantityRules.kindOf("L")).isEqualTo(Kind.LT);
        assertThat(QuantityRules.kindOf("ltr")).isEqualTo(Kind.LT);
        assertThat(QuantityRules.kindOf("Litre")).isEqualTo(Kind.LT);
        assertThat(QuantityRules.kindOf("gr")).isEqualTo(Kind.G);
        assertThat(QuantityRules.kindOf("ml")).isEqualTo(Kind.ML);
        for (String countable : new String[] {"pcs", "Pc.", "piece", "pieces", "box", "can", "tin", "dozen", "carton"}) {
            assertThat(QuantityRules.kindOf(countable)).as(countable).isEqualTo(Kind.COUNTABLE);
        }
        // Unknown and missing units are countable.
        assertThat(QuantityRules.kindOf("bunch")).isEqualTo(Kind.COUNTABLE);
        assertThat(QuantityRules.kindOf(null)).isEqualTo(Kind.COUNTABLE);
        assertThat(QuantityRules.kindOf("")).isEqualTo(Kind.COUNTABLE);
    }

    @Test
    void kgTakesThreeDecimalsUpToItsMaximum() {
        QuantityRules.Rule kg = rules.forUnit("kg", Use.REQUEST);
        assertThat(kg.check("1,5").value()).isEqualByComparingTo("1.5");
        assertThat(kg.check("0.125").value()).isEqualByComparingTo("0.125");
        assertThat(kg.check("2000").ok()).isTrue();
        assertError(kg.check("0.1255"), "quantity.error.decimals");
        assertError(kg.check("2000.001"), "quantity.error.max.request");
        assertError(kg.check("1000000"), "quantity.error.max.request");
        assertThat(kg.maxText()).isEqualTo("2000");
        assertThat(kg.inputMode()).isEqualTo("decimal");
        assertThat(kg.step()).isEqualTo("0.001");
    }

    @Test
    void pcsTakeWholeNumbersOnly() {
        QuantityRules.Rule pcs = rules.forUnit("pcs", Use.REQUEST);
        assertThat(pcs.check("12").value()).isEqualByComparingTo("12");
        // 12.0 is a whole number.
        assertThat(pcs.check("12,0").value()).isEqualByComparingTo("12");
        assertError(pcs.check("1,5"), "quantity.error.whole");
        assertError(pcs.check("5001"), "quantity.error.max.request");
        assertThat(pcs.inputMode()).isEqualTo("numeric");
        // g and ml are whole numbers too.
        assertError(rules.forUnit("g", Use.REQUEST).check("2.5"), "quantity.error.whole");
        assertThat(rules.forUnit("ml", Use.REQUEST).check("50000").ok()).isTrue();
    }

    @Test
    void lettersBlanksAndTheMinimumAreRefused() {
        QuantityRules.Rule kg = rules.forUnit("kg", Use.REQUEST);
        assertError(kg.check("abc"), "quantity.error.number");
        assertError(kg.check("1.2.3"), "quantity.error.number");
        assertError(kg.check("5 kg"), "quantity.error.number");
        assertError(kg.check(" "), "quantity.error.required");
        assertError(kg.check("0"), "quantity.error.aboveZero");
        assertError(kg.check("-1"), "quantity.error.aboveZero");
        // Where 0 means "none": the receipt and the count.
        assertThat(rules.forUnit("kg", Use.RECEIPT).check("0").value()).isEqualByComparingTo("0");
        assertError(rules.forUnit("kg", Use.COUNT).check("-1"), "quantity.error.zeroOrMore");
    }

    @Test
    void theStoreCountHoldsTenTimesTheLineMaximum() {
        QuantityRules.Rule count = rules.forUnit("kg", Use.COUNT);
        assertThat(count.maxText()).isEqualTo("20000");
        assertThat(count.check("15000").ok()).isTrue();
        assertError(count.check("20001"), "quantity.error.max.count");
    }

    @Test
    void anItemsOwnMaximumReplacesTheUnitsInRequestsOrdersAndReceipts() {
        Item water = new Item();
        water.setUnit("lt");
        water.setMaxPerRequest(new BigDecimal("6000"));
        assertThat(rules.forItem(water, Use.REQUEST).check("5000").ok()).isTrue();
        assertThat(rules.forItem(water, Use.OFFICE_ORDER).maxText()).isEqualTo("6000");
        assertThat(rules.forItem(water, Use.RECEIPT).maxText()).isEqualTo("6000");
        // Not in the store count or the quote.
        assertThat(rules.forItem(water, Use.COUNT).maxText()).isEqualTo("20000");
        assertThat(rules.forItem(water, Use.QUOTE).maxText()).isEqualTo("2000");

        Item saffron = new Item();
        saffron.setUnit("g");
        saffron.setMaxPerRequest(new BigDecimal("100"));
        assertError(rules.forItem(saffron, Use.REQUEST).check("101"), "quantity.error.max.request");
    }

    @Test
    void aValueAlreadySavedMayStayAboveTheMaximum() {
        QuantityRules.Rule kg = rules.forUnit("kg", Use.REQUEST);
        assertThat(kg.check("3000", new BigDecimal("3000.000")).ok()).isTrue();
        assertError(kg.check("3001", new BigDecimal("3000")), "quantity.error.max.request");
    }

    @Test
    void theUnitsJsonListsEveryKnownUnitAndTheCountableDefault() {
        String json = rules.unitsJson("REQUEST");
        assertThat(json).startsWith("{").endsWith("}")
                .contains("\"kg\":[3,\"2000\"]", "\"l\":[3,\"2000\"]", "\"g\":[0,\"50000\"]", "\"\":[0,\"5000\"]");
    }

    private static void assertError(Check check, String code) {
        assertThat(check.ok()).isFalse();
        assertThat(check.code()).isEqualTo(code);
    }
}
