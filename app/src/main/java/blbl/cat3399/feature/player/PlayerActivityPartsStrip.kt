package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.feature.video.VideoCardAdapter

/**
 * 分P 视频卡片横滚条：
 * - 在播放页底部导航栏之上显示分P (multi-part) 视频卡片
 * - 原 `btn_detail`(第 8 个按钮)不再跳转视频详情页，而是聚焦本条带
 * - 可显示「分P (N) 正序/倒序」头部并切换顺序
 */
internal fun PlayerActivity.initPlayerPartsStrip() {
    binding.recyclerPartsStrip.layoutManager =
        LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    binding.recyclerPartsStrip.itemAnimator = null
    binding.recyclerPartsStrip.adapter =
        VideoCardAdapter(
            onClick = { _, position -> playPartsStripPosition(position) },
            onLongClick = null,
            fixedItemWidthDimenRes = R.dimen.player_parts_strip_card_width,
            fixedItemMarginDimenRes = R.dimen.player_parts_strip_card_margin,
            isSelected = { _, position -> position == currentPartsStripDisplayIndex() },
        )

    binding.recyclerPartsStrip.addOnChildAttachStateChangeListener(
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        // From the strip go DOWN to seekbar; LEFT/RIGHT stay on cards.
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            binding.seekProgress.post {
                                if (binding.seekProgress.visibility != View.VISIBLE) return@post
                                binding.seekProgress.requestFocus()
                            }
                            true
                        }
                        else -> false
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                view.setOnKeyListener(null)
            }
        },
    )

    binding.btnPartsStripOrder.setOnClickListener {
        partsOrderReversed = !partsOrderReversed
        refreshPlayerPartsStripContent()
    }

    refreshPlayerPartsStripContent()
}

/**
 * Called by `notifyPartsListPanelChanged` (and any time parts are mutated).
 * Re-evaluates visibility against the current `bottom_bar`/OSD state and
 * repopulates the strip with the current parts.
 *
 * The strip is hidden by default — `partsStripEnabled` is only flipped to
 * true via the `btn_detail` click (togglePlayerPartsStrip). This avoids
 * surprise UI on first entry.
 */
internal fun PlayerActivity.refreshPlayerPartsStripContent() {
    val safeBottomVisible = binding.bottomBar.visibility == View.VISIBLE
    val cards = resolvePartsStripCards()

    // The strip is meaningful only with multiple parts; a single part is still
    // the current video so showing a one-card rail is wasted space. Stay hidden
    // when the user hasn't opted in via btn_detail.
    val shouldShow = partsStripEnabled && safeBottomVisible && cards.size > 1
    binding.partsStripPanel.visibility = if (shouldShow) View.VISIBLE else View.GONE
    if (!shouldShow) return

    val headerCount = cards.size
    binding.tvPartsStripTitle.text =
        if (partsOrderReversed) "分P ($headerCount) 倒序" else "分P ($headerCount)"
    binding.btnPartsStripOrder.visibility = if (headerCount > 1) View.VISIBLE else View.GONE
    binding.btnPartsStripOrder.text =
        if (partsOrderReversed) "倒序" else "正序"

    (binding.recyclerPartsStrip.adapter as? VideoCardAdapter)?.submit(cards)

    val displayIndex = currentPartsStripDisplayIndex()
        .takeIf { it in cards.indices }
        ?: 0
    binding.recyclerPartsStrip.scrollToPosition(displayIndex)
}

/**
 * Display position (in the possibly-reversed strip) of the currently playing part.
 */
private fun PlayerActivity.currentPartsStripDisplayIndex(): Int {
    val count = partsListItems.size
    if (count <= 0 || partsListIndex !in 0 until count) return -1
    return if (partsOrderReversed) count - 1 - partsListIndex else partsListIndex
}

/**
 * Map a display position back to the original partsList index, then play it.
 */
private fun PlayerActivity.playPartsStripPosition(position: Int) {
    val count = partsListItems.size
    if (position !in 0 until count) return
    val originalIndex = if (partsOrderReversed) count - 1 - position else position
    playPartsListIndex(originalIndex)
}

private fun PlayerActivity.resolvePartsStripCards(): List<VideoCard> {
    if (partsListItems.isEmpty()) return emptyList()
    val base: List<VideoCard> =
        if (partsListUiCards.isNotEmpty() && partsListUiCards.size == partsListItems.size) {
            partsListUiCards
        } else {
            partsListItems.mapIndexed { index, item ->
                VideoCard(
                    bvid = item.bvid,
                    cid = item.cid,
                    aid = item.aid,
                    epId = item.epId,
                    title = item.title?.trim().takeUnless { it.isNullOrBlank() } ?: "视频 ${index + 1}",
                    coverUrl = "",
                    durationSec = 0,
                    ownerName = "",
                    ownerFace = null,
                    ownerMid = null,
                    view = null,
                    danmaku = null,
                    pubDate = null,
                    pubDateText = null,
                )
            }
        }
    return if (partsOrderReversed) base.asReversed() else base
}

/**
 * Toggle the parts strip. Used by the "btn_detail" 8th-button click:
 * - off → on: focus the currently playing card (default hidden -> now shown)
 * - on  → off: hide
 * - no parts / only 1 part: shows a toast, leaves state untouched.
 */
internal fun PlayerActivity.togglePlayerPartsStrip() {
    val cards = resolvePartsStripCards()
    if (cards.size <= 1) {
        AppToast.show(this, "当前没有分P列表")
        return
    }
    partsStripEnabled = !partsStripEnabled
    // Make sure the bottom_bar is visible so the strip can render.
    if (partsStripEnabled && binding.bottomBar.visibility != View.VISIBLE) {
        // setControlsVisible(true) refreshes the strip; the caller also calls it.
    }
    refreshPlayerPartsStripContent()
    if (!partsStripEnabled) return

    // Focus the currently playing card (or first) once the refresh has settled.
    val targetIndex = currentPartsStripDisplayIndex().takeIf { it in 0 until cards.size } ?: 0
    binding.recyclerPartsStrip.post {
        if (binding.partsStripPanel.visibility != View.VISIBLE) return@post
        val holder = binding.recyclerPartsStrip.findViewHolderForAdapterPosition(targetIndex)
        val target =
            holder?.itemView?.takeIf { it.isFocusable && it.visibility == View.VISIBLE }
                ?: binding.recyclerPartsStrip.getChildAt(0)
                ?: return@post
        target.requestFocus()
    }
}
