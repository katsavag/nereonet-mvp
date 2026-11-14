package com.nereonix.nereonet_mvp.core.rfq;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** P44: pack units turned into item units, so quotes in different packs compare per item unit. */
class UnitsTest {

    @Test
    void unitsOfOneKindConvert() {
        assertThat(Units.factor("L", "lt")).isEqualByComparingTo("1");
        assertThat(Units.factor("ml", "lt")).isEqualByComparingTo("0.001");
        assertThat(Units.factor("g", "kg")).isEqualByComparingTo("0.001");
        assertThat(Units.factor("kg", "g")).isEqualByComparingTo("1000");
        assertThat(Units.factor("dozen", "pcs")).isEqualByComparingTo("12");
        assertThat(Units.factor("Pcs.", "pcs")).isEqualByComparingTo(BigDecimal.ONE);
        // A unit not listed converts only to itself.
        assertThat(Units.factor("Box", "box")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void otherUnitsDoNotConvert() {
        assertThat(Units.convertible("kg", "lt")).isFalse();
        assertThat(Units.convertible("box", "pcs")).isFalse();
        assertThat(Units.convertible("", "kg")).isFalse();
        assertThat(Units.convertible(null, "kg")).isFalse();
    }
}
