package com.iptv.tvplayer.utils

import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * 极简农历转换（1900-2100），用于 OSD 显示「农历 丙午马年 九月十六」。
 * 算法基于 1900-2100 年农历数据表，无第三方依赖。
 */
data class LunarDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val isLeap: Boolean
) {
    /** 例：丙午马年 */
    val yearName: String
        get() = LunarCalendar.ganZhi(year) + LunarCalendar.zodiac(year) + "年"

    /** 例：九月 / 闰四月 */
    val monthName: String
        get() = (if (isLeap) "闰" else "") + LunarCalendar.MONTH_NAMES[month - 1]

    /** 例：十六 */
    val dayName: String
        get() = LunarCalendar.dayName(day)

    /** 例：农历 丙午马年 九月十六 */
    fun fullText(): String = "农历 $yearName $monthName$dayName"

    /** 例：农历九月十六 */
    fun shortText(): String = "农历$monthName$dayName"
}

object LunarCalendar {

    /** 1900-2100 年农历数据表（每年 1 个 int：低 4 位闰月，bit16 闰月大小，bit15..bit4 各月大小） */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520
    )

    private val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    private val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    private val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")
    internal val MONTH_NAMES = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )
    private val DAY_UNITS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

    private fun leapMonth(year: Int): Int = LUNAR_INFO[year - 1900] and 0xf

    private fun leapDays(year: Int): Int {
        if (leapMonth(year) == 0) return 0
        return if ((LUNAR_INFO[year - 1900] and 0x10000) != 0) 30 else 29
    }

    private fun monthDays(year: Int, month: Int): Int =
        if ((LUNAR_INFO[year - 1900] and (0x10000 shr month)) != 0) 30 else 29

    private fun yearDays(year: Int): Int {
        var sum = 348
        var bit = 0x8000
        while (bit > 0x8) {
            if ((LUNAR_INFO[year - 1900] and bit) != 0) sum++
            bit = bit shr 1
        }
        return sum + leapDays(year)
    }

    private fun daysSince1900(year: Int, month: Int, day: Int): Int {
        val utc = TimeZone.getTimeZone("UTC")
        val target = Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day)
        }
        val base = Calendar.getInstance(utc).apply {
            clear()
            set(1900, 0, 31)
        }
        return ((target.timeInMillis - base.timeInMillis) / 86400000L).toInt()
    }

    /** 公历转农历 */
    fun fromDate(date: Date): LunarDate {
        val cal = Calendar.getInstance().apply { time = date }
        var offset = daysSince1900(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        if (offset < 0) return LunarDate(1900, 1, 1, false)

        var lunarYear = 1900
        var daysOfYear: Int
        while (lunarYear < 2101 && offset > 0) {
            daysOfYear = yearDays(lunarYear)
            offset -= daysOfYear
            lunarYear++
        }
        if (offset < 0) {
            offset += yearDays(lunarYear - 1)
            lunarYear--
        }

        val leap = leapMonth(lunarYear)
        var isLeap = false
        var lunarMonth = 1
        var daysOfMonth = 0
        while (lunarMonth < 13 && offset > 0) {
            if (leap > 0 && lunarMonth == leap + 1 && !isLeap) {
                lunarMonth--
                isLeap = true
                daysOfMonth = leapDays(lunarYear)
            } else {
                daysOfMonth = monthDays(lunarYear, lunarMonth)
            }
            if (isLeap && lunarMonth == leap + 1) isLeap = false
            offset -= daysOfMonth
            lunarMonth++
        }
        if (offset == 0 && leap > 0 && lunarMonth == leap + 1) {
            if (isLeap) {
                isLeap = false
            } else {
                isLeap = true
                lunarMonth--
            }
        }
        if (offset < 0) {
            offset += daysOfMonth
            lunarMonth--
        }
        return LunarDate(lunarYear, lunarMonth.coerceIn(1, 12), offset + 1, isLeap)
    }

    fun ganZhi(year: Int): String = TIAN_GAN[(year - 4) % 10] + DI_ZHI[(year - 4) % 12]

    fun zodiac(year: Int): String = ZODIAC[(year - 4) % 12]

    fun dayName(day: Int): String = when {
        day == 10 -> "初十"
        day == 20 -> "二十"
        day == 30 -> "三十"
        day < 10 -> "初" + DAY_UNITS[day - 1]
        day < 20 -> "十" + DAY_UNITS[day - 11]
        day < 30 -> "廿" + DAY_UNITS[day - 21]
        else -> "三十"
    }
}
