package com.alexandr5476.lifetracing.domain

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DomainModuleTest {
    @Test
    fun `domain module can run JVM tests`() {
        assertNotNull(DomainModule)
    }
}
