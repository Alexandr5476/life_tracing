package com.alexandr5476.lifetracing.data

import com.alexandr5476.lifetracing.domain.DomainModule
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DataModuleDependencyTest {
    @Test
    fun `data module can depend on pure domain module`() {
        assertNotNull(DomainModule)
    }
}
