/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArithmeticTest {

    @Test
    fun `the sums the small model sent to set_timer`() {
        assertThat(Arithmetic.answer("That's 4 times 37.")).isEqualTo("4 times 37 is 148.")
        assertThat(Arithmetic.answer("96 divided by 3")).isEqualTo("96 divided by 3 is 32.")
        assertThat(Arithmetic.answer("What's 96,000 divided by 400?")).isEqualTo("96,000 divided by 400 is 240.")
    }

    @Test
    fun `the sum the answer model got wrong`() {
        assertThat(Arithmetic.answer("What is 96,000 multiplied by 30,000?"))
            .isEqualTo("96,000 times 30,000 is 2,880,000,000.")
    }

    @Test
    fun `numbers said in words are said back in digits`() {
        assertThat(Arithmetic.answer("four times thirty seven")).isEqualTo("4 times 37 is 148.")
        assertThat(Arithmetic.answer("what is ninety-six thousand divided by four hundred"))
            .isEqualTo("96,000 divided by 400 is 240.")
        assertThat(Arithmetic.answer("one hundred and five plus a hundred")).isEqualTo("105 plus 100 is 205.")
    }

    @Test
    fun `symbols are the words`() {
        assertThat(Arithmetic.answer("6 x 7")).isEqualTo("6 times 7 is 42.")
        assertThat(Arithmetic.answer("6×7")).isEqualTo("6 times 7 is 42.")
        assertThat(Arithmetic.answer("84 ÷ 2")).isEqualTo("84 divided by 2 is 42.")
        assertThat(Arithmetic.answer("2 + 2")).isEqualTo("2 plus 2 is 4.")
        assertThat(Arithmetic.answer("10 - 4")).isEqualTo("10 minus 4 is 6.")
        assertThat(Arithmetic.answer("15% of 80")).isEqualTo("15 percent of 80 is 12.")
    }

    @Test
    fun `the other operators`() {
        assertThat(Arithmetic.answer("How much is 15 percent of 80")).isEqualTo("15 percent of 80 is 12.")
        assertThat(Arithmetic.answer("what is 7 squared")).isEqualTo("7 squared is 49.")
        assertThat(Arithmetic.answer("3 cubed")).isEqualTo("3 cubed is 27.")
        assertThat(Arithmetic.answer("square root of 144")).isEqualTo("square root of 144 is 12.")
        assertThat(Arithmetic.answer("2 to the power of 10")).isEqualTo("2 to the power of 10 is 1,024.")
        assertThat(Arithmetic.answer("20 over 4 equals")).isEqualTo("20 divided by 4 is 5.")
    }

    @Test
    fun `times and divided by come before plus and minus`() {
        assertThat(Arithmetic.answer("2 plus 3 times 4")).isEqualTo("2 plus 3 times 4 is 14.")
        assertThat(Arithmetic.answer("2 plus 3 squared")).isEqualTo("2 plus 3 squared is 11.")
        assertThat(Arithmetic.answer("10 minus 4 minus 3")).isEqualTo("10 minus 4 minus 3 is 3.")
    }

    @Test
    fun `an inexact result is rounded half up to four places`() {
        assertThat(Arithmetic.answer("10 divided by 3")).isEqualTo("10 divided by 3 is 3.3333.")
        assertThat(Arithmetic.answer("2 divided by 3")).isEqualTo("2 divided by 3 is 0.6667.")
        assertThat(Arithmetic.answer("1.5 plus 1.5")).isEqualTo("1.5 plus 1.5 is 3.")
        assertThat(Arithmetic.answer("1 divided by 30000")).isEqualTo("1 divided by 30,000 is 0.00003333.")
    }

    @Test
    fun `a negative result is said as negative`() {
        assertThat(Arithmetic.answer("3 minus 5")).isEqualTo("3 minus 5 is negative 2.")
    }

    @Test
    fun `dividing by zero is declined aloud`() {
        assertThat(Arithmetic.answer("96 divided by 0")).isEqualTo("You can't divide by zero.")
    }

    @Test
    fun `a root that is not whole is rounded like any result`() {
        // No number can be negative before a root in a spoken sum, so that guard is not reachable here.
        assertThat(Arithmetic.answer("square root of 5 minus 9"))
            .isEqualTo("square root of 5 minus 9 is negative 6.7639.")
    }

    @Test
    fun `anything that is not wholly a sum is null`() {
        assertThat(Arithmetic.answer("Call 6-1-1")).isNull()
        assertThat(Arithmetic.answer("set a timer for 4 minutes")).isNull()
        assertThat(Arithmetic.answer("what time is it")).isNull()
        assertThat(Arithmetic.answer("what is 5")).isNull()
        assertThat(Arithmetic.answer("text Sam 2 plus 2")).isNull()
        assertThat(Arithmetic.answer("what's 2 plus 2 minutes")).isNull()
        assertThat(Arithmetic.answer("2 divided by 0 minutes")).isNull()
        assertThat(Arithmetic.answer("2 plus")).isNull()
        assertThat(Arithmetic.answer("five six plus two")).isNull()
        assertThat(Arithmetic.answer("")).isNull()
    }

    @Test
    fun `numbers too big to read out are left to the model`() {
        assertThat(Arithmetic.answer("10 to the power of 40")).isNull()
        assertThat(Arithmetic.answer("2 to the power of 0.5")).isNull()
    }
}
