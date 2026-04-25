package com.yourname.expensetracker.domain.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BKTreeTest {

    @Test
    fun `size and empty state reflect inserts and clear`() = runTest {
        val tree = StringBKTree.create()

        assertThat(tree.isEmpty).isTrue()
        assertThat(tree.size).isEqualTo(0)

        tree.insert("Lidl")
        tree.insert("AB")

        assertThat(tree.isEmpty).isFalse()
        assertThat(tree.size).isEqualTo(2)

        tree.clear()

        assertThat(tree.isEmpty).isTrue()
        assertThat(tree.size).isEqualTo(0)
    }
}
