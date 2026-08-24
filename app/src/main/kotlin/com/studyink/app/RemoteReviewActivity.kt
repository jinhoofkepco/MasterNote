package com.studyink.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.studyink.core.model.MarkColor
import com.studyink.monitor.telegram.TelegramEnqueueResult
import com.studyink.reader.RemotePageSnapshotRef
import com.studyink.reader.RemoteReviewGradeTap
import com.studyink.reader.RemoteReviewView
import com.studyink.reader.RemoteSnapshotOpenResult
import com.studyink.reader.RemoteTeacherFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Teacher inbox and full-page vector correction editor for Telegram remote review. */
class RemoteReviewActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MasterNote-remote-review-ui").apply { isDaemon = true }
    }
    private lateinit var titleText: TextView
    private lateinit var counterText: TextView
    private lateinit var emptyText: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var reviewView: RemoteReviewView

    private var snapshots: List<IncomingRemoteSnapshot> = emptyList()
    private var currentIndex = -1
    private var currentBitmap: Bitmap? = null
    private var displayedSnapshot: IncomingRemoteSnapshot? = null
    private var loadingTransferId: String? = null
    private var publishBusy = false
    private var gradeBusy = false
    private var stalePromptTransferId: String? = null
    private var historyBlockedTransferId: String? = null

    private val refreshInbox = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            refreshSnapshotList()
            window.decorView.postDelayed(this, INBOX_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildContent())
        reviewView.onPublishRequested = ::publishFeedback
        reviewView.onGradeTap = ::requestGrade
        reviewView.onStateChanged = { renderHeader() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveSafely { finish() }
            },
        )
        refreshSnapshotList(openNewestWhenEmpty = true)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(refreshInbox)
        window.decorView.post(refreshInbox)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(refreshInbox)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        currentBitmap?.recycle()
        currentBitmap = null
        super.onDestroy()
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(COLOR_HEADER) }
        }
        header.addView(horizontalRow().apply {
            titleText = TextView(context).apply {
                text = "받은 원격 페이지"
                textSize = 18f
                setTextColor(COLOR_TEXT)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
            }
            addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallButton("연결").apply {
                setOnClickListener { leaveSafely { startActivity(Intent(this@RemoteReviewActivity, RemoteReviewSetupActivity::class.java)) } }
            })
            addView(smallButton("닫기").apply { setOnClickListener { leaveSafely { finish() } } })
        })
        header.addView(horizontalRow().apply {
            previousButton = smallButton("이전")
            counterText = TextView(context).apply {
                textSize = 14f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
            }
            nextButton = smallButton("다음")
            addView(previousButton, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(counterText, LinearLayout.LayoutParams(0, dp(42), 2f))
            addView(nextButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this)
        reviewView = RemoteReviewView(this).apply { visibility = View.GONE }
        emptyText = TextView(this).apply {
            text = "아직 받은 페이지가 없습니다.\n학생이 필기하면 약 1분 안에 여기에 나타납니다."
            textSize = 17f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        content.addView(reviewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(emptyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        previousButton.setOnClickListener { requestOpen(currentIndex + 1) }
        nextButton.setOnClickListener { requestOpen(currentIndex - 1) }
        applyWindowInsets(root, header)
        return root
    }

    private fun applyWindowInsets(root: View, header: View) {
        val headerLeft = dp(10)
        val headerTop = dp(8)
        val headerRight = dp(10)
        val headerBottom = dp(8)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            // Keep the review canvas and controls out of gesture/navigation insets, while only
            // the header consumes the status-bar inset. This remains correct under Android 15's
            // enforced edge-to-edge mode and does not double-pad the content vertically.
            root.setPadding(safe.left, 0, safe.right, safe.bottom)
            header.setPadding(headerLeft, headerTop + safe.top, headerRight, headerBottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun refreshSnapshotList(openNewestWhenEmpty: Boolean = false) {
        val next = MasterNoteRemoteReviewCoordinator.incomingSnapshots()
        snapshots = next
        val displayedTransferId = reviewView.reviewState.snapshot?.transferId
        val decision = decideRemoteSnapshotRefresh(
            displayedTransferId = displayedTransferId,
            hasUnpublishedChanges = reviewView.hasUnpublishedChanges,
            nextTransferIds = snapshots.map(IncomingRemoteSnapshot::transferId),
        )
        when (decision.action) {
            RemoteSnapshotRefreshAction.RETAIN -> {
                currentIndex = decision.index
                displayedSnapshot = snapshots.getOrNull(currentIndex)
            }
            RemoteSnapshotRefreshAction.KEEP_DIRTY_STALE -> {
                currentIndex = -1
                promptForStaleDirtySnapshot(displayedTransferId)
            }
            RemoteSnapshotRefreshAction.CLEAR -> clearDisplayedSnapshot(discardChanges = true)
            RemoteSnapshotRefreshAction.OPEN_NEWEST -> {
                if (loadingTransferId == null && (openNewestWhenEmpty || displayedTransferId != null ||
                        reviewView.reviewState.snapshot == null)
                ) {
                    clearDisplayedSnapshot(discardChanges = true)
                    openSnapshot(0, discardChanges = true)
                }
            }
        }
        renderHeader()
    }

    private fun promptForStaleDirtySnapshot(transferId: String?) {
        if (transferId == null || stalePromptTransferId == transferId || isFinishing || isDestroyed) return
        stalePromptTransferId = transferId
        AlertDialog.Builder(this)
            .setTitle("현재 첨삭 페이지가 목록에서 제외됐습니다")
            .setMessage("작성 중인 획은 화면에 유지했지만 이 연결로는 전송할 수 없습니다. 버리고 최신 페이지를 열까요?")
            .setNegativeButton("화면에 유지", null)
            .setPositiveButton("버리고 최신 열기") { _, _ ->
                clearDisplayedSnapshot(discardChanges = true)
                if (snapshots.isNotEmpty()) openSnapshot(0, discardChanges = true)
                renderHeader()
            }
            .show()
    }

    private fun clearDisplayedSnapshot(discardChanges: Boolean) {
        if (!reviewView.clearSnapshot(discardChanges)) return
        loadingTransferId = null
        currentIndex = -1
        displayedSnapshot = null
        stalePromptTransferId = null
        currentBitmap?.recycle()
        currentBitmap = null
        reviewView.visibility = View.GONE
        emptyText.text = "아직 받은 페이지가 없습니다.\n학생이 필기하면 약 1분 안에 여기에 나타납니다."
        emptyText.visibility = View.VISIBLE
    }

    private fun requestOpen(index: Int) {
        if (index !in snapshots.indices || index == currentIndex || loadingTransferId != null) return
        if (!reviewView.hasUnpublishedChanges) {
            openSnapshot(index, discardChanges = false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("보내지 않은 첨삭이 있습니다")
            .setMessage("현재 첨삭을 버리고 다른 페이지를 열까요?")
            .setNegativeButton("계속 첨삭", null)
            .setPositiveButton("버리고 이동") { _, _ -> openSnapshot(index, discardChanges = true) }
            .show()
    }

    private fun openSnapshot(index: Int, discardChanges: Boolean) {
        val snapshot = snapshots.getOrNull(index) ?: return
        if (historyBlockedTransferId == snapshot.transferId) return
        loadingTransferId = snapshot.transferId
        emptyText.visibility = View.VISIBLE
        emptyText.text = "페이지를 여는 중입니다…"
        worker.execute {
            val bitmap = decodeBoundedBitmap(snapshot)
            val feedbackState = MasterNoteRemoteReviewCoordinator
                .publishedFeedbackState(snapshot.transferId)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (loadingTransferId != snapshot.transferId) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                loadingTransferId = null
                if (feedbackState == RemotePublishedFeedbackState.HISTORY_UNAVAILABLE) {
                    bitmap?.recycle()
                    historyBlockedTransferId = snapshot.transferId
                    emptyText.text = "이 페이지의 이전 첨삭 원본이 보관 한도를 지나 안전하게 열지 않았습니다.\n원격 첨삭 연결을 새로 만든 뒤 다시 받아주세요."
                    Toast.makeText(this, "기존 첨삭을 지우지 않도록 이 페이지 편집을 막았습니다.", Toast.LENGTH_LONG).show()
                    renderHeader()
                    return@runOnUiThread
                }
                if (bitmap == null) {
                    emptyText.text = "이 페이지 이미지를 읽지 못했습니다. 다시 전송될 때까지 보관 기록은 유지됩니다."
                    Toast.makeText(this, "페이지 이미지를 읽지 못했습니다.", Toast.LENGTH_LONG).show()
                    renderHeader()
                    return@runOnUiThread
                }
                val reference = snapshot.toReaderReference()
                val initialFeedback = (feedbackState as? RemotePublishedFeedbackState.Available)?.feedback
                val result = reviewView.showSnapshot(
                    snapshot = reference,
                    bitmap = bitmap,
                    initialFeedback = initialFeedback,
                    discardUnpublishedChanges = discardChanges,
                )
                if (result == RemoteSnapshotOpenResult.REJECTED_UNPUBLISHED_CHANGES) {
                    bitmap.recycle()
                    return@runOnUiThread
                }
                val previous = currentBitmap
                currentBitmap = bitmap
                currentIndex = snapshots.indexOfFirst { it.transferId == snapshot.transferId }
                    .takeIf { it >= 0 } ?: index
                displayedSnapshot = snapshot
                stalePromptTransferId = null
                historyBlockedTransferId = null
                reviewView.visibility = View.VISIBLE
                emptyText.visibility = View.GONE
                if (previous !== bitmap) previous?.recycle()
                renderHeader()
            }
        }
    }

    private fun decodeBoundedBitmap(snapshot: IncomingRemoteSnapshot): Bitmap? {
        if (!snapshot.imageFile.isFile || snapshot.imageFile.length() !in 1..MAX_IMAGE_FILE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(snapshot.imagePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_DECODED_PIXELS) return null
        if (bounds.outWidth != snapshot.widthPx || bounds.outHeight != snapshot.heightPx) return null
        return BitmapFactory.decodeFile(snapshot.imagePath)
    }

    private fun publishFeedback(feedback: RemoteTeacherFeedback) {
        if (publishBusy) return
        publishBusy = true
        renderHeader()
        worker.execute {
            val result = runCatching {
                MasterNoteRemoteReviewCoordinator.publishTeacherFeedback(feedback)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                publishBusy = false
                result.fold(
                    onSuccess = { enqueue ->
                        if (enqueue.accepted()) {
                            reviewView.acknowledgePublished(feedback)
                            Toast.makeText(this, "첨삭을 안전하게 전송 대기열에 저장했습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, enqueue.userText(), Toast.LENGTH_LONG).show()
                        }
                    },
                    onFailure = {
                        Toast.makeText(this, "첨삭을 저장하지 못했습니다. 연결을 확인하고 다시 눌러주세요.", Toast.LENGTH_LONG).show()
                    },
                )
                renderHeader()
            }
        }
    }

    private fun requestGrade(tap: RemoteReviewGradeTap) {
        if (gradeBusy || publishBusy || displayedSnapshot?.transferId != tap.snapshot.transferId) return
        val attempt = displayedSnapshot?.attemptNo
        if (attempt == null) {
            Toast.makeText(this, "학생 풀이 회차가 포함된 새 페이지를 받은 뒤 채점해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${displayedSnapshot?.pageNumber ?: tap.snapshot.pageNumber + 1}쪽 ${attempt}회 채점")
            .setMessage("선택한 위치에 보낼 채점 표시를 고르세요.")
            .setNegativeButton("오답") { _, _ -> publishGrade(tap, MarkColor.RED) }
            .setNeutralButton("취소", null)
            .setPositiveButton("정답") { _, _ -> publishGrade(tap, MarkColor.BLUE) }
            .show()
    }

    private fun publishGrade(tap: RemoteReviewGradeTap, color: MarkColor) {
        if (gradeBusy || displayedSnapshot?.transferId != tap.snapshot.transferId) return
        gradeBusy = true
        renderHeader()
        worker.execute {
            val result = runCatching {
                MasterNoteRemoteReviewCoordinator.publishRemoteGrade(
                    snapshotTransferId = tap.snapshot.transferId,
                    anchorX = tap.anchor.x,
                    anchorY = tap.anchor.y,
                    color = color,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                gradeBusy = false
                result.fold(
                    onSuccess = { enqueue ->
                        val message = if (enqueue.accepted()) {
                            if (color == MarkColor.BLUE) "정답 표시를 전송 대기열에 저장했습니다."
                            else "오답 표시를 전송 대기열에 저장했습니다."
                        } else if (enqueue == TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED) {
                            "실시간 연결 중에는 텔 채점이 꺼집니다. 텔로 바뀐 뒤 새 페이지에서 다시 채점해주세요."
                        } else {
                            enqueue.userText()
                        }
                        Toast.makeText(
                            this,
                            message,
                            if (enqueue.accepted()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = {
                        Toast.makeText(this, "채점을 저장하지 못했습니다. 연결을 확인해주세요.", Toast.LENGTH_LONG).show()
                    },
                )
                renderHeader()
            }
        }
    }

    private fun renderHeader() {
        val current = displayedSnapshot?.takeIf { snapshot ->
            snapshot.transferId == reviewView.reviewState.snapshot?.transferId
        }
        titleText.text = current?.let { value ->
            val student = value.studentLabel?.let { "$it · " }.orEmpty()
            val attempt = value.attemptNo?.let { " · ${it}회" }.orEmpty()
            "$student${value.workbookLabel} · ${value.pageNumber}쪽$attempt"
        } ?: "받은 원격 페이지"
        counterText.text = current?.let {
            val time = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(it.receivedAtEpochMs))
            if (currentIndex in snapshots.indices) {
                "${currentIndex + 1}/${snapshots.size} · $time"
            } else {
                "보관 목록에서 제외됨 · $time"
            }
        } ?: "0/0"
        previousButton.isEnabled = currentIndex in 0 until snapshots.lastIndex && !publishBusy && !gradeBusy
        nextButton.isEnabled = currentIndex > 0 && !publishBusy && !gradeBusy
    }

    private fun leaveSafely(action: () -> Unit) {
        if (!reviewView.hasUnpublishedChanges) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("보내지 않은 첨삭이 있습니다")
            .setMessage("이 화면을 닫으면 현재 페이지의 보내지 않은 첨삭이 사라집니다.")
            .setNegativeButton("계속 첨삭", null)
            .setPositiveButton("버리고 닫기") { _, _ -> action() }
            .show()
    }

    private fun IncomingRemoteSnapshot.toReaderReference() = RemotePageSnapshotRef(
        transferId = transferId,
        pageToken = pageToken,
        bookFingerprint = pageToken,
        pageNumber = pageNumber - 1,
        studentRevision = studentRevision,
        imageWidthPx = widthPx,
        imageHeightPx = heightPx,
        receivedAtEpochMillis = receivedAtEpochMs,
    )

    private fun TelegramEnqueueResult.accepted(): Boolean = this == TelegramEnqueueResult.ENQUEUED ||
        this == TelegramEnqueueResult.ALREADY_PENDING ||
        this == TelegramEnqueueResult.ALREADY_DELIVERED

    private fun TelegramEnqueueResult.userText(): String = when (this) {
        TelegramEnqueueResult.NOT_CONFIGURED, TelegramEnqueueResult.CHAT_CHANGED ->
            "원격 첨삭 연결을 먼저 확인해주세요."
        TelegramEnqueueResult.QUEUE_FULL -> "전송 대기열이 가득 찼습니다. 잠시 뒤 다시 눌러주세요."
        TelegramEnqueueResult.PREVIOUSLY_DEAD ->
            "이 첨삭은 전송할 수 없습니다. 새 획을 추가한 뒤 다시 보내주세요."
        TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED ->
            "실시간 연결 중에는 텔 첨삭이 꺼집니다. 텔로 바뀐 뒤 다시 보내주세요."
        else -> "첨삭을 전송 대기열에 넣지 못했습니다."
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun smallButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 13f
        minWidth = dp(58)
        minHeight = dp(42)
        setTextColor(COLOR_TEXT)
        backgroundTintList = ColorStateList.valueOf(COLOR_BUTTON)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INBOX_REFRESH_MILLIS = 2_000L
        const val MAX_IMAGE_FILE_BYTES = 2L * 1024L * 1024L
        // Student renderer is capped at four megapixels; reject larger decoded allocations even
        // if a future protocol revision permits them.
        const val MAX_DECODED_PIXELS = 4_000_000L
        const val COLOR_BACKGROUND = 0xFF292C31.toInt()
        const val COLOR_HEADER = 0xFFF6F7F9.toInt()
        const val COLOR_BUTTON = 0xFFE7EBF0.toInt()
        const val COLOR_TEXT = 0xFF20252B.toInt()
        const val COLOR_MUTED = 0xFF626B76.toInt()
    }
}

internal enum class RemoteSnapshotRefreshAction { RETAIN, KEEP_DIRTY_STALE, CLEAR, OPEN_NEWEST }

internal data class RemoteSnapshotRefreshDecision(
    val action: RemoteSnapshotRefreshAction,
    val index: Int = -1,
)

internal fun decideRemoteSnapshotRefresh(
    displayedTransferId: String?,
    hasUnpublishedChanges: Boolean,
    nextTransferIds: List<String>,
): RemoteSnapshotRefreshDecision {
    if (displayedTransferId != null) {
        val retainedIndex = nextTransferIds.indexOf(displayedTransferId)
        if (retainedIndex >= 0) {
            return RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.RETAIN, retainedIndex)
        }
        if (hasUnpublishedChanges) {
            return RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.KEEP_DIRTY_STALE)
        }
    }
    return if (nextTransferIds.isEmpty()) {
        RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.CLEAR)
    } else {
        RemoteSnapshotRefreshDecision(RemoteSnapshotRefreshAction.OPEN_NEWEST, 0)
    }
}
