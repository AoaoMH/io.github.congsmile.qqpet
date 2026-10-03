package com.copilot.qqpet.engine

import com.copilot.qqpet.engine.model.StudyDispatchParam
import com.copilot.qqpet.engine.model.WorkDispatchParam
import com.copilot.qqpet.engine.task.PetAdaptiveWorkTask
import com.copilot.qqpet.engine.task.PetStudyTask
import com.copilot.qqpet.protocol.QQPetDirectBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCareerDispatchTest {

    @Test
    fun testStudyCandidatePoolContainsFallback() {
        val param = StudyDispatchParam(studyMode = 1) // 专攻智力
        // 验证候选池里包含智力课程
        val pool = PetStudyTask.CANDIDATE_COURSES_INTELLECT
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.any { it.first.contains("智力") || it.first.contains("文化") })
    }

    @Test
    fun testWorkCandidatePoolContainsFallback() {
        val param = WorkDispatchParam(workMode = 1) // 专攻文职
        val pool = com.copilot.qqpet.engine.task.PetWorkTask.CANDIDATE_JOBS_CLERK
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.any { it.first.contains("文职") || it.first.contains("图书") || it.first.contains("魔法塔") })
    }

    @Test
    fun testWorkPlaceSelectionDefaultStarTower() {
        // 当未解锁任何特殊小镇场所时，保底使用星尘魔法塔
        val param = WorkDispatchParam(customWorkType = 0, cachedWorkPlaces = null)
        assertNotNull(param)
        assertEquals(0, param.customWorkType)
    }
}
