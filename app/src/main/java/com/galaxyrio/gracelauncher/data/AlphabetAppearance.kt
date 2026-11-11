package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

/** Desktop rail settings, stored alongside the rest of the list appearance. */
data class AlphabetAppearance(
    val fontId: String? = null,
    val fontColor: IconColor? = null,
    val fontSize: Int = 14,
    /**
     * 2026-11-11 新增：字母透明度（百分比 0~100，默认 85）。
     *
     * 由来：用户要求「让 A 到 Z 字母表能调节透明度」。
     *
     * 为什么默认 85 而不是 100：
     * 改前渲染层把未选中字母硬编码成 alpha=0.85f，选中字母是 1f。
     * 默认值取 85 保持原观感不变，老用户升级后看不出差别。
     * 新数值只乘在「未选中」的基准上，选中项仍固定 1f，
     * 所以选中高亮不会因为透明度调低而一起变淡。
     */
    val opacityPercent: Int = 85,
    val indicatorShape: IconShape = IconShape.Circle,
    val pebbleRoundness: Int = IconDesign.DefaultPebbleRoundness,
    val squareCornerRadius: Int = 0,
    val cookieSides: Int = 4,
    /** null anchors the bottom and uses the original 18dp step, irrespective of letter count. */
    val topPercent: Int? = null,
    val bottomPercent: Int = 85,
    val freeMovement: Boolean = true,
    /** null preserves the legacy home-screen Hide alphabet preference. */
    val showOnHome: Boolean? = null,
    val showInAppList: Boolean = true,
    val rightTouchWidth: Int = 36,
) {
    fun showsOnHome(legacyHideAlphabet: Boolean): Boolean = showOnHome ?: !legacyHideAlphabet

    fun normalized(): AlphabetAppearance {
        val top = topPercent?.coerceIn(0, 99)
        return copy(fontSize = fontSize.coerceIn(10, 28), topPercent = top,
            opacityPercent = opacityPercent.coerceIn(0, 100),
            bottomPercent = bottomPercent.coerceIn((top ?: 0) + 1, 100),
            rightTouchWidth = rightTouchWidth.coerceIn(28, 56),
            pebbleRoundness = pebbleRoundness.coerceIn(0, 100),
            squareCornerRadius = squareCornerRadius.coerceIn(0, 100),
            cookieSides = cookieSides.takeIf { it in IconDesign.CookieSides } ?: 4)
    }

    /** The caller includes the home and utility markers in entryCount. */
    internal fun layout(viewportHeightDp: Float, entryCount: Int): AlphabetRailLayout {
        if (!viewportHeightDp.isFinite() || viewportHeightDp <= 0f || entryCount <= 0) return AlphabetRailLayout(0f, 0f)
        val safe = normalized()
        val bottom = viewportHeightDp * safe.bottomPercent / 100f
        val height = safe.topPercent?.let { bottom - viewportHeightDp * it / 100f }
            // Only compress when the entries physically cannot fit above the chosen bottom.
            ?: (entryCount * DefaultStepDp).coerceAtMost(bottom)
        return AlphabetRailLayout(bottom - height, height)
    }

    internal fun json(): JSONObject = normalized().let { value -> JSONObject().apply {
        put("fontId", value.fontId ?: JSONObject.NULL); put("fontColor", value.fontColor?.json())
        put("fontSize", value.fontSize); put("indicatorShape", value.indicatorShape.name)
        put("opacityPercent", value.opacityPercent)
        put("pebbleRoundness", value.pebbleRoundness); put("squareCornerRadius", value.squareCornerRadius)
        put("cookieSides", value.cookieSides); put("topPercent", value.topPercent ?: JSONObject.NULL)
        put("bottomPercent", value.bottomPercent); put("freeMovement", value.freeMovement)
        put("showOnHome", value.showOnHome ?: JSONObject.NULL); put("showInAppList", value.showInAppList)
        put("rightTouchWidth", value.rightTouchWidth)
    } }

    companion object {
        internal const val DefaultStepDp = 18f
        internal fun decode(value: JSONObject?): AlphabetAppearance {
            val defaults = AlphabetAppearance()
            return value?.let {
                val bottom = it.optInt("bottomPercent", defaults.bottomPercent)
                // Existing numeric ranges, including the old defaults, stay unchanged.
                val top = if (!it.has("topPercent") || it.isNull("topPercent")) null else it.optInt("topPercent", 35)
                AlphabetAppearance(
                    fontId = it.optString("fontId").takeIf { id -> id.isNotBlank() && id != "null" },
                    fontColor = IconColor.decode(it.optJSONObject("fontColor")),
                    fontSize = it.optInt("fontSize", defaults.fontSize),
                    opacityPercent = it.optInt("opacityPercent", defaults.opacityPercent),
                    indicatorShape = IconShape.entries.firstOrNull { shape -> shape.name == it.optString("indicatorShape") }
                        ?: defaults.indicatorShape,
                    pebbleRoundness = it.optInt("pebbleRoundness", defaults.pebbleRoundness),
                    squareCornerRadius = it.optInt("squareCornerRadius", defaults.squareCornerRadius),
                    cookieSides = it.optInt("cookieSides", defaults.cookieSides),
                    topPercent = top,
                    bottomPercent = bottom,
                    freeMovement = it.optBoolean("freeMovement", defaults.freeMovement),
                    showOnHome = if (it.isNull("showOnHome")) null else it.optBoolean("showOnHome", true),
                    showInAppList = it.optBoolean("showInAppList", defaults.showInAppList),
                    rightTouchWidth = it.optInt("rightTouchWidth", defaults.rightTouchWidth),
                ).normalized()
            } ?: defaults
        }
    }
}

internal data class AlphabetRailLayout(val topDp: Float, val heightDp: Float)
