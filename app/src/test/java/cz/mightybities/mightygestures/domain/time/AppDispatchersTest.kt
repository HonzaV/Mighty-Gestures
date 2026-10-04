package cz.mightybities.mightygestures.domain.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppDispatchersTest {
    @Test
    fun `defaults main to Dispatchers Main`() {
        assertEquals(Dispatchers.Main, AppDispatchers().main)
    }

    @Test
    fun `main can be overridden for tests`() {
        val testDispatcher = UnconfinedTestDispatcher()
        val dispatchers = AppDispatchers(main = testDispatcher)

        assertEquals(testDispatcher, dispatchers.main)
        assertNotEquals(Dispatchers.Main, dispatchers.main)
    }
}
