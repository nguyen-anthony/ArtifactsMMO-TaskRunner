package com.artifactsmmo.core.task

import com.artifactsmmo.core.task.BankExecutor.Companion.recycleBatchSize
import com.artifactsmmo.core.task.BankExecutor.Companion.recycleReturnPerItem
import kotlin.test.Test
import kotlin.test.assertEquals

class BankRecycleBatchTest {

    @Test
    fun `return per item follows the game formula`() {
        assertEquals(1, recycleReturnPerItem(5))
        assertEquals(2, recycleReturnPerItem(6))   // copper_ring: 6 copper bars
        assertEquals(2, recycleReturnPerItem(10))
        assertEquals(3, recycleReturnPerItem(11))
    }

    @Test
    fun `batch leaves room for recovered materials`() {
        // 100 copper rings in the bank, empty 100-slot inventory: 47 rings -> up to 94 materials + headroom.
        assertEquals(47, recycleBatchSize(left = 100, available = 100, freeCapacity = 100, perItem = 2))
    }

    @Test
    fun `batch is limited by what is left and what is available`() {
        assertEquals(10, recycleBatchSize(left = 10, available = 100, freeCapacity = 100, perItem = 1))
        assertEquals(3, recycleBatchSize(left = 53, available = 3, freeCapacity = 100, perItem = 2))
        assertEquals(0, recycleBatchSize(left = 5, available = 5, freeCapacity = 4, perItem = 1))
    }
}
