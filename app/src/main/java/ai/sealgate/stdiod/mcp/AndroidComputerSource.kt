package ai.sealgate.stdiod.mcp

import ai.sealgate.stdiod.BuildConfig
import ai.sealgate.stdiod.ComputerUseSettings
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** System-owned accessibility component present only in non-Play manifests. */
class ComputerAccessibilityService : AccessibilityService() {
    private val eventSequence = AtomicLong()

    @Volatile
    internal var lastEventUptimeMillis: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventUptimeMillis = SystemClock.uptimeMillis()
        eventSequence.incrementAndGet()
    }

    internal fun eventSequence(): Long = eventSequence.get()

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (active === this) active = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var active: ComputerAccessibilityService? = null

        fun isConnected(): Boolean = active != null

        fun disable() {
            active?.disableSelf()
            active = null
        }

        internal fun connectedService(): ComputerAccessibilityService? = active
    }
}

/** AccessibilityService-backed computer use for developer/private/enterprise builds. */
class AndroidComputerSource(context: Context) : ComputerSource {
    private val appContext = context.applicationContext
    private val nextObservationId = AtomicLong()
    private val uiSettler = UiSettler(
        uptimeMillis = SystemClock::uptimeMillis,
        sleepMillis = SystemClock::sleep,
        quietMillis = UI_QUIET_MILLIS,
        pollMillis = UI_POLL_MILLIS,
        timeoutMillis = UI_SETTLE_TIMEOUT_MILLIS,
    )
    private val screenshotGate = MinimumIntervalGate(
        uptimeMillis = SystemClock::uptimeMillis,
        sleepMillis = SystemClock::sleep,
        minimumIntervalMillis = SCREENSHOT_MIN_INTERVAL_MILLIS,
    )
    private val snapshots = RecentSnapshotStore<Snapshot>(MAX_RECENT_SNAPSHOTS)

    override fun status(): ComputerOperationResult {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return ComputerOperationResult(
            payload = buildJsonObject {
                put("available_in_build", JsonPrimitive(BuildConfig.COMPUTER_USE_AVAILABLE))
                put("enabled", JsonPrimitive(ComputerUseSettings.isEnabled(appContext)))
                put("accessibility_service_connected", JsonPrimitive(ComputerAccessibilityService.isConnected()))
                put("screen_interactive", JsonPrimitive(power.isInteractive))
                put("device_locked", JsonPrimitive(keyguard.isDeviceLocked))
                put("screenshot_supported", JsonPrimitive(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R))
                put("sdk_int", JsonPrimitive(Build.VERSION.SDK_INT))
            },
        )
    }

    override fun observe(): ComputerOperationResult {
        val service = availableService() ?: return unavailableResult()
        return captureObservation(service)
    }

    override fun click(nodeId: String): ComputerOperationResult = withService { service ->
        val locator = clickLocator(nodeId) ?: return@withService staleNodeFailure("click", nodeId)
        val baseline = uiStateMarker(service)
        var node = resolve(service, locator, locator.path)
        var clicked = false
        var targetBounds: Rect? = null
        while (node != null) {
            if (targetBounds == null) targetBounds = Rect().also(node::getBoundsInScreen)
            clicked = node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val parent = if (clicked) null else node.parent
            node.recycleCompat()
            node = parent
        }
        if (!clicked) {
            val bounds = targetBounds
            if (bounds != null && !bounds.isEmpty && validatePoint(service, bounds.centerX(), bounds.centerY())) {
                val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
                clicked = dispatchGesture(service, path, NODE_CLICK_FALLBACK_MILLIS)
            }
        }
        finishAction(
            service,
            "click",
            clicked,
            if (clicked) null else "node and its screen location no longer accept clicks",
            baseline,
        )
    }

    override fun setText(nodeId: String, text: String): ComputerOperationResult = withService { service ->
        val locator = locator(nodeId) ?: return@withService staleNodeFailure("set_text", nodeId)
        val node = resolve(service, locator, locator.path)
            ?: return@withService actionFailure("set_text", "node is no longer available")
        val baseline = uiStateMarker(service)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val performed = node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        node.recycleCompat()
        finishAction(service, "set_text", performed, if (performed) null else "node does not accept text", baseline)
    }

    override fun tap(x: Int, y: Int, durationMillis: Int): ComputerOperationResult = withService { service ->
        val valid = validatePoint(service, x, y) && durationMillis in 1..MAX_GESTURE_MILLIS
        if (!valid) return@withService actionFailure("tap", "coordinates or duration are out of range")
        val baseline = uiStateMarker(service)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val performed = dispatchGesture(service, path, durationMillis)
        finishAction(service, "tap", performed, if (performed) null else "Android rejected the tap gesture", baseline)
    }

    override fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Int,
    ): ComputerOperationResult = withService { service ->
        val valid = validatePoint(service, startX, startY) && validatePoint(service, endX, endY) &&
            durationMillis in 1..MAX_GESTURE_MILLIS
        if (!valid) return@withService actionFailure("swipe", "coordinates or duration are out of range")
        val baseline = uiStateMarker(service)
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val performed = dispatchGesture(service, path, durationMillis)
        finishAction(service, "swipe", performed, if (performed) null else "Android rejected the swipe gesture", baseline)
    }

    override fun globalAction(action: String): ComputerOperationResult = withService { service ->
        val actionId = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return@withService actionFailure("global", "unknown global action: $action")
        }
        val baseline = uiStateMarker(service)
        val performed = service.performGlobalAction(actionId)
        finishAction(
            service,
            "global:$action",
            performed,
            if (performed) null else "global action is unavailable",
            baseline,
        )
    }

    override fun openApp(packageName: String): ComputerOperationResult = withService { service ->
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withService actionFailure("open_app", "app is unavailable: $packageName")
        val baseline = uiStateMarker(service)
        val launched = runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(launchIntent)
        }.isSuccess
        finishAction(
            service,
            "open_app",
            launched,
            if (launched) null else "Android blocked the app launch",
            baseline,
        )
    }

    private inline fun withService(block: (ComputerAccessibilityService) -> ComputerOperationResult): ComputerOperationResult {
        val service = availableService() ?: return unavailableResult()
        return block(service)
    }

    private fun availableService(): ComputerAccessibilityService? {
        if (!BuildConfig.COMPUTER_USE_AVAILABLE || !ComputerUseSettings.isEnabled(appContext)) return null
        val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isDeviceLocked) return null
        return ComputerAccessibilityService.connectedService()
    }

    private fun unavailableResult(): ComputerOperationResult {
        val status = status().payload
        val reason = when {
            !BuildConfig.COMPUTER_USE_AVAILABLE -> "computer control is unavailable in this build"
            !ComputerUseSettings.isEnabled(appContext) -> "computer control is disabled in Mobile Tunnel"
            status["device_locked"] == JsonPrimitive(true) -> "computer control stops while the device is locked"
            status["screen_interactive"] == JsonPrimitive(false) -> "computer control stops while the screen is off"
            else -> "enable the Mobile Tunnel accessibility service in Android settings"
        }
        return ComputerOperationResult(
            payload = buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("error", JsonPrimitive(reason))
                put("status", status)
            },
            error = reason,
        )
    }

    private fun finishAction(
        service: ComputerAccessibilityService,
        action: String,
        performed: Boolean,
        error: String?,
        baseline: UiStateMarker,
    ): ComputerOperationResult {
        if (!performed) return actionFailure(action, error ?: "action was not performed")
        val settle = uiSettler.awaitPostAction(baseline) { uiStateMarker(service) }
        val observation = captureObservation(service)
        val payload = buildJsonObject {
            put("action", buildJsonObject {
                put("name", JsonPrimitive(action))
                put("performed", JsonPrimitive(true))
                put("uiSettled", JsonPrimitive(settle.settled))
                put("postActionEventObserved", JsonPrimitive(settle.postActionEventObserved))
                put("activeWindowChanged", JsonPrimitive(settle.activeWindowChanged))
                put("settleTimedOut", JsonPrimitive(!settle.settled))
            })
            observation.payload.forEach { (key, value) -> put(key, value) }
        }
        return ComputerOperationResult(
            payload = payload,
            screenshot = observation.screenshot,
            error = observation.error,
        )
    }

    private fun actionFailure(action: String, error: String) = ComputerOperationResult(
        payload = buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(error))
            put("action", buildJsonObject {
                put("name", JsonPrimitive(action))
                put("performed", JsonPrimitive(false))
                put("error", JsonPrimitive(error))
            })
        },
        error = error,
    )

    private fun staleNodeFailure(action: String, nodeId: String): ComputerOperationResult {
        val error = "stale or unknown node_id: $nodeId"
        return ComputerOperationResult(
            payload = buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("errorCode", JsonPrimitive("STALE_NODE_ID"))
                put("error", JsonPrimitive(error))
                snapshots.latestId()?.let { put("currentObservationId", JsonPrimitive(it)) }
                put("action", buildJsonObject {
                    put("name", JsonPrimitive(action))
                    put("performed", JsonPrimitive(false))
                    put("error", JsonPrimitive(error))
                })
            },
            error = error,
        )
    }

    private fun uiStateMarker(service: ComputerAccessibilityService): UiStateMarker {
        val root = service.rootInActiveWindow
        val marker = UiStateMarker(
            eventSequence = service.eventSequence(),
            lastEventUptimeMillis = service.lastEventUptimeMillis,
            packageName = root?.packageName?.toString(),
            windowId = root?.windowId,
        )
        root?.recycleCompat()
        return marker
    }

    private fun captureObservation(service: ComputerAccessibilityService): ComputerOperationResult {
        var result = captureObservationOnce(service)
        repeat(MAX_OBSERVATION_ATTEMPTS - 1) {
            if (observationIsCompleteAndConsistent(result)) return result
            waitForCurrentUiQuiet(service)
            result = captureObservationOnce(service)
        }
        return result
    }

    private fun observationIsCompleteAndConsistent(result: ComputerOperationResult): Boolean {
        val screenshot = result.payload["screenshot"] as? JsonObject
        return result.payload["consistent"] == JsonPrimitive(true) &&
            screenshot?.get("available") == JsonPrimitive(true)
    }

    private fun waitForCurrentUiQuiet(service: ComputerAccessibilityService) {
        val deadline = SystemClock.uptimeMillis() + OBSERVATION_RETRY_SETTLE_TIMEOUT_MILLIS
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - service.lastEventUptimeMillis >= UI_QUIET_MILLIS || now >= deadline) return
            SystemClock.sleep(minOf(UI_POLL_MILLIS, deadline - now))
        }
    }

    private fun captureObservationOnce(service: ComputerAccessibilityService): ComputerOperationResult {
        val observationId = "obs_${nextObservationId.incrementAndGet()}"
        val initialEventSequence = service.eventSequence()
        val treeStartedAt = SystemClock.elapsedRealtimeNanos()
        val root = service.rootInActiveWindow
            ?: return ComputerOperationResult(
                payload = errorPayload(observationId, "no interactive accessibility window is available"),
                error = "no interactive accessibility window is available",
            )
        val initialPackage = root.packageName?.toString()
        val initialWindowId = root.windowId
        val tree = captureTree(observationId, root)
        root.recycleCompat()
        val treeFinishedAt = SystemClock.elapsedRealtimeNanos()
        val screenshotStartedAt = SystemClock.elapsedRealtimeNanos()
        val screenshotResult = captureScreenshot(service)
        val screenshotFinishedAt = SystemClock.elapsedRealtimeNanos()
        val currentRoot = service.rootInActiveWindow
        val consistent = currentRoot?.packageName?.toString() == initialPackage &&
            currentRoot?.windowId == initialWindowId &&
            service.eventSequence() == initialEventSequence
        currentRoot?.recycleCompat()
        if (consistent) {
            snapshots.put(observationId, Snapshot(observationId, tree.locators))
        }

        val payload = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("observationId", JsonPrimitive(observationId))
            put("capturedAt", JsonPrimitive(Instant.now().toString()))
            put("consistent", JsonPrimitive(consistent))
            put("packageName", initialPackage?.let(::JsonPrimitive) ?: JsonNull)
            put("windowId", JsonPrimitive(initialWindowId))
            put("treeCaptureStartedNanos", JsonPrimitive(treeStartedAt))
            put("treeCaptureFinishedNanos", JsonPrimitive(treeFinishedAt))
            put("screenshotCaptureStartedNanos", JsonPrimitive(screenshotStartedAt))
            put("screenshotCaptureFinishedNanos", JsonPrimitive(screenshotFinishedAt))
            put("accessibilityTree", tree.json)
            put(
                "screenshot",
                screenshotResult.screenshot?.let { screenshot ->
                    buildJsonObject {
                        put("available", JsonPrimitive(true))
                        put("mimeType", JsonPrimitive(screenshot.mimeType))
                        put("width", JsonPrimitive(screenshot.width))
                        put("height", JsonPrimitive(screenshot.height))
                        put("encodedBytes", JsonPrimitive(screenshot.encodedBytes))
                        put("sha256", JsonPrimitive(screenshot.sha256))
                        put("downscaled", JsonPrimitive(screenshot.downscaled))
                    }
                } ?: buildJsonObject {
                    put("available", JsonPrimitive(false))
                    put("error", JsonPrimitive(screenshotResult.error ?: "screenshot unavailable"))
                },
            )
        }
        return ComputerOperationResult(
            payload = payload,
            screenshot = screenshotResult.screenshot?.let {
                ComputerScreenshot(it.base64, it.mimeType)
            },
        )
    }

    private fun errorPayload(observationId: String, error: String): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("observationId", JsonPrimitive(observationId))
        put("error", JsonPrimitive(error))
    }

    private fun captureTree(observationId: String, root: AccessibilityNodeInfo): TreeCapture {
        val nodes = mutableListOf<JsonObject>()
        val locators = linkedMapOf<String, NodeLocator>()
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(root, emptyList(), null, recycle = false))
        var estimatedBytes = 0
        var omitted = 0

        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (nodes.size >= MAX_TREE_NODES || pending.path.size > MAX_TREE_DEPTH) {
                omitted++
                if (pending.recycle) pending.node.recycleCompat()
                continue
            }
            val localId = "n${nodes.size}"
            val nodeId = "$observationId:$localId"
            val bounds = Rect().also(pending.node::getBoundsInScreen)
            val sensitive = pending.node.isPassword ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && pending.node.isAccessibilityDataSensitive)
            val nodeText = pending.node.text?.toString()?.takeUnless { sensitive }
            val nodeDescription = pending.node.contentDescription?.toString()?.takeUnless { sensitive }
            val nodeJson = buildJsonObject {
                put("id", JsonPrimitive(nodeId))
                put("parentId", pending.parentId?.let(::JsonPrimitive) ?: JsonNull)
                put("packageName", pending.node.packageName?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("className", pending.node.className?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                put("viewId", pending.node.viewIdResourceName?.let(::JsonPrimitive) ?: JsonNull)
                put("text", if (sensitive) JsonPrimitive("[REDACTED]") else nodeText?.let(::JsonPrimitive) ?: JsonNull)
                put("contentDescription", if (sensitive) JsonPrimitive("[REDACTED]") else nodeDescription?.let(::JsonPrimitive) ?: JsonNull)
                put("bounds", boundsJson(bounds))
                put("visible", JsonPrimitive(pending.node.isVisibleToUser))
                put("enabled", JsonPrimitive(pending.node.isEnabled))
                put("clickable", JsonPrimitive(pending.node.isClickable))
                put("longClickable", JsonPrimitive(pending.node.isLongClickable))
                put("editable", JsonPrimitive(pending.node.isEditable))
                put("scrollable", JsonPrimitive(pending.node.isScrollable))
                put("focusable", JsonPrimitive(pending.node.isFocusable))
                put("focused", JsonPrimitive(pending.node.isFocused))
                put("selected", JsonPrimitive(pending.node.isSelected))
                put("password", JsonPrimitive(pending.node.isPassword))
                put("actions", buildJsonArray { pending.node.actionList.mapNotNull(::actionName).forEach { add(JsonPrimitive(it)) } })
                put("childCount", JsonPrimitive(pending.node.childCount))
            }
            val nodeBytes = nodeJson.toString().toByteArray(Charsets.UTF_8).size
            if (estimatedBytes + nodeBytes > MAX_TREE_BYTES) {
                omitted += queue.size + 1
                if (pending.recycle) pending.node.recycleCompat()
                break
            }
            estimatedBytes += nodeBytes
            nodes += nodeJson
            locators[nodeId] = NodeLocator(
                path = pending.path,
                parentId = pending.parentId,
                packageName = pending.node.packageName?.toString(),
                className = pending.node.className?.toString(),
                viewId = pending.node.viewIdResourceName,
                text = nodeText,
                contentDescription = nodeDescription,
                bounds = bounds,
                clickable = pending.node.isClickable,
            )
            for (index in 0 until pending.node.childCount) {
                pending.node.getChild(index)?.let { child ->
                    queue.add(PendingNode(child, pending.path + index, nodeId, recycle = true))
                }
            }
            if (pending.recycle) pending.node.recycleCompat()
        }
        while (queue.isNotEmpty()) queue.removeFirst().let { if (it.recycle) it.node.recycleCompat() }
        return TreeCapture(
            json = buildJsonObject {
                put("rootId", nodes.firstOrNull()?.get("id") ?: JsonNull)
                put("nodes", JsonArray(nodes))
                put("nodeCount", JsonPrimitive(nodes.size))
                put("truncated", JsonPrimitive(omitted > 0))
                put("omittedNodeCount", JsonPrimitive(omitted))
                put("serializedBytes", JsonPrimitive(estimatedBytes))
            },
            locators = locators,
        )
    }

    private fun boundsJson(bounds: Rect): JsonObject = buildJsonObject {
        put("left", JsonPrimitive(bounds.left))
        put("top", JsonPrimitive(bounds.top))
        put("right", JsonPrimitive(bounds.right))
        put("bottom", JsonPrimitive(bounds.bottom))
    }

    private fun actionName(action: AccessibilityNodeInfo.AccessibilityAction): String? = when (action.id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"
        AccessibilityNodeInfo.ACTION_CUT -> "cut"
        AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        else -> action.label?.toString()?.takeIf(String::isNotBlank)
    }

    private fun locator(nodeId: String): NodeLocator? {
        val snapshot = snapshotFor(nodeId) ?: return null
        return snapshot.locators[nodeId]
    }

    private fun clickLocator(nodeId: String): NodeLocator? {
        val snapshot = snapshotFor(nodeId) ?: return null
        val requested = snapshot.locators[nodeId] ?: return null
        var candidate: NodeLocator? = requested
        while (candidate != null) {
            if (candidate.clickable) return candidate
            candidate = candidate.parentId?.let(snapshot.locators::get)
        }
        return requested
    }

    private fun snapshotFor(nodeId: String): Snapshot? {
        val observationId = nodeId.substringBefore(':', missingDelimiterValue = "")
        if (observationId.isEmpty()) return null
        return snapshots.get(observationId)
    }

    private fun resolve(
        service: ComputerAccessibilityService,
        locator: NodeLocator,
        path: List<Int>,
    ): AccessibilityNodeInfo? {
        var node = service.rootInActiveWindow ?: return null
        for (index in path) {
            val child = node.getChild(index)
            node.recycleCompat()
            node = child ?: return findMatchingNode(service, locator)
        }
        if (nodeMatches(node, locator, exactBounds = true)) return node
        node.recycleCompat()
        return findMatchingNode(service, locator)
    }

    private fun findMatchingNode(
        service: ComputerAccessibilityService,
        locator: NodeLocator,
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited++
            if (nodeMatches(node, locator, exactBounds = false)) {
                while (queue.isNotEmpty()) queue.removeFirst().recycleCompat()
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
            node.recycleCompat()
        }
        while (queue.isNotEmpty()) queue.removeFirst().recycleCompat()
        return null
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, locator: NodeLocator, exactBounds: Boolean): Boolean {
        if (node.packageName?.toString() != locator.packageName || node.className?.toString() != locator.className) {
            return false
        }
        val stableIdentityMatches = when {
            locator.viewId != null -> node.viewIdResourceName == locator.viewId
            locator.text != null -> node.text?.toString() == locator.text
            locator.contentDescription != null -> node.contentDescription?.toString() == locator.contentDescription
            else -> false
        }
        val hasStableIdentity = locator.viewId != null || locator.text != null || locator.contentDescription != null
        if (hasStableIdentity && !stableIdentityMatches) return false
        if (!hasStableIdentity && !exactBounds) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        return if (exactBounds) bounds == locator.bounds else bounds.isNear(locator.bounds)
    }

    private fun Rect.isNear(other: Rect): Boolean =
        kotlin.math.abs(left - other.left) <= NODE_BOUNDS_TOLERANCE_PIXELS &&
            kotlin.math.abs(top - other.top) <= NODE_BOUNDS_TOLERANCE_PIXELS &&
            kotlin.math.abs(right - other.right) <= NODE_BOUNDS_TOLERANCE_PIXELS &&
            kotlin.math.abs(bottom - other.bottom) <= NODE_BOUNDS_TOLERANCE_PIXELS

    private fun validatePoint(service: ComputerAccessibilityService, x: Int, y: Int): Boolean {
        val metrics = service.resources.displayMetrics
        return x in 0 until metrics.widthPixels && y in 0 until metrics.heightPixels
    }

    private fun dispatchGesture(service: ComputerAccessibilityService, path: Path, durationMillis: Int): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.toLong()))
            .build()
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        return accepted && latch.await(GESTURE_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && completed
    }

    private fun captureScreenshot(service: ComputerAccessibilityService): ScreenshotCapture {
        var result = ScreenshotCapture(error = "screenshot unavailable")
        repeat(MAX_SCREENSHOT_ATTEMPTS) { attempt ->
            screenshotGate.awaitAndMark()
            result = captureScreenshotOnce(service)
            if (result.errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return result
            if (attempt + 1 < MAX_SCREENSHOT_ATTEMPTS) SystemClock.sleep(SCREENSHOT_RETRY_DELAY_MILLIS)
        }
        return result
    }

    private fun captureScreenshotOnce(service: ComputerAccessibilityService): ScreenshotCapture {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCapture(error = "accessibility screenshots require Android 11 or newer")
        }
        val latch = CountDownLatch(1)
        var captured: Bitmap? = null
        var error: String? = null
        var failureCode: Int? = null
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            appContext.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        captured = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        if (captured == null) error = "Android returned an unreadable screenshot"
                    } finally {
                        buffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    error = screenshotError(errorCode)
                    failureCode = errorCode
                    latch.countDown()
                }
            },
        )
        if (!latch.await(SCREENSHOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            return ScreenshotCapture(error = "screenshot timed out")
        }
        val bitmap = captured ?: return ScreenshotCapture(
            error = error ?: "screenshot unavailable",
            errorCode = failureCode,
        )
        return try {
            runCatching { ScreenshotCapture(screenshot = encodeScreenshot(bitmap)) }
                .getOrElse { ScreenshotCapture(error = it.message ?: "screenshot encoding failed") }
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeScreenshot(original: Bitmap): EncodedScreenshot {
        var bitmap = original
        var downscaled = false
        var encoded = encodeJpeg(bitmap, 82)
        var quality = 82
        while (encoded.size > MAX_SCREENSHOT_BYTES && quality > 65) {
            quality -= 8
            encoded = encodeJpeg(bitmap, quality.coerceAtLeast(65))
        }
        while (encoded.size > MAX_SCREENSHOT_BYTES && maxOf(bitmap.width, bitmap.height) > MIN_LONG_EDGE) {
            val scale = (TARGET_LONG_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(0.85f)
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (bitmap !== original) bitmap.recycle()
            bitmap = scaled
            downscaled = true
            quality = 78
            encoded = encodeJpeg(bitmap, quality)
        }
        if (bitmap !== original) bitmap.recycle()
        require(encoded.size <= MAX_SCREENSHOT_BYTES) { "screenshot could not be encoded within the attachment limit" }
        return EncodedScreenshot(
            base64 = Base64.encodeToString(encoded, Base64.NO_WRAP),
            mimeType = "image/jpeg",
            width = if (downscaled) decodeDimensions(encoded).first else original.width,
            height = if (downscaled) decodeDimensions(encoded).second else original.height,
            encodedBytes = encoded.size,
            sha256 = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) },
            downscaled = downscaled,
        )
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "JPEG encoder failed" }
            output.toByteArray()
        }

    private fun decodeDimensions(encoded: ByteArray): Pair<Int, Int> {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        return options.outWidth to options.outHeight
    }

    private fun screenshotError(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility screenshot access is unavailable"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshots were requested too quickly"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "the default display is unavailable"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Android screenshot failure"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
        ) {
            "the active window contains secure content"
        } else {
            "Android screenshot failure ($errorCode)"
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val parentId: String?,
        val recycle: Boolean,
    )

    private data class NodeLocator(
        val path: List<Int>,
        val parentId: String?,
        val packageName: String?,
        val className: String?,
        val viewId: String?,
        val text: String?,
        val contentDescription: String?,
        val bounds: Rect,
        val clickable: Boolean,
    )

    private data class Snapshot(
        val observationId: String,
        val locators: Map<String, NodeLocator>,
    )

    private data class TreeCapture(
        val json: JsonObject,
        val locators: Map<String, NodeLocator>,
    )

    private data class ScreenshotCapture(
        val screenshot: EncodedScreenshot? = null,
        val error: String? = null,
        val errorCode: Int? = null,
    )

    private data class EncodedScreenshot(
        val base64: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val encodedBytes: Int,
        val sha256: String,
        val downscaled: Boolean,
    )

    companion object {
        private const val MAX_TREE_NODES = 2_000
        private const val MAX_TREE_DEPTH = 50
        private const val MAX_TREE_BYTES = 512 * 1024
        private const val MAX_SCREENSHOT_BYTES = 1536 * 1024
        private const val TARGET_LONG_EDGE = 1_600
        private const val MIN_LONG_EDGE = 1_280
        private const val MAX_GESTURE_MILLIS = 10_000
        private const val NODE_CLICK_FALLBACK_MILLIS = 80
        private const val GESTURE_CALLBACK_TIMEOUT_MILLIS = 12_000L
        private const val SCREENSHOT_TIMEOUT_MILLIS = 3_000L
        private const val SCREENSHOT_MIN_INTERVAL_MILLIS = 500L
        private const val SCREENSHOT_RETRY_DELAY_MILLIS = 250L
        private const val MAX_SCREENSHOT_ATTEMPTS = 3
        private const val MAX_OBSERVATION_ATTEMPTS = 2
        private const val OBSERVATION_RETRY_SETTLE_TIMEOUT_MILLIS = 1_500L
        private const val MAX_RECENT_SNAPSHOTS = 4
        private const val NODE_BOUNDS_TOLERANCE_PIXELS = 48
        private const val UI_QUIET_MILLIS = 400L
        private const val UI_POLL_MILLIS = 50L
        private const val UI_SETTLE_TIMEOUT_MILLIS = 3_000L
    }
}
