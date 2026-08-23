package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.feature.video.VideoCardAdapter

/**
 * 分P 视频卡片"沉浸式选择模式"：
 * - 默认隐藏
 * - 点 btn_detail 切换：进入时隐藏所有 OSD（底部导航栏 / 进度条 / 时间），分P 横滚条
 *   锚定到 player_view 底部，独占底部条带，并把焦点跳到当前分P（或第一张）卡片
 * - 退出（再点 btn_detail、BACK、DPAD_DOWN）恢复 OSD，条带隐藏
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
                        // 沉浸模式下 DPAD_DOWN 退出沉浸；普通模式尝试把焦点还给 seekbar。
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (immersivePartsMode) {
                                exitImmersivePartsMode()
                                true
                            } else {
                                binding.seekProgress.post {
                                    if (binding.seekProgress.visibility != View.VISIBLE) return@post
                                    binding.seekProgress.requestFocus()
                                }
                                true
                            }
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

    syncPartsStripButtonVisibility()
    refreshPlayerPartsStripContent()
}

/**
 * 切换沉浸式分P选择模式：
 * - off → on：隐藏 OSD，条带锚到底部，焦点跳第一张分P
 * - on  → off：恢复 OSD，条带隐藏
 * - 没有多分P → toast 提示，状态不变
 */
internal fun PlayerActivity.togglePlayerPartsStrip() {
    val cards = resolvePartsStripCards()
    if (cards.size <= 1) {
        AppToast.show(this, "当前没有分P列表")
        return
    }
    val turningOn = !immersivePartsMode
    immersivePartsMode = turningOn
    applyImmersiveVisibility(turningOn)
    if (!turningOn) return

    // 焦点跳到当前分P（或第一张）卡片。
    binding.recyclerPartsStrip.post { focusFirstPartsStripCardIfShown() }
    binding.recyclerPartsStrip.postDelayed({ focusFirstPartsStripCardIfShown() }, 120L)
}

/**
 * BACK/ESCAPE/B 路由：退出沉浸模式，不退出播放器。
 */
internal fun PlayerActivity.exitImmersivePartsMode(): Boolean {
    if (!immersivePartsMode) return false
    immersivePartsMode = false
    applyImmersiveVisibility(false)
    return true
}

/**
 * 根据 OSD 配置 + parts 状态收缩 `btn_detail`：
 * - OSD 配置未开启 → 忽略（applyOsdButtonsVisibility 处理）
 * - fetch 未完成 → 暂不收缩
 * - fetch 完成且 partsListItems.size <= 1 → 强制 GONE（避免点到空按钮）
 */
internal fun PlayerActivity.syncPartsStripButtonVisibility() {
    val osdEnabled = BiliClient.prefs.playerOsdButtons.toSet()
        .contains(AppPrefs.PLAYER_OSD_BTN_DETAIL)
    if (!osdEnabled) return
    val fetchInFlight = partsListFetchJob?.isActive == true
    val hasMultipleParts = partsListItems.size > 1
    val target = if (fetchInFlight || hasMultipleParts) View.VISIBLE else View.GONE
    if (binding.btnDetail.visibility != target) {
        binding.btnDetail.visibility = target
    }
}

/**
 * 应用沉浸模式的 OSD 显隐 + 条带约束切换。
 */
internal fun PlayerActivity.applyImmersiveVisibility(immersive: Boolean) {
    if (immersive) {
        binding.bottomBar.visibility = View.GONE
        binding.controlsRow.visibility = View.GONE
        binding.tvTime.visibility = View.GONE
        anchorPartsStripToBottomEdge()
    } else {
        // 还原约束后再让 setControlsVisible(true) 决定 OSD 显隐。
        anchorPartsStripAboveBottomBar()
        setControlsVisible(true)
    }
    refreshPlayerPartsStripContent()
}

private fun PlayerActivity.anchorPartsStripToBottomEdge() {
    val lp = binding.partsStripPanel.layoutParams as? ConstraintLayout.LayoutParams ?: return
    lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
    lp.bottomToBottom = R.id.player_view
    binding.partsStripPanel.layoutParams = lp
    binding.partsStripPanel.requestLayout()
}

private fun PlayerActivity.anchorPartsStripAboveBottomBar() {
    val lp = binding.partsStripPanel.layoutParams as? ConstraintLayout.LayoutParams ?: return
    lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
    lp.bottomToTop = R.id.bottom_bar
    binding.partsStripPanel.layoutParams = lp
    binding.partsStripPanel.requestLayout()
}

/**
 * Called by `notifyPartsListPanelChanged` (and any time parts are mutated).
 * 沉浸模式下条带只看 `immersivePartsMode + parts.size>1`，不再受 bottom_bar 显隐左右。
 */
internal fun PlayerActivity.refreshPlayerPartsStripContent() {
    syncPartsStripButtonVisibility()
    val cards = resolvePartsStripCards()

    // 沉浸模式下强行显示（条带已锚到 player 底部）；普通模式下沿用旧规则。
    val shouldShow = if (immersivePartsMode) {
        cards.size > 1
    } else {
        val safeBottomVisible = binding.bottomBar.visibility == View.VISIBLE
        cards.size > 1 && safeBottomVisible
    }
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

private fun PlayerActivity.focusFirstPartsStripCardIfShown() {
    if (binding.partsStripPanel.visibility != View.VISIBLE) return
    if (binding.recyclerPartsStrip.adapter?.itemCount.orZero() <= 0) return
    val first = binding.recyclerPartsStrip.getChildAt(0) ?: return
    if (!first.isFocusable || first.visibility != View.VISIBLE) return
    first.requestFocus()
}

private fun Int?.orZero(): Int = this ?: 0

/**
 * Display position (in the possibly-reversed strip) of the currently playing part.
 */
private fun PlayerActivity.currentPartsStripDisplayIndex(): Int {
    val count = partsListItems.size
    if (count <= 0 || partsListIndex !in 0 until count) return -1
    return if (partsOrderReversed) count - 1 - partsListIndex else partsListIndex
}

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
