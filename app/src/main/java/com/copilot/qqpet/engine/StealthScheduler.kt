package com.copilot.qqpet.engine

import kotlin.random.Random

/**
 * 抗风控隐身调度器：提供随机 1~3 分钟拟人休眠、按需延时抖动与隐身开关判定
 */
object StealthScheduler {

    /**
     * 计算在途任务休眠秒数
     * @param remainingSeconds 任务剩余秒数
     * @param humanLikeEnabled 是否开启自选拟人休眠（默认开启，随机 1~3 分钟）
     */
    fun calculateTaskSleepSeconds(
        remainingSeconds: Long?,
        humanLikeEnabled: Boolean = true
    ): Long {
        if (remainingSeconds == null || remainingSeconds <= 0) {
            return if (humanLikeEnabled) {
                // 随机 1~3 分钟 (60~180 秒)
                Random.nextLong(60, 181)
            } else {
                30L
            }
        }

        return if (humanLikeEnabled) {
            if (remainingSeconds <= 180) {
                // 任务即将结束（3 分钟内），睡到任务结束并加随机 5~25 秒拟人操作延迟
                remainingSeconds + Random.nextLong(5, 26)
            } else {
                // 长期在途任务：单次休眠随机 1~3 分钟 (60~180 秒)，既不失联又大幅降低轮询频次
                Random.nextLong(60, 181)
            }
        } else {
            // 关闭拟人休眠时的常规保底
            minOf(remainingSeconds + 2, 60L)
        }
    }

    /**
     * 空闲轮询间隔（毫秒）：随机 1~3 分钟 (60,000 ~ 180,000 ms)
     */
    fun calculateIdleCycleDelayMillis(humanLikeEnabled: Boolean = true): Long {
        return if (humanLikeEnabled) {
            Random.nextLong(60, 181) * 1000L
        } else {
            30 * 1000L
        }
    }

    fun isLogAllowed(debugEnabled: Boolean): Boolean = debugEnabled

    fun shouldInjectSettingCard(hideSettingEntry: Boolean): Boolean = !hideSettingEntry
}
