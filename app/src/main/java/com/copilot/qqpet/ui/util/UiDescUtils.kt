package com.copilot.qqpet.ui.util

import com.copilot.qqpet.protocol.QQPetDirectBridge
import com.copilot.qqpet.ui.component.SegmentItem
import com.copilot.qqpet.ui.component.WorkPlaceOption

object UiDescUtils {

    fun getSchoolStageDesc(stage: Int, highestStage: Int = 0): String {
        if (stage == 0) {
            val highName = when (highestStage) {
                1 -> "初级学园"
                2 -> "中级学园"
                3 -> "高级学园"
                4 -> "进修学园"
                else -> null
            }
            return if (highName != null) {
                "智能自适应 (当前最高: $highName)"
            } else {
                "智能自适应 (自动就读已解锁最高学园)"
            }
        }
        return when (stage) {
            1 -> "初级学园 (初阶课程 · 基础打底)"
            2 -> "中级学园 (进阶课程 · 技能专精)"
            3 -> "高级学园 (高阶深造 · 学府殿堂)"
            4 -> "进修学园 (最高学府 · 极限强化)"
            else -> "学园阶段 $stage"
        }
    }

    fun buildSchoolStageOptions(details: QQPetDirectBridge.SecondMapDetails?): List<SegmentItem> {
        val defaultOptions = listOf(
            SegmentItem("自适应", enabled = true),
            SegmentItem("初级", enabled = true),
            SegmentItem("中级", enabled = true),
            SegmentItem("高级", enabled = true),
            SegmentItem("进修", enabled = true)
        )
        if (details == null || details.code != 0) return defaultOptions

        val curStage = details.currentStage
        val stageItems = mutableListOf<SegmentItem>()
        stageItems.add(SegmentItem("自适应", enabled = true))

        val s1 = details.stages.find { it.stage == 1 }
        val s1Grad = curStage > 1 || (s1?.isGraduated == true)
        val s1Enable = curStage == 1
        stageItems.add(
            SegmentItem(
                if (s1Grad) "初级(已毕业)" else "初级",
                enabled = s1Enable,
                disabledTip = if (s1Grad) "初级学园已毕业（腾讯规则禁止重复就读）" else if (!s1Enable) "初级学园尚未解锁" else null
            )
        )

        val s2 = details.stages.find { it.stage == 2 }
        val s2Grad = curStage > 2 || (s2?.isGraduated == true)
        val s2Enable = curStage == 2
        stageItems.add(
            SegmentItem(
                if (s2Grad) "中级(已毕业)" else if (curStage < 2) "中级(未解锁)" else "中级",
                enabled = s2Enable,
                disabledTip = if (s2Grad) "中级学园已毕业（腾讯规则禁止重复就读）" else if (curStage < 2) "中级学园尚未解锁（需先完成初级修习）" else null
            )
        )

        val s3 = details.stages.find { it.stage == 3 }
        val s3Grad = curStage > 3 || (s3?.isGraduated == true)
        val s3Enable = curStage == 3
        stageItems.add(
            SegmentItem(
                if (s3Grad) "高级(已毕业)" else if (curStage < 3) "高级(未解锁)" else "高级",
                enabled = s3Enable,
                disabledTip = if (s3Grad) "高级学园已毕业" else if (curStage < 3) "高级学园尚未解锁（需先完成中级深造）" else null
            )
        )

        val s4Enable = curStage == 4
        stageItems.add(
            SegmentItem(
                if (!s4Enable) "进修(未解锁)" else "进修",
                enabled = s4Enable,
                disabledTip = if (!s4Enable) "进修学园尚未解锁（需先完成高级学府深造）" else null
            )
        )
        return stageItems
    }

    fun getWorkTypeDesc(careerId: Int, placeTitle: String? = null): String {
        if (!placeTitle.isNullOrBlank()) {
            return placeTitle
        }
        return when (careerId) {
            1 -> "伐木场 (农林工 · 基础体力打工)"
            2 -> "采石场 (采掘工 · 中阶智力打工)"
            3 -> "矿洞探索 (矿工 · 高阶魅力打工)"
            4 -> "便利店 (收银员 · 商业初阶兼职)"
            5 -> "工坊制造 (学徒工 · 制造中阶打工)"
            6 -> "远洋港口 (水手 · 综合高阶历练)"
            else -> "打工场所 $careerId"
        }
    }

    fun buildWorkPlaceOptions(workDetails: QQPetDirectBridge.SecondMapDetails?): List<WorkPlaceOption> {
        val staticOptions = listOf(
            WorkPlaceOption(1, "伐木场"),
            WorkPlaceOption(2, "采石场"),
            WorkPlaceOption(3, "矿洞探索")
        )
        if (workDetails == null || workDetails.stages.isEmpty()) {
            return staticOptions
        }
        return workDetails.stages.map { stageInfo ->
            val isUnlocked = !stageInfo.isGraduated && stageInfo.limitStatus == 0
            val tip = if (isUnlocked) null else stageInfo.lockReason.ifEmpty { "尚未解锁该打工场所" }
            WorkPlaceOption(
                careerId = stageInfo.stage,
                title = stageInfo.title.ifEmpty { "场所${stageInfo.stage}" },
                enabled = isUnlocked,
                disabledTip = tip
            )
        }
    }

    fun getHiredRecallDesc(progress: Int): String = when (progress) {
        12 -> "12% 早期保底档 (收益起跑即撤，极速刷新)"
        42 -> "42% 中期平衡档 (兼顾打工收益与体力回流)"
        72 -> "72% 收益最大档 (默认推荐 · 斩获大额金币稳妥结算)"
        else -> "$progress% 召回"
    }

    fun getCareSubtitle(energy: Int, clean: Int): String {
        return "自身体力低于 ${energy} / 清洁度低于 ${clean} 立即照料"
    }

    fun getFriendCareSubtitle(energy: Int, clean: Int): String {
        return "好友体力低于 ${energy} / 清洁度低于 ${clean} 立即照料"
    }
}
