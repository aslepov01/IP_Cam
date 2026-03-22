package com.ipcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

/**
 * CameraService - Single Source of Truth for Camera Operations
 * 
 * This service manages camera operations, HTTP/RTSP streaming, and provides a unified interface
 * for both the MainActivity UI and web clients.
 * 
 * LIFECYCLE MANAGEMENT:
 * ====================
 * 
 * Service Lifecycle:
 * ------------------
 * - Implements LifecycleOwner with custom LifecycleRegistry
 * - Uses foreground service to persist across Activity lifecycle changes
 * - START_STICKY ensures automatic restart after system kills
 * - onTaskRemoved() schedules restart when app is swiped away
 * 
 * Callback Management:
 * -------------------
 * - Three callbacks communicate with MainActivity:
 *   1. onFrameAvailableCallback: Delivers preview frames (bitmaps)
 *   2. onCameraStateChangedCallback: Notifies of camera/settings changes
 *   3. onConnectionsChangedCallback: Updates connection counts
 * 
 * - Callbacks are @Volatile to ensure visibility across threads
 * - Activity callbacks are owner-scoped so a stale Activity instance cannot clear a newer one
 * - All callback invocations use safe wrappers (safeInvokeXxxCallback) that:
 *   * Check service lifecycle state (skip if DESTROYED)
 *   * Handle null callbacks gracefully
 *   * Recycle bitmaps if callback can't be invoked
 * 
 * Coroutine Management:
 * --------------------
 * - serviceScope: Custom CoroutineScope for long-running operations
 *   * Uses SupervisorJob to isolate failures
 *   * Persists across Activity lifecycle changes
 *   * Cancelled in onDestroy() AFTER all other cleanup
 * 
 * - Coroutines tied to service lifecycle, NOT Activity lifecycle
 * - This ensures camera continues running when MainActivity is destroyed
 * - All coroutines check lifecycle state before critical operations
 * 
 * Executor Management:
 * -------------------
 * - cameraExecutor: Single thread for CameraX analysis callbacks
 * - processingExecutor: 2-thread pool for image processing (rotation, JPEG encoding)
 * 
 * - All executors shut down in onDestroy() with proper cleanup:
 *   1. Camera executor (stops frame capture)
 *   2. Processing executor (waits up to 2s, then forces shutdown)
 *   3. Streaming executor (immediate forceful shutdown)
 * 
 * Resource Cleanup:
 * ----------------
 * - onDestroy() cleanup order:
 *   1. Set lifecycle to DESTROYED (stops new callback invocations)
 *   2. Clear callbacks (break reference cycles)
 *   3. Unregister receivers, stop HTTP/RTSP servers
 *   4. Shutdown executors (camera → processing → streaming)
 *   5. Clear bitmap pool and frame references
 *   6. Cancel coroutine scope
 *   7. Release wake locks
 * 
 * Thread Safety:
 * -------------
 * - Callback fields marked @Volatile for thread-safe access
 * - Safe callback wrappers check lifecycle state atomically
 * - Bitmap pool and frame data protected by synchronized locks
 * 
 * Design Rationale:
 * ----------------
 * We use a custom LifecycleOwner instead of LifecycleService because:
 * 1. Service must persist across Activity destroy/recreate cycles
 * 2. Camera operations are independent of MainActivity lifecycle
 * 3. Web clients can stream even when MainActivity is destroyed
 * 4. Custom lifecycle gives precise control over resource management
 * 
 * API Level: Minimum API 30 (Android 11+)
 * - No need for pre-API-30 compatibility checks
 * - Uses modern Android lifecycle and camera APIs
 */
class CameraService : Service(), LifecycleOwner, CameraServiceInterface {
    
    /**
     * ProcessedFrame - Atomic container for frame data
     * 
     * Ensures UI (Bitmap) and web stream (JPEG) always display the same frame by
     * bundling them together with their timestamp. This prevents synchronization
     * issues where the UI and web clients could show frames from different moments.
     * 
     * Using AtomicReference for lock-free, thread-safe updates that guarantee
     * atomicity across all three fields simultaneously.
     */
    private data class ProcessedFrame(
        val bitmap: Bitmap,      // For MainActivity preview
        val jpegBytes: ByteArray, // Pre-compressed for HTTP streaming
        val timestamp: Long       // Capture timestamp for staleness detection
    ) {
        // Override equals/hashCode to handle ByteArray properly
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as ProcessedFrame
            
            if (bitmap != other.bitmap) return false
            if (!jpegBytes.contentEquals(other.jpegBytes)) return false
            if (timestamp != other.timestamp) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = bitmap.hashCode()
            result = 31 * result + jpegBytes.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    private val binder = LocalBinder()
    @Volatile private var httpServer: HttpServer? = null
    @Volatile private var selectedCameraId: String? = null
    
    // Track if service is stopping due to missing permissions or other fatal errors
    @Volatile private var isStopping = false
    
    // ATOMIC FRAME UPDATES: Single source of truth for frame data
    // AtomicReference ensures the UI and web stream always display the same frame
    // by atomically updating bitmap, JPEG bytes, and timestamp together.
    // This eliminates race conditions from separate bitmapLock and jpegLock.
    private val lastProcessedFrame = AtomicReference<ProcessedFrame?>(null)
    
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    // Dedicated executor for expensive image processing (rotation, annotation, JPEG compression)
    // This prevents blocking the CameraX analysis thread
    private val processingExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ImageProcessing-${System.currentTimeMillis()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1 // Slightly lower priority than camera thread
        }
    }
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var selectedResolution: Size? = null
    // H.264 encoder for RTSP streaming (parallel pipeline)
    private var h264Encoder: H264PreviewEncoder? = null
    private var videoCaptureUseCase: androidx.camera.core.Preview? = null // For H.264 encoding
    
    // MJPEG frame throttling
    @Volatile private var lastMjpegFrameProcessedTimeMs: Long = 0
    private val resolutionByCameraId = mutableMapOf<String, Size>()
    
    /**
     * CachedCameraCharacteristics - Stores hardware capabilities for a camera
     * 
     * Caching camera characteristics avoids repeated expensive IPC calls to CameraManager.
     * These characteristics are static hardware properties that don't change at runtime.
     * All characteristics are queried once per camera during service initialization.
     */
    private data class CachedCameraCharacteristics(
        val cameraId: String,
        val ordinal: Int,
        val lensFacing: Int, // CameraCharacteristics.LENS_FACING_BACK or LENS_FACING_FRONT
        val hasFlash: Boolean,
        val yuvOutputResolutions: List<Size>,
        val previewOutputResolutions: List<Size>,
        val supportedResolutions: List<Size>,
        val hardwareFingerprint: String
    )

    private data class RawCameraGroup(
        val groupKey: String,
        val fingerprint: String,
        val lensFacing: Int,
        val candidates: List<CachedCameraCharacteristics>
    )

private enum class CameraCandidateState {
    UNKNOWN,
    WORKING,
    BROKEN
}

private enum class TorchCapabilityState {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE
}

private enum class TorchVerificationResult {
    AVAILABLE,
    UNAVAILABLE,
    INCONCLUSIVE
}
    
    // Cache camera characteristics to avoid repeated IPC calls
    // Key: camera ID, Value: cached characteristics
    private val cameraCharacteristicsCache = mutableMapOf<String, CachedCameraCharacteristics>()
    @Volatile private var cameraCharacteristicsCacheInitialized = false
    private val cameraCatalogLock = Any()
    private var cameraGroups = emptyList<RawCameraGroup>()
    private val cameraGroupKeyByRawId = mutableMapOf<String, String>()
    private val representativeCameraIdByGroupKey = mutableMapOf<String, String>()
    private val cameraCandidateStateById = mutableMapOf<String, CameraCandidateState>()
    private val torchCapabilityByGroupKey = mutableMapOf<String, TorchCapabilityState>()
    @Volatile private var cameraCatalogVersion = 0
    private var lastPublishedCameraCatalogSignature = emptyList<String>()
    private var torchVerificationJob: Job? = null
    private var boundCameraStartupTimeoutJob: Job? = null
    @Volatile private var boundCameraGroupKey: String? = null
    @Volatile private var boundCameraId: String? = null
    @Volatile private var awaitingFirstFrameForBoundCamera: Boolean = false
    @Volatile private var lastKnownGoodCameraId: String? = null
    @Volatile private var desiredTorchEnabled: Boolean = false
    @Volatile private var effectiveTorchEnabled: Boolean = false
    @Volatile private var effectiveTorchOwnerCameraId: String? = null

    private fun lensFacingToLabel(lensFacing: Int): String {
        return when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
    }

    private fun lensFacingDisplayName(lensFacing: Int): String {
        return when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Camera"
        }
    }

    private fun cameraFacingPriority(lensFacing: Int): Int {
        return when (lensFacing) {
            CameraCharacteristics.LENS_FACING_BACK -> 0
            CameraCharacteristics.LENS_FACING_FRONT -> 1
            CameraCharacteristics.LENS_FACING_EXTERNAL -> 2
            else -> 3
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun getBindableCameraIds(): Set<String>? {
        val provider = cameraProvider ?: return null
        return provider.availableCameraInfos.mapNotNull { cameraInfo ->
            runCatching { Camera2CameraInfo.from(cameraInfo).cameraId }.getOrNull()
        }.toSet()
    }

    private fun getBindableCameraDescriptors(): List<CachedCameraCharacteristics> {
        val bindableIds = getBindableCameraIds()
        return cameraCharacteristicsCache.values
            .asSequence()
            .filter { bindableIds == null || it.cameraId in bindableIds }
            .sortedWith(
                compareBy<CachedCameraCharacteristics>(
                    { cameraFacingPriority(it.lensFacing) },
                    { it.ordinal }
                )
            )
            .toList()
    }

    private fun buildCameraOptions(): List<CameraOption> {
        val groups = getVisibleCameraGroups()
        val groupSizes = groups.groupingBy { it.lensFacing }.eachCount()
        val groupIndexes = mutableMapOf<Int, Int>()

        return groups.map { group ->
            val descriptor = getRepresentativeCameraDescriptor(group) ?: group.candidates.first()
            val index = groupIndexes.getOrDefault(descriptor.lensFacing, 0) + 1
            groupIndexes[descriptor.lensFacing] = index
            val baseLabel = lensFacingDisplayName(descriptor.lensFacing)
            val displayName = if ((groupSizes[descriptor.lensFacing] ?: 0) > 1) {
                "$baseLabel $index (ID ${descriptor.cameraId})"
            } else {
                "$baseLabel (ID ${descriptor.cameraId})"
            }
            CameraOption(
                cameraId = descriptor.cameraId,
                displayName = displayName,
                facing = lensFacingToLabel(descriptor.lensFacing),
                hasFlash = getTorchCapabilityState(group.groupKey) == TorchCapabilityState.AVAILABLE
            )
        }
    }

    private fun getDefaultCameraId(): String? {
        return getVisibleCameraGroups().firstNotNullOfOrNull { getRepresentativeCameraDescriptor(it)?.cameraId }
            ?: getBindableCameraDescriptors().firstOrNull()?.cameraId
            ?: cameraCharacteristicsCache.values.minByOrNull { it.ordinal }?.cameraId
    }

    private fun getDefaultCameraIdForFacing(lensFacing: Int): String? {
        val visibleMatch = getVisibleCameraGroups()
            .filter { it.lensFacing == lensFacing }
            .firstNotNullOfOrNull { getRepresentativeCameraDescriptor(it)?.cameraId }
        if (visibleMatch != null) {
            return visibleMatch
        }

        return getBindableCameraDescriptors()
            .filter { it.lensFacing == lensFacing }
            .minByOrNull { it.ordinal }
            ?.cameraId
    }

    private fun getSelectedCameraDescriptor(): CachedCameraCharacteristics? {
        val selectedId = ensureSelectedCameraId() ?: return null
        return cameraCharacteristicsCache[selectedId]
    }

    private fun getCameraGroups(): List<RawCameraGroup> {
        synchronized(cameraCatalogLock) {
            return cameraGroups.toList()
        }
    }

    private fun getVisibleCameraGroups(): List<RawCameraGroup> {
        synchronized(cameraCatalogLock) {
            return cameraGroups.filter { group ->
                group.candidates.any { candidate ->
                    cameraCandidateStateById[candidate.cameraId] != CameraCandidateState.BROKEN
                }
            }
        }
    }

    private fun buildCameraGroupKey(fingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(fingerprint.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun getTorchCapabilityState(groupKey: String?): TorchCapabilityState {
        if (groupKey == null) {
            return TorchCapabilityState.UNAVAILABLE
        }
        synchronized(cameraCatalogLock) {
            return torchCapabilityByGroupKey[groupKey] ?: TorchCapabilityState.UNAVAILABLE
        }
    }

    private fun isTorchAvailableForGroup(groupKey: String?): Boolean {
        return getTorchCapabilityState(groupKey) == TorchCapabilityState.AVAILABLE
    }

    private fun getCameraGroupForRawId(cameraId: String?): RawCameraGroup? {
        val resolvedGroupKey = synchronized(cameraCatalogLock) {
            if (cameraId == null) {
                null
            } else {
                cameraGroupKeyByRawId[cameraId]
            }
        } ?: return null

        return getCameraGroups().firstOrNull { it.groupKey == resolvedGroupKey }
    }

    private fun getRepresentativeCameraDescriptor(group: RawCameraGroup): CachedCameraCharacteristics? {
        val representativeId = synchronized(cameraCatalogLock) {
            representativeCameraIdByGroupKey[group.groupKey]
        }
        return group.candidates.firstOrNull { it.cameraId == representativeId }
            ?: group.candidates.firstOrNull()
    }

    private fun chooseRepresentativeCameraIdLocked(
        group: RawCameraGroup,
        preferredCameraId: String? = null
    ): String {
        fun candidateIfUsable(cameraId: String?): CachedCameraCharacteristics? {
            if (cameraId == null) {
                return null
            }
            return group.candidates.firstOrNull { it.cameraId == cameraId }
                ?.takeIf { cameraCandidateStateById[it.cameraId] != CameraCandidateState.BROKEN }
        }

        candidateIfUsable(preferredCameraId)?.let { return it.cameraId }
        candidateIfUsable(selectedCameraId)?.let { return it.cameraId }
        candidateIfUsable(lastKnownGoodCameraId)?.let { return it.cameraId }

        val rankedCandidates = group.candidates.sortedWith(
            compareBy<CachedCameraCharacteristics>(
                {
                    when (cameraCandidateStateById[it.cameraId] ?: CameraCandidateState.UNKNOWN) {
                        CameraCandidateState.WORKING -> 0
                        CameraCandidateState.UNKNOWN -> 1
                        CameraCandidateState.BROKEN -> 2
                    }
                },
                { it.ordinal }
            )
        )
        return rankedCandidates.first().cameraId
    }

    private fun publishCameraCatalogIfChanged(reason: String): Boolean {
        val nextSignature = synchronized(cameraCatalogLock) {
            cameraGroups.mapNotNull { group ->
                val representative = getRepresentativeCameraDescriptor(group) ?: return@mapNotNull null
                val candidateStates = group.candidates.joinToString(",") { candidate ->
                    "${candidate.cameraId}:${cameraCandidateStateById[candidate.cameraId] ?: CameraCandidateState.UNKNOWN}"
                }
                "${group.groupKey}|${representative.cameraId}|${torchCapabilityByGroupKey[group.groupKey] ?: TorchCapabilityState.UNAVAILABLE}|$candidateStates"
            }
        }

        if (nextSignature == lastPublishedCameraCatalogSignature) {
            return false
        }

        lastPublishedCameraCatalogSignature = nextSignature
        cameraCatalogVersion += 1
        Log.i(TAG, "Camera catalog updated ($reason): ${buildCameraOptions().joinToString { "${it.displayName}[flash=${it.hasFlash}]" }}")
        return true
    }

    private fun normalizeResolutionMappingsForCameraGroups(groups: List<RawCameraGroup>) {
        groups.forEach { group ->
            val representativeId = getRepresentativeCameraDescriptor(group)?.cameraId ?: return@forEach
            if (resolutionByCameraId[representativeId] == null) {
                val migratedResolution = group.candidates
                    .asSequence()
                    .mapNotNull { candidate -> resolutionByCameraId[candidate.cameraId] }
                    .firstOrNull()
                if (migratedResolution != null) {
                    resolutionByCameraId[representativeId] = migratedResolution
                }
            }

            group.candidates
                .map { it.cameraId }
                .filter { it != representativeId }
                .forEach { aliasId -> resolutionByCameraId.remove(aliasId) }
        }
    }

    private fun rebuildCameraCatalog(reason: String) {
        if (!cameraCharacteristicsCacheInitialized) {
            return
        }

        val groups = getBindableCameraDescriptors()
            .groupBy { it.hardwareFingerprint }
            .values
            .map { candidates ->
                val sortedCandidates = candidates.sortedBy { it.ordinal }
                RawCameraGroup(
                    groupKey = buildCameraGroupKey(sortedCandidates.first().hardwareFingerprint),
                    fingerprint = sortedCandidates.first().hardwareFingerprint,
                    lensFacing = sortedCandidates.first().lensFacing,
                    candidates = sortedCandidates
                )
            }
            .sortedWith(
                compareBy<RawCameraGroup>(
                    { cameraFacingPriority(it.lensFacing) },
                    { it.candidates.first().ordinal }
                )
            )

        synchronized(cameraCatalogLock) {
            cameraGroups = groups
            cameraGroupKeyByRawId.clear()
            groups.forEach { group ->
                group.candidates.forEach { candidate ->
                    cameraGroupKeyByRawId[candidate.cameraId] = group.groupKey
                    cameraCandidateStateById.putIfAbsent(candidate.cameraId, CameraCandidateState.UNKNOWN)
                }
            }

            val validCameraIds = groups.flatMapTo(mutableSetOf()) { group -> group.candidates.map { it.cameraId } }
            cameraCandidateStateById.keys.retainAll(validCameraIds)

            val validGroupKeys = groups.mapTo(mutableSetOf()) { it.groupKey }
            representativeCameraIdByGroupKey.keys.retainAll(validGroupKeys)
            torchCapabilityByGroupKey.keys.retainAll(validGroupKeys)

            groups.forEach { group ->
                representativeCameraIdByGroupKey[group.groupKey] = chooseRepresentativeCameraIdLocked(
                    group,
                    preferredCameraId = representativeCameraIdByGroupKey[group.groupKey]
                )
                torchCapabilityByGroupKey[group.groupKey] = when {
                    group.lensFacing != CameraCharacteristics.LENS_FACING_BACK -> TorchCapabilityState.UNAVAILABLE
                    else -> torchCapabilityByGroupKey[group.groupKey] ?: TorchCapabilityState.UNKNOWN
                }
            }
        }

        normalizeResolutionMappingsForCameraGroups(groups)

        val selectedId = ensureSelectedCameraId()
        if (selectedId != null && selectedResolution == null) {
            selectedResolution = resolutionByCameraId[selectedId]
        }

        val changed = publishCameraCatalogIfChanged(reason)
        if (changed) {
            saveSettings()
        }
        broadcastCameraState()
        safeInvokeCameraStateCallback()
    }

    private fun resolveRepresentativeCameraId(cameraId: String?): String? {
        if (cameraId == null) {
            return null
        }

        synchronized(cameraCatalogLock) {
            val groupKey = cameraGroupKeyByRawId[cameraId] ?: return if (cameraCharacteristicsCache.containsKey(cameraId)) cameraId else null
            val group = cameraGroups.firstOrNull { it.groupKey == groupKey } ?: return null
            val representativeId = representativeCameraIdByGroupKey[groupKey]
                ?: chooseRepresentativeCameraIdLocked(group).also { representativeCameraIdByGroupKey[groupKey] = it }
            return representativeId
        }
    }

    private fun ensureSelectedCameraId(): String? {
        val currentId = selectedCameraId
        val resolvedCurrentId = resolveRepresentativeCameraId(currentId)
        if (resolvedCurrentId != null) {
            if (selectedCameraId != resolvedCurrentId) {
                selectedCameraId = resolvedCurrentId
                selectedResolution = resolutionByCameraId[resolvedCurrentId]
            }
            return resolvedCurrentId
        }

        val fallbackId = resolveRepresentativeCameraId(lastKnownGoodCameraId)
            ?: getDefaultCameraId()
        selectedCameraId = fallbackId
        selectedResolution = fallbackId?.let { resolutionByCameraId[it] }
        return fallbackId
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildCameraSelector(cameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { cameraInfo ->
                    runCatching { Camera2CameraInfo.from(cameraInfo).cameraId == cameraId }
                        .getOrDefault(false)
                }
            }
            .build()
    }

    private fun formatFloatKey(value: Float?): String {
        return if (value == null) "unknown" else String.format(Locale.US, "%.3f", value)
    }

    private fun buildCameraFingerprint(
        lensFacing: Int,
        hasFlash: Boolean,
        focalLengths: FloatArray?,
        sensorPhysicalSize: android.util.SizeF?,
        pixelArraySize: android.util.Size?,
        capabilities: IntArray?,
        supportedResolutions: List<Size>
    ): String {
        val focalLengthsKey = focalLengths
            ?.sorted()
            ?.joinToString("|") { formatFloatKey(it) }
            ?: "none"
        val physicalSizeKey = sensorPhysicalSize?.let { "${formatFloatKey(it.width)}x${formatFloatKey(it.height)}" } ?: "unknown"
        val pixelArrayKey = pixelArraySize?.let { "${it.width}x${it.height}" } ?: "unknown"
        val capabilitiesKey = capabilities
            ?.sorted()
            ?.joinToString("|")
            ?: "none"
        val supportedResolutionsKey = supportedResolutions.joinToString("|") { "${it.width}x${it.height}" }

        return listOf(
            lensFacing.toString(),
            hasFlash.toString(),
            focalLengthsKey,
            physicalSizeKey,
            pixelArrayKey,
            capabilitiesKey,
            supportedResolutionsKey
        ).joinToString("#")
    }

    private fun buildImageAnalysisResolutionSelector(targetResolution: Size): androidx.camera.core.resolutionselector.ResolutionSelector {
        return androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionFilter { supportedSizes, _ ->
                val exactMatch = supportedSizes.filter { size ->
                    size.width == targetResolution.width && size.height == targetResolution.height
                }

                if (exactMatch.isNotEmpty()) {
                    exactMatch
                } else {
                    val targetPixels = targetResolution.width * targetResolution.height
                    val targetAspectRatio = targetResolution.width.toFloat() / targetResolution.height.toFloat()

                    val closest = supportedSizes.minByOrNull { size ->
                        val pixels = size.width * size.height
                        val aspectRatio = size.width.toFloat() / size.height.toFloat()
                        val pixelDiff = kotlin.math.abs(pixels - targetPixels)
                        val aspectDiff = kotlin.math.abs(aspectRatio - targetAspectRatio) * 1_000_000
                        pixelDiff + aspectDiff.toInt()
                    }

                    closest?.let { listOf(it) } ?: supportedSizes
                }
            }
            .build()
    }

    private fun initializeCameraProvider(onReady: (() -> Unit)? = null) {
        cameraProvider?.let {
            onReady?.let { callback ->
                ContextCompat.getMainExecutor(this).execute { callback() }
            }
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.i(TAG, "Camera provider initialized successfully")
                onReady?.let { callback ->
                    ContextCompat.getMainExecutor(this).execute { callback() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                cameraProvider = null
            }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun maybeVerifySelectedCameraTorchCapability(reason: String) {
        val selectedId = ensureSelectedCameraId() ?: return
        val selectedGroup = getCameraGroupForRawId(selectedId) ?: return
        val descriptor = cameraCharacteristicsCache[selectedId] ?: return

        if (descriptor.lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
            updateTorchCapability(selectedGroup.groupKey, TorchCapabilityState.UNAVAILABLE, "front/external camera selected")
            return
        }

        if (getTorchCapabilityState(selectedGroup.groupKey) != TorchCapabilityState.UNKNOWN) {
            return
        }

        if (torchVerificationJob?.isActive == true) {
            return
        }

        val boundCamera = camera ?: return
        val boundId = boundCameraId ?: return
        torchVerificationJob = serviceScope.launch {
            when (verifyTorchCapabilityWhileActive(selectedGroup.groupKey, boundId, boundCamera, reason)) {
                TorchVerificationResult.AVAILABLE -> {
                    updateTorchCapability(
                        selectedGroup.groupKey,
                        TorchCapabilityState.AVAILABLE,
                        "torch verification for cameraId=$boundId"
                    )
                }
                TorchVerificationResult.UNAVAILABLE -> {
                    updateTorchCapability(
                        selectedGroup.groupKey,
                        TorchCapabilityState.UNAVAILABLE,
                        "torch verification for cameraId=$boundId"
                    )
                }
                TorchVerificationResult.INCONCLUSIVE -> {
                    Log.d(
                        TAG,
                        "Torch verification for group=${selectedGroup.groupKey} cameraId=$boundId was inconclusive; leaving capability UNKNOWN"
                    )
                }
            }
        }
    }

    private suspend fun verifyTorchCapabilityWhileActive(
        groupKey: String,
        cameraId: String,
        boundCamera: androidx.camera.core.Camera,
        reason: String
    ): TorchVerificationResult {
        return try {
            Log.d(TAG, "Verifying torch support for group=$groupKey cameraId=$cameraId ($reason)")
            withContext(Dispatchers.IO) {
                boundCamera.cameraControl.enableTorch(true).get(TORCH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                Thread.sleep(TORCH_PROBE_SETTLE_DELAY_MS)
                boundCamera.cameraControl.enableTorch(false).get(TORCH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            Log.i(TAG, "Torch verified for group=$groupKey cameraId=$cameraId")
            TorchVerificationResult.AVAILABLE
        } catch (e: Exception) {
            val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
            if (e is CancellationException || rootCause is CancellationException) {
                Log.d(TAG, "Torch verification canceled for group=$groupKey cameraId=$cameraId", e)
                return TorchVerificationResult.INCONCLUSIVE
            }

            if (rootCause is androidx.camera.core.CameraControl.OperationCanceledException) {
                Log.d(TAG, "Torch verification interrupted because camera became inactive for group=$groupKey cameraId=$cameraId", e)
                return TorchVerificationResult.INCONCLUSIVE
            }

            if (rootCause is IllegalStateException && rootCause.message?.contains("No flash unit", ignoreCase = true) == true) {
                Log.w(TAG, "Torch verification reported no flash unit for group=$groupKey cameraId=$cameraId", e)
                return TorchVerificationResult.UNAVAILABLE
            }

            Log.w(TAG, "Torch verification failed for group=$groupKey cameraId=$cameraId", e)
            TorchVerificationResult.UNAVAILABLE
        }
    }

    private fun updateTorchCapability(groupKey: String, capability: TorchCapabilityState, reason: String) {
        val changed = synchronized(cameraCatalogLock) {
            val current = torchCapabilityByGroupKey[groupKey]
            if (current == capability) {
                false
            } else {
                torchCapabilityByGroupKey[groupKey] = capability
                true
            }
        }

        if (!changed) {
            return
        }

        val catalogChanged = publishCameraCatalogIfChanged(reason)
        if (catalogChanged) {
            saveSettings()
        }

        val selectedGroupKey = getCameraGroupForRawId(ensureSelectedCameraId())?.groupKey
        if (selectedGroupKey == groupKey) {
            when (capability) {
                TorchCapabilityState.AVAILABLE -> {
                    if (desiredTorchEnabled) {
                        reconcileTorchState("torch capability available for selected camera")
                    }
                }
                TorchCapabilityState.UNKNOWN -> Unit
                TorchCapabilityState.UNAVAILABLE -> {
                    if (desiredTorchEnabled) {
                        desiredTorchEnabled = false
                        saveSettings()
                    }
                    if (effectiveTorchEnabled) {
                        reconcileTorchState("torch capability removed for selected camera")
                    }
                }
            }
        }

        broadcastCameraState()
        safeInvokeCameraStateCallback()
    }
    
    // Flashlight control
    @Volatile private var camera: androidx.camera.core.Camera? = null
    private var cameraStateLiveData: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null
    private var cameraStateObserver: androidx.lifecycle.Observer<androidx.camera.core.CameraState>? = null
    @Volatile private var isTorchOperationInProgress: Boolean = false // Prevent concurrent torch operations
    // Camera binding state management
    @Volatile private var isBindingInProgress: Boolean = false
    private val bindingLock = Any() // Lock for binding synchronization
    @Volatile private var lastBindRequestTime: Long = 0 // Time of last bind request
    private var pendingBindJob: Job? = null // Job for pending bind operation
    @Volatile private var hasPendingRebind: Boolean = false // Track if a rebind was requested while binding in progress
    @Volatile private var pendingRtspPipelineRefresh: Boolean = false
    // Cache display metrics and battery info to avoid Binder calls from HTTP threads
    private var cachedDensity: Float = 0f
    private var cachedBatteryInfo: BatteryInfo = BatteryInfo(0, false)
    private var lastBatteryUpdate: Long = 0
    
    // Enhanced Battery Management System
    // Battery thresholds for power management
    private val BATTERY_CRITICAL_PERCENT = 10  // Disable streaming below this
    private val BATTERY_LOW_PERCENT = 20       // Release wakelocks below this
    private val BATTERY_RECOVERY_PERCENT = 50  // Auto-restore above this
    
    // Battery management state
    private enum class BatteryManagementMode {
        NORMAL,          // Full operation (battery > 20% OR charging)
        LOW_BATTERY,     // Wakelocks released (battery ≤ 20%, not charging)
        CRITICAL_BATTERY // Streaming disabled (battery ≤ 10%, not charging)
    }
    
    @Volatile private var batteryMode: BatteryManagementMode = BatteryManagementMode.NORMAL
    @Volatile private var userOverrideBatteryLimit: Boolean = false // User can manually override critical mode if battery > 10%
    // State tracking for delta broadcasting (only send changed values via SSE)
    private val lastBroadcastState = mutableMapOf<String, Any?>()
    private val broadcastLock = Any() // Lock for broadcast state synchronization
    private lateinit var lifecycleRegistry: LifecycleRegistry
    // LIFECYCLE MANAGEMENT: Use lifecycleScope for lifecycle-aware coroutines
    // This ensures all coroutines are cancelled when service lifecycle ends (DESTROYED state)
    // However, since CameraService needs to persist across Activity lifecycle changes,
    // we use a custom scope for long-running operations independent of Activity lifecycle.
    // The lifecycleScope is still available for operations that should stop with service destroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cameraOrientation: String = "landscape" // "portrait" or "landscape" - base camera recording mode
    private var rotation: Int = 0 // 0, 90, 180, 270 - applied to the camera-oriented image
    private var deviceOrientation: Int = 0 // Current device orientation (for app UI only, not camera)
    private var orientationEventListener: OrientationEventListener? = null
    // OSD overlay settings - individually toggleable
    private var showDateTimeOverlay: Boolean = true // Show date/time in top left
    private var showBatteryOverlay: Boolean = true // Show battery in top right
    private var showResolutionOverlay: Boolean = true // Show actual resolution in bottom right for debugging
    private var showFpsOverlay: Boolean = true // Show FPS in bottom left
    // FPS tracking
    private val fpsFrameTimes = mutableListOf<Long>() // Track frame times for FPS calculation (camera)
    private var currentCameraFps: Float = 0f // Current calculated camera FPS
    private var lastFpsCalculation: Long = 0 // Last time FPS was calculated
    
    // MJPEG streaming FPS tracking (actual frames served to clients)
    private val mjpegFpsFrameTimes = mutableListOf<Long>()
    private var currentMjpegFps: Float = 0f
    private var lastMjpegFpsCalculation: Long = 0
    private val mjpegFpsLock = Any()
    
    // RTSP streaming FPS tracking (actual frames encoded)
    private val rtspFpsFrameTimes = mutableListOf<Long>()
    private var currentRtspFps: Float = 0f
    private var lastRtspFpsCalculation: Long = 0
    private val rtspFpsLock = Any()
    
    // Target FPS settings
    @Volatile private var targetMjpegFps: Int = 10 // Target FPS for MJPEG streaming (default 10)
    @Volatile private var targetRtspFps: Int = 30 // Target FPS for RTSP streaming (default 30)
    
    // CPU usage tracking
    @Volatile private var currentCpuUsage: Float = 0f // Current CPU usage percentage (0-100)
    
    private var watchdogRetryDelay: Long = WATCHDOG_RETRY_DELAY_MS
    @Volatile private var lastCameraStartupAtMs: Long = 0L
    // Enhanced watchdog - track last frame timestamp to detect frozen frames
    @Volatile private var lastWatchdogFrameTimestamp: Long = 0L
    @Volatile private var frozenFrameDetectionCount: Int = 0
    private var networkReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    @Volatile private var actualPort: Int = PORT // The actual port being used (may differ from PORT if unavailable)
    @Volatile private var connectionLimits: ConnectionLimits = ConnectionLimits.DEFAULT
    // Track if server was intentionally stopped (don't auto-restart in watchdog)
    @Volatile private var serverIntentionallyStopped: Boolean = false
    
    private lateinit var performanceMetrics: PerformanceMetrics
    private lateinit var runtimeTelemetrySampler: RuntimeTelemetrySampler
    @Volatile private var latestRuntimeTelemetry: RuntimeTelemetrySnapshot = RuntimeTelemetrySnapshot.empty()
    private var telemetryJob: Job? = null
    @Volatile private var adaptiveQualityEnabled: Boolean = false // Deprecated compatibility flag
    
    // RTSP server for hardware-accelerated H.264 streaming
    private var rtspServer: RTSPServer? = null
    @Volatile private var rtspEnabled: Boolean = false
    @Volatile private var rtspBitrate: Int = -1 // Saved bitrate setting (-1 = auto/default)
    @Volatile private var rtspBitrateMode: String = "VBR" // Saved bitrate mode setting
    
    // WiFi debugging manager for remote access (Device Owner only)
    private var wifiDebuggingManager: WiFiDebuggingManager? = null
    
    // Device identification
    @Volatile private var deviceName: String = "" // User-defined device name (default: IP_CAM_{deviceModel})
    
    // Callbacks for the currently active MainActivity instance.
    // Only one visible activity should own these callbacks at a time, so writes are owner-scoped.
    @Volatile private var onCameraStateChangedCallback: (() -> Unit)? = null
    @Volatile private var onFrameAvailableCallback: ((Bitmap) -> Unit)? = null
    @Volatile private var onConnectionsChangedCallback: (() -> Unit)? = null
    @Volatile private var activityCallbackOwnerId: String? = null
    
    // Bitmap pool for memory-efficient bitmap reuse
    private val bitmapPool = BitmapPool(maxPoolSizeBytes = 64L * 1024 * 1024) // 64 MB pool

    private fun clearLastProcessedFrame(reason: String) {
        val oldFrame = lastProcessedFrame.getAndSet(null) ?: return
        bitmapPool.recycleBitmap(oldFrame.bitmap)
        Log.d(TAG, "Last processed frame cleared ($reason)")
    }

    private fun clearRtspPipeline(reason: String) {
        val hadRtspPipeline = h264Encoder != null || videoCaptureUseCase != null
        if (hadRtspPipeline) {
            Log.d(TAG, "Clearing RTSP pipeline ($reason)")
        }

        h264Encoder?.stop()
        h264Encoder = null
        videoCaptureUseCase = null
        if (hadRtspPipeline) {
            rtspServer?.invalidateCodecConfig("RTSP pipeline cleared: $reason")
        }
    }

    private fun hasBoundRtspPipeline(): Boolean {
        return h264Encoder != null || videoCaptureUseCase != null
    }

    private fun shouldBindRtspPipeline(): Boolean {
        return rtspEnabled && rtspServer?.hasActiveCameraLease() == true
    }

    private fun refreshRtspPipelineIfNeeded(reason: String) {
        val shouldBind = shouldBindRtspPipeline()
        val isBound = hasBoundRtspPipeline()

        if (shouldBind == isBound) {
            pendingRtspPipelineRefresh = false
            return
        }

        val currentState = synchronized(cameraStateLock) { cameraState }
        if (currentState != CameraState.ACTIVE) {
            pendingRtspPipelineRefresh = true
            Log.d(
                TAG,
                "Deferring RTSP pipeline refresh ($reason): state=$currentState, shouldBind=$shouldBind, isBound=$isBound"
            )
            return
        }

        pendingRtspPipelineRefresh = false
        Log.i(
            TAG,
            "RTSP pipeline refresh required ($reason): state=$currentState, shouldBind=$shouldBind, isBound=$isBound"
        )
        requestBindCamera()
    }

    private fun noteCameraStartup(reason: String) {
        lastCameraStartupAtMs = System.currentTimeMillis()
        lastWatchdogFrameTimestamp = 0L
        frozenFrameDetectionCount = 0
        Log.d(TAG, "Camera startup grace window refreshed ($reason)")
    }

    private fun isWithinCameraStartupGracePeriod(now: Long = System.currentTimeMillis()): Boolean {
        val startupAt = lastCameraStartupAtMs
        return startupAt > 0L && now - startupAt < CAMERA_WATCHDOG_STARTUP_GRACE_MS
    }
    
    // ==================== Camera State Management ====================
    
    /**
     * CameraState - Tracks camera lifecycle for on-demand activation
     */
    private enum class CameraState {
        IDLE,           // Camera not initialized, no consumers
        INITIALIZING,   // Camera binding in progress
        ACTIVE,         // Camera bound and providing frames
        STOPPING,       // Camera unbinding in progress
        ERROR           // Camera failed to initialize
    }
    
    @Volatile private var cameraState: CameraState = CameraState.IDLE
    private val cameraStateLock = Any()
    
    /**
     * Consumer tracking for on-demand camera activation
     * Consumers: Preview (MainActivity), MJPEG clients, RTSP clients, Manual API
     */
    private enum class ConsumerType {
        PREVIEW,    // MainActivity preview
        MJPEG,      // MJPEG stream clients
        RTSP,       // RTSP streaming
        SNAPSHOT,   // Single-frame snapshot requests
        MANUAL      // Manual activation via API (for testing/debugging)
    }
    
    private val consumers = mutableMapOf<ConsumerType, Int>()
    private val consumersLock = Any()
    
    companion object {
         private const val TAG = "CameraService"
         private const val CHANNEL_ID = "CameraServiceChannel"
         private const val NOTIFICATION_ID = 1
         private const val PORT = 8080
         private const val MAX_PORT = 65535
         private const val MAX_PORT_ATTEMPTS = 10 // Try up to 10 ports
         private const val FRAME_STALE_THRESHOLD_MS = 5_000L
         private const val FROZEN_FRAME_DETECTION_COUNT = 3 // Number of consecutive same-frame detections before reset
         private const val RESOLUTION_DELIMITER = "x"
         private const val WATCHDOG_RETRY_DELAY_MS = 1_000L
         private const val WATCHDOG_MAX_RETRY_DELAY_MS = 30_000L
         private const val CAMERA_WATCHDOG_STARTUP_GRACE_MS = 3_000L
         private const val CAMERA_PROBE_TIMEOUT_MS = 5_000L
         private const val TORCH_PROBE_TIMEOUT_MS = 1_500L
         private const val TORCH_PROBE_SETTLE_DELAY_MS = 150L
         // Camera rebinding debounce
         private const val CAMERA_REBIND_DEBOUNCE_MS = 500L // Minimum time between rebind requests
         // Intent extras
         const val EXTRA_START_SERVER = "start_server"
         // JPEG compression quality settings
         private const val JPEG_QUALITY_CAMERA = 70 // Lower quality to reduce memory pressure
         private const val JPEG_QUALITY_SNAPSHOT = 85 // Higher quality for snapshots
         private const val JPEG_QUALITY_STREAM = 75 // Balanced quality for streaming
         // Stream timing
         private const val STREAM_FRAME_DELAY_MS = 100L // ~10 fps
         private const val TELEMETRY_UPDATE_INTERVAL_MS = 2000L
         // Camera activation delay
         private const val CAMERA_ACTIVATION_DELAY_MS = 500L // Delay before activating camera when consumer registers
         // Settings keys
         private const val PREF_LEGACY_MAX_CONNECTIONS = "maxConnections"
         private const val PREF_MAX_MJPEG_STREAMS = "maxMjpegStreams"
         private const val PREF_MAX_SSE_CLIENTS = "maxSseClients"
         private const val PREF_MAX_RTSP_SESSIONS = "maxRtspSessions"
     }
     
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    
    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        // Create notification channel immediately (required before startForeground)
        createNotificationChannel()
        
        // CRITICAL: Must call startForeground() within 5 seconds of startForegroundService()
        // Do this BEFORE any other operations to avoid ANR
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            // If foreground start fails, stop the service gracefully
            stopSelf()
            return
        }
        
        // Note: Permission checks are done BEFORE starting the service (in BootReceiver and MainActivity)
        // This ensures the service only starts when permissions are available
        // If you see this service running, permissions were validated before start
        
        // Initialize camera characteristics cache early to avoid repeated IPC calls
        // This queries hardware capabilities once and caches them for the service lifetime
        initializeCameraCharacteristicsCache()

        // Load saved settings before the camera provider comes up. Raw camera IDs are normalized
        // to the current grouped catalog once bindable cameras become available.
        loadSettings()

        initializeCameraProvider {
            rebuildCameraCatalog("service create")
        }
        
        performanceMetrics = PerformanceMetrics(this)
        runtimeTelemetrySampler = RuntimeTelemetrySampler()
        latestRuntimeTelemetry = RuntimeTelemetrySnapshot.empty()
        Log.d(TAG, "Runtime telemetry components initialized")
        
        // Cache display density to avoid Binder calls from HTTP threads
        cachedDensity = resources.displayMetrics.density
        registerBatteryReceiver()
        
        // Check for POST_NOTIFICATIONS permission on Android 13+
        // Note: startForeground() will still succeed even without permission on Android 13+,
        // but the notification won't be visible to the user. The service continues to run normally.
        if (!hasNotificationPermission()) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Foreground service notification may not be visible on Android 13+")
        }
        
        acquireLocks()
        registerNetworkReceiver()
        setupOrientationListener()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        startTelemetryLoop()
        
        // CAMERA INITIALIZATION:
        // DO NOT automatically start camera on service creation
        // Camera will start on-demand when first consumer (preview, MJPEG, RTSP) connects
        // This saves resources when the app is running as a launcher without active use
        Log.d(TAG, "Camera initialization deferred until first consumer connects")
        cameraState = CameraState.IDLE
        
        // AUTO-START RTSP SERVER (always running/idling)
        // RTSP server starts immediately but camera activates only when clients connect
        // This allows RTSP clients to connect without manual web activation
        serviceScope.launch {
            delay(2000) // Brief delay to ensure service is fully initialized
            if (enableRTSPStreaming()) {
                Log.i(TAG, "RTSP server auto-started and ready for connections")
            } else {
                Log.w(TAG, "Failed to auto-start RTSP server")
            }
        }
        
        startWatchdog()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        
        // Check if we should start the server based on intent extra
        val shouldStartServer = intent?.getBooleanExtra(EXTRA_START_SERVER, false) ?: false
        if (shouldStartServer && httpServer?.isAlive() != true) {
            startServer()
        }
        
        // NOTE: Camera is NOT automatically started in onStartCommand
        // Camera will be initialized on-demand when first consumer connects
        // This prevents unnecessary camera usage when app is idle
        
        return START_STICKY
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - restarting service")
        
        // Restart the service immediately when task is removed
        val restartIntent = Intent(applicationContext, CameraService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
    }
    
    /**
     * Check if all required permissions are granted
     * Service cannot function without these permissions
     */
    private fun hasRequiredPermissions(): Boolean {
        // CAMERA permission is required (runtime permission)
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        // INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE are install-time permissions (automatically granted)
        // No need to check them at runtime
        
        if (!hasCameraPermission) {
            Log.e(TAG, "Missing required permission: CAMERA")
            return false
        }
        
        Log.d(TAG, "All required permissions granted")
        return true
    }
    
    /**
     * Periodically check if camera can be activated after boot
     * This handles cases where device is locked at boot but unlocked later
     * Checks every 15 seconds for up to 5 minutes
     */
    /**
     * Check if POST_NOTIFICATIONS permission is granted (required on Android 13+)
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Permission not required on Android 12 and below
            true
        }
    }
    
    private fun createNotificationChannel() {
        // API 30+ always supports notification channels (introduced in API 26)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IP Camera Service",
            NotificationManager.IMPORTANCE_DEFAULT  // Changed from LOW to DEFAULT for better persistence
        ).apply {
            description = "Keeps camera service running in background"
            setShowBadge(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    
    private fun isPortAvailable(port: Int): Boolean {
        var serverSocket: ServerSocket? = null
        return try {
            // Create a server socket to check if the port is available
            serverSocket = ServerSocket(port)
            serverSocket.reuseAddress = true
            true
        } catch (e: IOException) {
            // Port is already in use
            Log.w(TAG, "Port $port is not available: ${e.message}")
            false
        } finally {
            // Always close the socket
            serverSocket?.close()
        }
    }
    
    /**
     * Find an available port starting from the specified port.
     * Will try up to MAX_PORT_ATTEMPTS consecutive ports.
     * @param startPort The port to start searching from
     * @return The first available port found, or null if none available
     */
    private fun findAvailablePort(startPort: Int): Int? {
        var port = startPort
        var attempts = 0
        
        while (attempts < MAX_PORT_ATTEMPTS && port <= MAX_PORT) {
            if (isPortAvailable(port)) {
                Log.d(TAG, "Found available port: $port")
                return port
            }
            port++
            attempts++
        }
        
        val reason = if (attempts >= MAX_PORT_ATTEMPTS) {
            "reached maximum attempts ($MAX_PORT_ATTEMPTS)"
        } else {
            "exceeded maximum port number ($MAX_PORT)"
        }
        Log.e(TAG, "Could not find available port - $reason, started from $startPort")
        return null
    }
    
    private fun getNotificationText(): String {
        return if (httpServer?.isAlive() == true) {
            "Server running on ${getServerUrl()}"
        } else {
            "Camera preview active"
        }
    }
    
    private fun createNotification(contentText: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val text = contentText ?: getNotificationText()
        
        // Use device name in notification title if set, otherwise use default
        val title = if (deviceName.isNotEmpty()) deviceName else "IP Camera Server"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Make notification persistent
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // Increase priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun startServer() {
        try {
            // Clear the intentionally stopped flag since we're starting the server
            serverIntentionallyStopped = false
            
            // If server is already running, just return
            if (httpServer?.isAlive() == true) {
                Log.d(TAG, "Server is already running on port $actualPort")
                return
            }
            
            // Stop any existing server instance
            httpServer?.stop()
            
            // Try to find an available port, starting with the preferred port
            val availablePort = findAvailablePort(PORT)
            
            if (availablePort == null) {
                val errorMsg = "Could not find an available port. Tried $MAX_PORT_ATTEMPTS ports starting from $PORT."
                Log.e(TAG, errorMsg)
                updateNotification("Server failed to start - no available ports")
                showUserNotification("Server Start Failed", errorMsg)
                return
            }
            
            // Update the actual port being used
            actualPort = availablePort
            
            // Notify user if we're using a different port than the default
            if (actualPort != PORT) {
                val msg = "Port $PORT was unavailable. Using port $actualPort instead."
                Log.w(TAG, msg)
                showUserNotification("Port Changed", msg)
            }
            
            // Create and start the Ktor-based HTTP server
            httpServer = HttpServer(actualPort, this@CameraService, this@CameraService)
            httpServer?.start()
            
            // Start WiFi debugging manager if Device Owner
            if (wifiDebuggingManager == null) {
                wifiDebuggingManager = WiFiDebuggingManager(this)
            }
            wifiDebuggingManager?.startMonitoring()
            
            val startMsg = if (actualPort != PORT) {
                "Server started on port $actualPort with connection limits $connectionLimits (default port $PORT was unavailable)"
            } else {
                "Server started on port $actualPort with connection limits $connectionLimits"
            }
            Log.d(TAG, startMsg)
            
            // Update notification with the actual port
            updateNotification("Server running on ${getServerUrl()}")
            
            // Broadcast state change to web clients (if any connected during start)
            broadcastCameraState()
            
        } catch (e: IOException) {
            val errorMsg = "Failed to start server: ${e.message}"
            Log.e(TAG, errorMsg, e)
            updateNotification("Server failed to start")
            showUserNotification("Server Error", errorMsg)
        } catch (e: Exception) {
            val errorMsg = "Failed to start server: ${e.message}"
            Log.e(TAG, errorMsg, e)
            updateNotification("Server failed to start")
            showUserNotification("Server Error", errorMsg)
        }
    }
    
    /**
     * Update the foreground notification with new text
     */
    private fun updateNotification(contentText: String) {
        // On Android 13+, check if POST_NOTIFICATIONS permission is granted
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Cannot update notification: POST_NOTIFICATIONS permission not granted")
            return
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(contentText))
    }
    
    /**
     * Show a user notification (not the foreground service notification)
     */
    private fun showUserNotification(title: String, message: String) {
        // On Android 13+, check if POST_NOTIFICATIONS permission is granted
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Cannot show user notification: POST_NOTIFICATIONS permission not granted. Title: $title, Message: $message")
            return
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Create a separate notification for user alerts
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        // Use a different notification ID for user alerts
        notificationManager?.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera() called but Camera permission not granted - waiting for permission")
            return
        }

        Log.d(TAG, "startCamera() - ensuring camera provider is ready...")
        initializeCameraProvider {
            rebuildCameraCatalog("startCamera")
            if (getVisibleCameraGroups().isEmpty()) {
                Log.e(TAG, "startCamera() aborted - no usable camera groups available")
                synchronized(cameraStateLock) {
                    cameraState = CameraState.ERROR
                }
                broadcastCameraState()
                safeInvokeCameraStateCallback()
                return@initializeCameraProvider
            }
            bindCamera()
        }
    }
    
    private fun bindCamera() {
        // Ensure lifecycle is in correct state
        if (lifecycleRegistry.currentState != Lifecycle.State.STARTED) {
            Log.w(TAG, "Cannot bind camera - lifecycle not in STARTED state: ${lifecycleRegistry.currentState}")
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        try {
            detachCameraStateObserver()
            clearBoundCameraStartupTimeout()
            Log.d(TAG, "Unbinding all use cases before rebinding...")
            cameraProvider?.unbindAll()
            camera = null
            boundCameraGroupKey = null
            boundCameraId = null
            awaitingFirstFrameForBoundCamera = false

            val cameraId = ensureSelectedCameraId()
            if (cameraId == null) {
                Log.e(TAG, "Cannot bind camera - no selectable camera is available")
                rebuildCameraCatalog("bindCamera without selected camera")
                return
            }
            val selectedCamera = cameraCharacteristicsCache[cameraId]
            if (selectedCamera == null) {
                Log.e(TAG, "Cannot bind camera - cached characteristics missing for cameraId=$cameraId")
                return
            }
            
            val resolution = selectedResolution ?: Size(1920, 1080)
            Log.d(
                TAG,
                "Binding cameraId=$cameraId (${lensFacingToLabel(selectedCamera.lensFacing)}) " +
                    "with resolution: ${resolution.width}x${resolution.height}"
            )
            
            // === Use Case 1: Preview for H.264 Encoding (Hardware MediaCodec) ===
            // Only create when an RTSP client currently holds a camera lease.
            val bindRtspPipeline = shouldBindRtspPipeline()
            if (bindRtspPipeline) {
                try {
                    // Create H.264 encoder
                    h264Encoder = H264PreviewEncoder(
                        width = resolution.width,
                        height = resolution.height,
                        fps = targetRtspFps,
                        bitrate = if (rtspBitrate > 0) rtspBitrate else RTSPServer.calculateBitrate(resolution.width, resolution.height),
                        bitrateMode = rtspBitrateMode,
                        rtspServer = rtspServer
                    )
                    h264Encoder?.start()
                    
                    // Create Preview for H.264 encoder (feeds to encoder's surface)
                    // CRITICAL: Must match the resolution that the encoder expects
                    val previewResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionFilter { supportedSizes, _ ->
                            // Try to find exact match for encoder resolution
                            val exactMatch = supportedSizes.filter { size ->
                                size.width == resolution.width && size.height == resolution.height
                            }
                            
                            if (exactMatch.isNotEmpty()) {
                                Log.d(TAG, "Found exact resolution match for H.264 Preview: ${resolution.width}x${resolution.height}")
                                exactMatch
                            } else {
                                // No exact match - find closest
                                val targetPixels = resolution.width * resolution.height
                                val closest = supportedSizes.minByOrNull { size ->
                                    kotlin.math.abs(size.width * size.height - targetPixels)
                                }
                                
                                if (closest != null) {
                                    Log.w(TAG, "Exact resolution ${resolution.width}x${resolution.height} not available for H.264 Preview. Using closest: ${closest.width}x${closest.height}")
                                } else {
                                    Log.e(TAG, "Could not find any suitable resolution for H.264 Preview")
                                }
                                
                                closest?.let { listOf(it) } ?: supportedSizes
                            }
                        }
                        .build()
                    
                    videoCaptureUseCase = androidx.camera.core.Preview.Builder()
                        .setResolutionSelector(previewResolutionSelector)
                        .build()
                        .apply {
                            // Connect to encoder's input surface
                            setSurfaceProvider { request ->
                                val surface = h264Encoder?.getInputSurface()
                                if (surface != null) {
                                    request.provideSurface(
                                        surface,
                                        cameraExecutor
                                    ) { }
                                    Log.d(TAG, "H.264 encoder surface connected to camera with target FPS: $targetRtspFps")
                                } else {
                                    request.willNotProvideSurface()
                                    Log.w(TAG, "H.264 encoder surface not available - encoder may have failed to initialize or been stopped")
                                }
                            }
                        }
                    Log.i(TAG, "H.264 encoder created and connected with target FPS: $targetRtspFps")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create H.264 encoder", e)
                    clearRtspPipeline("encoder creation failure")
                }
            } else {
                clearRtspPipeline(if (rtspEnabled) "no active RTSP leases" else "RTSP disabled")
            }
            
            // === Use Case 2: ImageAnalysis for MJPEG (CPU, throttled to targetMjpegFps) ===
            val resolutionSelector = buildImageAnalysisResolutionSelector(resolution)
            
            val mjpegAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                // Note: ImageAnalysis doesn't support setTargetFrameRate directly
                // Frame rate throttling is achieved via KEEP_ONLY_LATEST backpressure
                // which naturally throttles based on processing time
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Use RGBA_8888 format to get Bitmaps directly (API 30+)
                // This eliminates inefficient YUV→NV21→JPEG→Bitmap conversion
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            mjpegAnalysis.setAnalyzer(cameraExecutor) { image ->
                processMjpegFrame(image)
            }
            
            // Store reference after configuration
            imageAnalysis = mjpegAnalysis
            
            // === Bind Use Cases to Lifecycle ===
            // Build list of use cases to bind based on what's enabled
            val useCases = mutableListOf<androidx.camera.core.UseCase>(
                mjpegAnalysis         // Always bind MJPEG/ImageAnalysis pipeline
            )
            
            // Add H.264 encoder preview only when an active RTSP session needs it
            videoCaptureUseCase?.let { useCases.add(it) }
            
            Log.d(TAG, "Binding ${useCases.size} use cases to lifecycle (ImageAnalysis${if (videoCaptureUseCase != null) " + H264 Preview" else ""})")
            val selector = buildCameraSelector(cameraId)
            camera = cameraProvider?.bindToLifecycle(this, selector, *useCases.toTypedArray())
            
            if (camera == null) {
                Log.e(TAG, "Camera binding returned null!")
                return
            }
            
            Log.i(TAG, "Camera bound successfully with ${useCases.size} use case(s):")
            Log.i(TAG, "  1. ImageAnalysis (MJPEG + MainActivity preview): ~$targetMjpegFps fps target")
            if (videoCaptureUseCase != null) {
                Log.i(TAG, "  2. Preview → H.264 Encoder (RTSP): $targetRtspFps fps target")
            }
            
            Log.d(TAG, "Camera bound successfully to cameraId=$cameraId. Frame processing should resume.")
            
            camera?.let { attachCameraStateObserver(it) }
            boundCameraGroupKey = getCameraGroupForRawId(cameraId)?.groupKey
            boundCameraId = cameraId
            awaitingFirstFrameForBoundCamera = true
            startBoundCameraStartupTimeout(
                expectedCameraId = cameraId,
                expectedGroupKey = boundCameraGroupKey ?: ""
            )
            
            noteCameraStartup("camera bound")
            synchronized(cameraStateLock) {
                cameraState = CameraState.INITIALIZING
                Log.d(TAG, "Camera binding successful → waiting for first frame")
            }

            if (pendingRtspPipelineRefresh) {
                refreshRtspPipelineIfNeeded("post-bind deferred refresh")
            }
            
            // Notify observers that camera state has changed (binding completed)
            // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
            safeInvokeCameraStateCallback()
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed with exception: ${e.message}", e)
            e.printStackTrace()
            val failedCameraId = boundCameraId ?: selectedCameraId
            if (failedCameraId != null) {
                markCameraCandidateState(failedCameraId, CameraCandidateState.BROKEN, "bind exception")
                selectedCameraId = ensureSelectedCameraId()
            }
            clearBoundCameraStartupTimeout()
            
            // Set error state
            synchronized(cameraStateLock) {
                cameraState = CameraState.ERROR
                Log.d(TAG, "Camera binding failed → ERROR state")
            }
            
            // Clear camera reference on failure to allow watchdog to retry
            camera = null
            boundCameraGroupKey = null
            boundCameraId = null
            
            // Show error notification to user
            showUserNotification(
                "Camera Binding Failed",
                "Failed to initialize camera: ${e.message}. The system will retry automatically."
            )
        }
    }
    
    /**
     * Properly stop camera activities before applying new settings.
     * This ensures clean state transition and prevents resource conflicts.
     * Also used for on-demand deactivation when no consumers remain.
     * 
     * MUST be called on main thread due to CameraX unbindAll() requirement.
     */
    private fun stopCamera(reactivateConsumersAfterStop: Boolean = true) {
        try {
            Log.d(TAG, "Stopping camera...")
            
            val shouldMaintainTorch = desiredTorchEnabled &&
                isTorchAvailableForGroup(getCameraGroupForRawId(boundCameraId ?: selectedCameraId)?.groupKey)
            
            // Stop RTSP pipeline first so MediaCodec and Preview surface are released before unbind.
            clearRtspPipeline("camera stop")
            
            // Clear old analyzer to stop frame processing
            imageAnalysis?.clearAnalyzer()
            detachCameraStateObserver()
            torchVerificationJob?.cancel()
            torchVerificationJob = null
            clearBoundCameraStartupTimeout()
            boundCameraGroupKey = null
            boundCameraId = null
            awaitingFirstFrameForBoundCamera = false
            
            // Unbind all use cases from lifecycle - MUST be on main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    cameraProvider?.unbindAll()
                    Log.d(TAG, "Camera unbound from lifecycle")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding camera", e)
                } finally {
                    camera = null
                    Log.d(TAG, "Camera reference cleared, shouldMaintainTorch=$shouldMaintainTorch")
                    
                    if (shouldMaintainTorch) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            reconcileTorchState("camera unbound while torch requested")
                        }, 500)
                    }
                }
            }
            
            clearLastProcessedFrame("camera stop")
            
            // Reset FPS counters when camera is stopped
            currentCameraFps = 0f
            synchronized(fpsFrameTimes) {
                fpsFrameTimes.clear()
            }
            Log.d(TAG, "Camera FPS reset to 0")
            
            // Update state to IDLE (unless already in ERROR)
            synchronized(cameraStateLock) {
                if (cameraState != CameraState.ERROR) {
                    cameraState = CameraState.IDLE
                    Log.d(TAG, "Camera stopped → IDLE state")
                } else {
                    Log.d(TAG, "Camera stopped (state remains ERROR)")
                }
            }
            
            // If consumers registered while the camera was stopping (e.g. a new MJPEG client
            // connected just as the last one disconnected), reactivate the camera immediately
            // instead of waiting for the watchdog (which runs every 5-10 s).
            if (reactivateConsumersAfterStop && hasConsumers()) {
                Log.d(TAG, "Consumers waiting after stopCamera(), reactivating camera immediately")
                activateCameraForConsumers()
            }
            
            // Update notification
            updateNotification("Camera inactive - No consumers")
            
            // Broadcast state change
            broadcastCameraState()
            
            Log.d(TAG, "Camera stopped successfully (including H.264 encoder if active)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
            synchronized (cameraStateLock) {
                cameraState = CameraState.ERROR
            }
        }
    }
    
    /**
     * Full camera service reset - stronger than requestBindCamera.
     * This performs a DEEP teardown and restart of the camera pipeline:
     * 1. Stops camera completely (clears use cases, unbinds camera)
     * 2. Clears ALL camera-related objects and state variables
     * 3. Cancels pending operations and jobs
     * 4. Forces complete reacquisition of ProcessCameraProvider
     * 5. Reinitializes camera from scratch
     * 
     * Use this for recovery from frozen/broken camera states that don't respond
     * to normal rebinding. This is the most thorough reset possible without
     * restarting the entire service.
     * 
     * @return true if reset was initiated successfully
     */
    override fun fullCameraReset(): Boolean {
        Log.w(TAG, "fullCameraReset() - Performing DEEP camera service reset...")
        Log.i(TAG, "Pre-reset state: camera=$camera, provider=$cameraProvider, imageAnalysis=$imageAnalysis, state=$cameraState")

        return try {
            // Cancel any pending bind operations
            pendingBindJob?.cancel()
            pendingBindJob = null
            
            // Stop camera first (clears H.264 encoder, analyzer, unbinds all)
            // Full reset manages restart explicitly after teardown completes.
            stopCamera(reactivateConsumersAfterStop = false)
            
            // Wait for stopCamera to complete (unbindAll is posted to main thread)
            Thread.sleep(300)
            
            // DEEP CLEAR: Explicitly null out ALL camera-related objects
            // This ensures no stale references remain that could cause issues
            
            // Clear ImageAnalysis use case
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            Log.d(TAG, "ImageAnalysis cleared")
            
            // Clear H.264 encoder and video use case (should already be cleared by stopCamera)
            clearRtspPipeline("full camera reset")
            
            // Clear camera reference
            camera = null
            
            clearLastProcessedFrame("full camera reset")
            
            // Reset binding state flags to prevent stale state
            synchronized(bindingLock) {
                isBindingInProgress = false
                hasPendingRebind = false
                lastBindRequestTime = 0
            }
            
            // Clear camera provider completely to force reacquisition
            // This ensures we get a fresh provider instance, not a cached one
            val oldProvider = cameraProvider
            cameraProvider = null
            
            // Explicitly shutdown the old provider if it exists
            if (oldProvider != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        oldProvider.unbindAll()
                        Log.d(TAG, "Old camera provider explicitly unbound")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error unbinding old provider (this is okay)", e)
                    }
                }
            }
            
            Log.d(TAG, "Camera provider cleared - will be reacquired on next start")
            
            // Reset watchdog state
            lastWatchdogFrameTimestamp = 0L
            frozenFrameDetectionCount = 0
            watchdogRetryDelay = WATCHDOG_RETRY_DELAY_MS
            
            // Reset FPS tracking
            currentCameraFps = 0f
            synchronized(fpsFrameTimes) {
                fpsFrameTimes.clear()
            }
            
            // Reset MJPEG throttling timestamp
            lastMjpegFrameProcessedTimeMs = 0
            
            // Reset state to IDLE (force it even if in ERROR)
            synchronized(cameraStateLock) {
                cameraState = CameraState.IDLE
                Log.d(TAG, "Camera state forced to IDLE")
            }
            
            // Give system MORE time to fully release all camera resources
            // This is critical for deep reset - ensures camera HAL is fully released
            Log.d(TAG, "Waiting for camera resources to be fully released...")
            Thread.sleep(800)
            
            Log.i(TAG, "Deep reset complete. Objects cleared: camera, provider, imageAnalysis, h264Encoder")
            
            // Restart camera if consumers are waiting
            if (hasConsumers()) {
                Log.i(TAG, "fullCameraReset() - Restarting camera for ${getConsumerCount()} consumers")
                // startCamera() will reacquire ProcessCameraProvider from scratch
                startCamera()
            } else {
                Log.i(TAG, "fullCameraReset() - Camera reset complete, no consumers waiting")
            }
            
            Log.i(TAG, "fullCameraReset() - Deep reset successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "fullCameraReset() - Deep reset failed", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
            synchronized(cameraStateLock) {
                cameraState = CameraState.ERROR
            }
            false
        }
    }

    /**
     * Stop camera, apply settings, and restart camera.
     * This ensures settings are properly applied without conflicts.
     * Uses proper async handling to avoid blocking the main thread.
     * 
     * DEBOUNCING PROTECTION:
     * 1. Time-based debouncing: Minimum 500ms between bind requests
     *    - If called too soon, schedules delayed retry via coroutine Job
     *    - Cancels previous pending job to use latest settings
     * 2. Flag-based debouncing: Prevents overlapping bind operations
     *    - If binding already in progress, sets pending flag for retry after completion
     *    - Ensures camera hardware stability by serializing all bind operations
     * 
     * THREAD SAFETY:
     * - All state checks and updates are synchronized via bindingLock
     * - isBindingInProgress flag prevents concurrent camera access
     * - hasPendingRebind flag queues requests received during binding
     * 
     * PRIVATE: Only CameraService methods should trigger rebinding.
     * External callers should use methods like selectCamera() or setResolutionAndRebind()
     * that encapsulate both the setting change and rebinding.
     */
    private fun requestBindCamera() {
        val now = System.currentTimeMillis()
        val currentCameraLifecycleState = synchronized(cameraStateLock) { cameraState }
        val hasActiveConsumers = hasConsumers()

        if (!hasActiveConsumers &&
            (currentCameraLifecycleState == CameraState.IDLE || currentCameraLifecycleState == CameraState.ERROR)) {
            Log.d(
                TAG,
                "requestBindCamera() skipped - no active consumers and camera state is $currentCameraLifecycleState; settings will apply on next activation"
            )
            return
        }

        // Time-based debouncing: Check if enough time has passed since last request
        synchronized(bindingLock) {
            val timeSinceLastRequest = now - lastBindRequestTime
            if (timeSinceLastRequest < CAMERA_REBIND_DEBOUNCE_MS) {
                Log.d(TAG, "requestBindCamera() debounced - too soon (${timeSinceLastRequest}ms < ${CAMERA_REBIND_DEBOUNCE_MS}ms)")
                
                // Cancel any pending bind job and schedule a new one
                // This ensures we use the latest settings if multiple rapid requests occur
                pendingBindJob?.cancel()
                val remainingDelay = CAMERA_REBIND_DEBOUNCE_MS - timeSinceLastRequest
                
                pendingBindJob = serviceScope.launch {
                    delay(remainingDelay)
                    // Recursively call after delay - this time it will pass the debounce check
                    requestBindCamera()
                }
                return
            }
            
            // Flag-based debouncing: Check if a binding operation is already in progress
            if (isBindingInProgress) {
                Log.d(TAG, "requestBindCamera() deferred - binding already in progress, will retry after completion")
                // Instead of silently dropping the request, mark that we have a pending rebind
                // This ensures the latest settings are applied after the current bind completes
                hasPendingRebind = true
                return
            }
            
            isBindingInProgress = true
            lastBindRequestTime = now
        }
        
        // Log stack trace to identify caller
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) {
            "${stackTrace[3].className}.${stackTrace[3].methodName}:${stackTrace[3].lineNumber}"
        } else "unknown"
        Log.d(TAG, "requestBindCamera() called by: $caller")
        
        ContextCompat.getMainExecutor(this).execute {
            try {
                // Stop camera first to ensure clean state
                Log.d(TAG, "Stopping camera before rebinding...")
                stopCamera(reactivateConsumersAfterStop = false)
                
                // Schedule rebinding after a short delay to ensure resources are released
                // Using Handler instead of Thread.sleep to avoid blocking main thread
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (!hasConsumers()) {
                            pendingRtspPipelineRefresh = false
                            Log.d(TAG, "Skipping delayed rebind - consumers disappeared after stop; camera stays idle")
                            return@postDelayed
                        }

                        Log.d(TAG, "Delay complete, rebinding camera now...")
                        bindCamera()
                        // Callback is now invoked directly in bindCamera() after binding succeeds
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in bindCamera() from postDelayed", e)
                    } finally {
                        // Clear the flag and check for pending rebind requests
                        val shouldRetry = synchronized(bindingLock) {
                            isBindingInProgress = false
                            Log.d(TAG, "isBindingInProgress flag cleared")
                            
                            // Check if another rebind was requested while we were binding
                            val retry = hasPendingRebind
                            if (retry) {
                                Log.d(TAG, "Pending rebind detected, will retry after optimized delay")
                                hasPendingRebind = false
                            }
                            retry
                        }
                        
                        // If there was a pending rebind request, execute it now
                        // This ensures the latest settings are applied
                        if (shouldRetry) {
                            // Calculate remaining debounce time to minimize unnecessary delay
                            // Capture both time values within synchronized block to prevent race conditions
                            serviceScope.launch {
                                val (currentTime, lastRequestTime) = synchronized(bindingLock) {
                                    System.currentTimeMillis() to lastBindRequestTime
                                }
                                val timeSinceLastBind = currentTime - lastRequestTime
                                val remainingDelay = (CAMERA_REBIND_DEBOUNCE_MS - timeSinceLastBind).coerceAtLeast(0)
                                
                                if (remainingDelay > 0) {
                                    Log.d(TAG, "Pending rebind: delaying ${remainingDelay}ms for debounce")
                                    delay(remainingDelay)
                                }
                                requestBindCamera()
                            }
                        }
                    }
                }, 100)
            } catch (e: Exception) {
                Log.e(TAG, "Error in requestBindCamera()", e)
                // Clear flag if we fail before even scheduling the delayed binding
                // Also clear pending rebind flag since we couldn't proceed
                synchronized(bindingLock) {
                    isBindingInProgress = false
                    hasPendingRebind = false
                }
            }
        }
    }

    /**
     * Process MJPEG frames (CPU-based, throttled to targetMjpegFps)
     * This is called by ImageAnalysis use case, separate from H.264 encoding
     * 
     * PERFORMANCE: Lightweight operations (FPS tracking, throttling) happen on the analyzer thread,
     * while expensive operations (rotation, annotation, JPEG compression) are offloaded to a
     * dedicated processing executor. This prevents blocking the CameraX analysis thread and
     * keeps the frame pipeline flowing smoothly.
     */
    private fun processMjpegFrame(image: ImageProxy) {
        // Skip processing if service is stopping
        if (isStopping) {
            Log.d(TAG, "Skipping frame processing - service is stopping")
            return
        }
        
        val processingStart = System.currentTimeMillis()
        
        try {
            if (awaitingFirstFrameForBoundCamera) {
                awaitingFirstFrameForBoundCamera = false
                clearBoundCameraStartupTimeout()
                val activeCameraId = boundCameraId ?: ensureSelectedCameraId()
                if (activeCameraId != null) {
                    lastKnownGoodCameraId = activeCameraId
                    markCameraCandidateState(activeCameraId, CameraCandidateState.WORKING, "first frame")
                }
                synchronized(cameraStateLock) {
                    cameraState = CameraState.ACTIVE
                }
                Log.i(TAG, "First frame received from cameraId=${activeCameraId ?: "unknown"} → ACTIVE state")
                broadcastCameraState()
                safeInvokeCameraStateCallback()
                maybeVerifySelectedCameraTorchCapability("first frame")
                if (desiredTorchEnabled) {
                    reconcileTorchState("first frame received")
                }
            }

            // === LIGHTWEIGHT OPERATIONS ON ANALYZER THREAD ===
            // These operations are fast and don't block the frame pipeline
            
            // Track Camera FPS FIRST (from ImageAnalysis callback rate - ALL frames including skipped)
            synchronized(fpsFrameTimes) {
                fpsFrameTimes.add(processingStart)
                // Keep only the last 2 seconds of frame times
                val cutoffTime = processingStart - 2000
                fpsFrameTimes.removeAll { it < cutoffTime }
                
                // Calculate FPS every 500ms
                if (processingStart - lastFpsCalculation > 500) {
                    if (fpsFrameTimes.size > 1) {
                        val timeSpan = fpsFrameTimes.last() - fpsFrameTimes.first()
                        val newFps = if (timeSpan > 0) {
                            (fpsFrameTimes.size - 1) * 1000f / timeSpan
                        } else {
                            0f
                        }
                        // Only broadcast if FPS changed significantly (more than 0.5 fps difference)
                        if (kotlin.math.abs(newFps - currentCameraFps) > 0.5f) {
                            currentCameraFps = newFps
                            broadcastCameraState()
                        } else {
                            currentCameraFps = newFps
                        }
                    }
                    lastFpsCalculation = processingStart
                }
            }
            
            // === MJPEG FPS Throttling ===
            // Skip frame if not enough time has passed since last processed frame
            val minFrameIntervalMs = (1000.0 / targetMjpegFps).toLong()
            val timeSinceLastFrame = processingStart - lastMjpegFrameProcessedTimeMs
            
            if (lastMjpegFrameProcessedTimeMs > 0 && timeSinceLastFrame < minFrameIntervalMs) {
                // Skip this frame to maintain target MJPEG FPS
                // Camera FPS already tracked above, so this only affects MJPEG stream FPS
                image.close()
                return
            }
            
            lastMjpegFrameProcessedTimeMs = processingStart
            
            // === OFFLOAD EXPENSIVE OPERATIONS TO PROCESSING EXECUTOR ===
            // Submit to dedicated processing executor to avoid blocking the analyzer thread
            // The ImageProxy must be closed by the processing task
            try {
                processingExecutor.execute {
                    processImageHeavyOperations(image, processingStart)
                }
            } catch (e: RejectedExecutionException) {
                // Executor queue is full or shutting down - close image and skip frame
                Log.w(TAG, "Processing executor rejected frame, skipping")
                image.close()
                performanceMetrics.recordFrameDropped()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in MJPEG frame analyzer", e)
            image.close()
            performanceMetrics.recordFrameDropped()
        }
    }
    
    /**
     * Heavy image processing operations (rotation, annotation, JPEG compression)
     * Runs on dedicated processing executor to avoid blocking CameraX analyzer thread
     * 
     * @param image ImageProxy to process (must be closed by this method)
     * @param processingStart Timestamp when frame processing started
     */
    private fun processImageHeavyOperations(image: ImageProxy, processingStart: Long) {
        // Skip processing if service is stopping
        if (isStopping) {
            Log.d(TAG, "Skipping heavy operations - service is stopping")
            image.close()
            return
        }
        
        // Use Kotlin's use{} extension to ensure image.close() is always called
        // This provides automatic resource management similar to Java's try-with-resources
        image.use {
            try {
                // === MJPEG Pipeline (CPU-based, throttled) ===
                // Convert RGBA to Bitmap for MJPEG (using pool for memory efficiency)
                val bitmap = imageProxyToBitmap(image)
                if (bitmap == null) {
                    // Failed to allocate bitmap, skip this frame
                    Log.w(TAG, "Skipping MJPEG frame due to bitmap allocation failure")
                    performanceMetrics.recordFrameDropped()
                    return
                }
                
                // Reduce logging frequency - only log every 30 frames (about 3 seconds at 10fps)
                val currentFrame = lastProcessedFrame.get()
                val frameCount = currentFrame?.timestamp?.toInt()?.rem(30) ?: 0
                if (frameCount == 0) {
                    Log.d(TAG, "Processing MJPEG frame - ImageProxy size: ${image.width}x${image.height}, Bitmap size: ${bitmap.width}x${bitmap.height}, Camera FPS: ${"%.1f".format(currentCameraFps)}")
                }
                
                // Apply camera orientation and rotation
                val finalBitmap = applyRotationCorrectly(bitmap)
                if (frameCount == 0) {
                    Log.d(TAG, "After rotation - Bitmap size: ${finalBitmap.width}x${finalBitmap.height}, Total rotation: ${(when (cameraOrientation) { "portrait" -> 90; else -> 0 } + rotation) % 360}°")
                }
                
                // Annotate bitmap (OSD overlays)
                // Note: annotateBitmap creates a new bitmap from pool, so finalBitmap can be cleaned up after
                val annotatedBitmap = annotateBitmap(finalBitmap)
                
                // Clean up finalBitmap after annotation using helper method
                // This handles both pooled and non-pooled bitmaps (e.g., rotated bitmaps)
                if (annotatedBitmap != null && finalBitmap != annotatedBitmap) {
                    bitmapPool.recycleBitmap(finalBitmap)
                }
                
                if (annotatedBitmap == null) {
                    // Failed to annotate, skip frame
                    Log.w(TAG, "Skipping MJPEG frame due to annotation failure")
                    performanceMetrics.recordFrameDropped()
                    return
                }
                
                val jpegQuality = JPEG_QUALITY_STREAM
                
                // Pre-compress to JPEG for HTTP serving
                val encodingStart = System.currentTimeMillis()
                val jpegBytes = ByteArrayOutputStream().use { stream ->
                    annotatedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                    stream.toByteArray()
                }
                val encodingTime = System.currentTimeMillis() - encodingStart
                
                // Track encoding performance
                performanceMetrics.recordFrameEncodingTime(encodingTime)
                
                // ATOMIC FRAME UPDATE: Create ProcessedFrame and atomically update reference
                // This ensures UI and web stream always display the same frame
                val timestamp = System.currentTimeMillis()
                val newFrame = ProcessedFrame(
                    bitmap = annotatedBitmap,
                    jpegBytes = jpegBytes,
                    timestamp = timestamp
                )
                
                // Atomically replace the old frame with the new one
                val oldFrame = lastProcessedFrame.getAndSet(newFrame)
                
                // Return old frame's bitmap to pool if it exists and is different
                if (oldFrame != null && oldFrame.bitmap != annotatedBitmap) {
                    bitmapPool.returnBitmap(oldFrame.bitmap)
                }
                
                // Track frame processing time
                val processingTime = System.currentTimeMillis() - processingStart
                performanceMetrics.recordFrameProcessingTime(processingTime)
                
                // Note: MJPEG FPS is tracked by HttpServer when frames are actually served to clients
                // Don't track here to avoid double-counting
                
                // Notify MainActivity only when it actively requested preview frames.
                if (onFrameAvailableCallback != null) {
                    val previewCopy = try {
                        bitmapPool.copy(annotatedBitmap, annotatedBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to copy bitmap for MainActivity preview", t)
                        null
                    }
                    if (previewCopy != null) {
                        // LIFECYCLE SAFETY: Use safe callback invocation to prevent crashes if MainActivity destroyed
                        safeInvokeFrameCallback(previewCopy)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing MJPEG frame in background", e)
                performanceMetrics.recordFrameDropped()
            } catch (t: Throwable) {
                // Catch OutOfMemoryError and other critical errors
                Log.e(TAG, "Critical error processing MJPEG frame in background", t)
                performanceMetrics.recordFrameDropped()
                // Clear bitmap pool on OOME to free memory
                if (t is OutOfMemoryError) {
                    Log.w(TAG, "OutOfMemoryError in frame processing, clearing bitmap pool")
                    bitmapPool.clear()
                }
            }
            // Note: image.close() is called automatically by use{} when this block exits
            // This happens regardless of normal completion, early return, or exception
        }
    }
    
    private fun applyRotationCorrectly(bitmap: Bitmap): Bitmap {
        // Calculate total rotation: camera orientation + manual rotation
        val baseRotation = when (cameraOrientation) {
            "portrait" -> 90
            "landscape" -> 0
            else -> 0
        }
        
        val totalRotation = (baseRotation + rotation) % 360
        
        if (totalRotation == 0) {
            return bitmap
        }
        
        // Use a properly sized matrix to avoid creating squared bitmaps
        val matrix = Matrix()
        matrix.postRotate(totalRotation.toFloat())
        
        return try {
            // NOTE: Bitmap.createBitmap with matrix creates bitmap outside pool
            // This is acceptable because:
            // 1. Native rotation is very efficient (hardware-accelerated)
            // 2. Rotation happens only on resolution/orientation changes (infrequent)
            // 3. Most frames don't need rotation (rotation == 0)
            // 4. Alternative (manual rotation with pool) would be slower
            // Future: Could implement pool-based rotation for frequently rotated streams
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            if (rotated != bitmap) {
                bitmapPool.returnBitmap(bitmap)
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Error rotating bitmap", t)
            bitmap
        }
    }
    
    private fun applyCameraOrientation(bitmap: Bitmap): Bitmap {
        // Determine the base rotation needed to achieve the desired camera orientation
        // Camera sensor is typically landscape-oriented by default
        val baseRotation = when (cameraOrientation) {
            "portrait" -> 90 // Rotate to portrait
            "landscape" -> 0 // Keep landscape (default sensor orientation)
            else -> 0
        }
        
        if (baseRotation == 0) {
            return bitmap
        }
        
        val matrix = Matrix()
        matrix.postRotate(baseRotation.toFloat())
        
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmapPool.returnBitmap(bitmap)
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying camera orientation", t)
            bitmap
        }
    }
    
    private fun applyRotation(bitmap: Bitmap): Bitmap {
        // Apply the user-specified rotation on top of the camera orientation
        if (rotation == 0) {
            return bitmap
        }
        
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Only return the original to pool if a new bitmap was created
            if (rotated != bitmap) {
                bitmapPool.returnBitmap(bitmap)
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Error rotating bitmap", t)
            bitmap
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        // With OUTPUT_IMAGE_FORMAT_RGBA_8888, ImageProxy provides RGBA data directly
        // This eliminates the inefficient YUV→NV21→JPEG→Bitmap conversion
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        // Get bitmap from pool or create new (with OOME protection)
        val bitmap = try {
            bitmapPool.get(image.width, image.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get bitmap from pool for ${image.width}x${image.height}", t)
            null
        }
        
        if (bitmap == null) {
            Log.e(TAG, "Unable to allocate bitmap for frame, skipping")
            return null
        }
        
        // If there's no row padding, we can copy directly
        if (rowPadding == 0) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // With row padding, copy row by row to exclude padding bytes
            buffer.rewind()
            val pixels = IntArray(image.width)
            
            for (row in 0 until image.height) {
                // Position at start of this row
                buffer.position(row * rowStride)
                
                // Read pixels for this row
                for (col in 0 until image.width) {
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    val a = buffer.get().toInt() and 0xFF
                    pixels[col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                // Set this row in the bitmap
                bitmap.setPixels(pixels, 0, image.width, 0, row, image.width, 1)
            }
        }

        return bitmap
    }

    private fun markCameraCandidateState(cameraId: String, state: CameraCandidateState, reason: String) {
        val group = getCameraGroupForRawId(cameraId)
        val changed = synchronized(cameraCatalogLock) {
            val previous = cameraCandidateStateById[cameraId]
            if (previous == state) {
                false
            } else {
                cameraCandidateStateById[cameraId] = state
                if (group != null) {
                    representativeCameraIdByGroupKey[group.groupKey] = chooseRepresentativeCameraIdLocked(
                        group,
                        preferredCameraId = representativeCameraIdByGroupKey[group.groupKey]
                    )
                }
                true
            }
        }
        if (!changed) {
            return
        }

        group?.let { normalizeResolutionMappingsForCameraGroups(listOf(it)) }
        ensureSelectedCameraId()
        val catalogChanged = publishCameraCatalogIfChanged(reason)
        if (catalogChanged) {
            saveSettings()
        }
    }

    private fun startBoundCameraStartupTimeout(expectedCameraId: String, expectedGroupKey: String) {
        boundCameraStartupTimeoutJob?.cancel()
        boundCameraStartupTimeoutJob = serviceScope.launch {
            delay(CAMERA_PROBE_TIMEOUT_MS)
            val stillAwaiting = awaitingFirstFrameForBoundCamera &&
                boundCameraId == expectedCameraId &&
                boundCameraGroupKey == expectedGroupKey
            if (!stillAwaiting) {
                return@launch
            }

            Log.w(
                TAG,
                "No first frame received from cameraId=$expectedCameraId within ${CAMERA_PROBE_TIMEOUT_MS}ms; " +
                    "marking candidate as broken and retrying group=$expectedGroupKey"
            )
            awaitingFirstFrameForBoundCamera = false
            markCameraCandidateState(expectedCameraId, CameraCandidateState.BROKEN, "first-frame timeout")

            val fallbackId = ensureSelectedCameraId()
            if (fallbackId == null) {
                synchronized(cameraStateLock) {
                    cameraState = CameraState.ERROR
                }
                broadcastCameraState()
                safeInvokeCameraStateCallback()
                return@launch
            }

            if (hasConsumers()) {
                requestBindCamera()
            }
        }
    }

    private fun clearBoundCameraStartupTimeout() {
        boundCameraStartupTimeoutJob?.cancel()
        boundCameraStartupTimeoutJob = null
    }

    private suspend fun applyTorchState(
        ownerCameraId: String,
        shouldEnable: Boolean,
        useCameraControl: Boolean,
        boundCamera: androidx.camera.core.Camera?,
        reason: String
    ): Boolean {
        return try {
            if (useCameraControl && boundCamera != null) {
                withContext(Dispatchers.IO) {
                    boundCamera.cameraControl.enableTorch(shouldEnable).get(
                        TORCH_PROBE_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                }
            } else {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.setTorchMode(ownerCameraId, shouldEnable)
            }
            true
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to ${if (shouldEnable) "enable" else "disable"} torch for cameraId=$ownerCameraId ($reason)",
                e
            )
            false
        }
    }

    private fun reconcileTorchState(reason: String) {
        if (isTorchOperationInProgress) {
            Log.d(TAG, "Torch operation already in progress, skipping reconcile ($reason)")
            return
        }

        val selectedId = ensureSelectedCameraId()
        val selectedGroupKey = getCameraGroupForRawId(selectedId)?.groupKey
        val shouldEnable = desiredTorchEnabled && isTorchAvailableForGroup(selectedGroupKey)
        val ownerCameraId = if (shouldEnable) {
            selectedId
        } else {
            effectiveTorchOwnerCameraId
        }

        if (ownerCameraId == null) {
            val changed = effectiveTorchEnabled || effectiveTorchOwnerCameraId != null
            effectiveTorchEnabled = false
            effectiveTorchOwnerCameraId = null
            if (changed) {
                broadcastCameraState()
                safeInvokeCameraStateCallback()
            }
            return
        }

        isTorchOperationInProgress = true
        val boundCamera = camera
        val useCameraControl = boundCamera != null && boundCameraId == ownerCameraId

        serviceScope.launch {
            val previousEffective = effectiveTorchEnabled
            val previousOwner = effectiveTorchOwnerCameraId
            val success = applyTorchState(
                ownerCameraId = ownerCameraId,
                shouldEnable = shouldEnable,
                useCameraControl = useCameraControl,
                boundCamera = boundCamera,
                reason = reason
            )

            if (success) {
                effectiveTorchEnabled = shouldEnable
                effectiveTorchOwnerCameraId = if (shouldEnable) ownerCameraId else null
            }

            isTorchOperationInProgress = false

            if (previousEffective != effectiveTorchEnabled || previousOwner != effectiveTorchOwnerCameraId) {
                broadcastCameraState()
                safeInvokeCameraStateCallback()
            }
        }
    }

    override fun selectCamera(cameraId: String): Boolean {
        val targetGroup = getVisibleCameraGroups()
            .firstOrNull { group -> group.candidates.any { it.cameraId == cameraId } }
        if (targetGroup == null) {
            Log.w(TAG, "Ignoring camera selection for unknown or unavailable cameraId=$cameraId")
            return false
        }

        val targetRepresentativeId = getRepresentativeCameraDescriptor(targetGroup)?.cameraId ?: return false
        val currentGroupKey = getCameraGroupForRawId(ensureSelectedCameraId())?.groupKey
        if (currentGroupKey == targetGroup.groupKey) {
            if (selectedCameraId != targetRepresentativeId) {
                selectedCameraId = targetRepresentativeId
                selectedResolution = resolutionByCameraId[targetRepresentativeId]
                saveSettings()
                broadcastCameraState()
                safeInvokeCameraStateCallback()
            }
            return true
        }

        val targetTorchCapability = getTorchCapabilityState(targetGroup.groupKey)
        if (targetTorchCapability == TorchCapabilityState.UNAVAILABLE && desiredTorchEnabled) {
            desiredTorchEnabled = false
            reconcileTorchState("selected camera changed to one without torch capability")
        }

        selectedCameraId = targetRepresentativeId
        selectedResolution = resolutionByCameraId[targetRepresentativeId]
        reconcileTorchState("selected camera changed")
        saveSettings()

        broadcastCameraState()
        safeInvokeCameraStateCallback()
        requestBindCamera()
        return true
    }
    
    /**
     * Initialize camera characteristics cache
     * 
     * Queries all available cameras once and caches their characteristics (facing, flash, resolutions).
     * This avoids repeated expensive IPC calls to CameraManager.getCameraCharacteristics().
     * Should be called once during service initialization.
     * Thread-safe: Uses volatile flag and synchronized block to prevent multiple initializations.
     */
    private fun initializeCameraCharacteristicsCache() {
        // Fast path: check if already initialized without synchronization
        if (cameraCharacteristicsCacheInitialized) {
            return
        }
        
        // Slow path: initialize with synchronization
        synchronized(cameraCharacteristicsCache) {
            // Double-check after acquiring lock
            if (cameraCharacteristicsCacheInitialized) {
                return
            }
            
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.cameraIdList.forEachIndexed { index, id ->
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) 
                            ?: CameraCharacteristics.LENS_FACING_BACK
                        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) 
                            ?: false
                        
                        // Get supported resolutions for this camera
                        val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val yuvResolutions = config?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
                            ?: emptyList()
                        val previewResolutions = config?.getOutputSizes(SurfaceTexture::class.java)?.toList()
                            ?: emptyList()
                        val previewSet = previewResolutions.map { it.width to it.height }.toSet()
                        val pipelineResolutions = yuvResolutions
                            .filter { (it.width to it.height) in previewSet }
                            .distinctBy { it.width to it.height }
                            .sortedByDescending { it.width * it.height }
                        val hardwareFingerprint = buildCameraFingerprint(
                            lensFacing = facing,
                            hasFlash = hasFlash,
                            focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                            sensorPhysicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
                            pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
                            capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                            supportedResolutions = pipelineResolutions
                        )
                        
                        val cached = CachedCameraCharacteristics(
                            cameraId = id,
                            ordinal = index,
                            lensFacing = facing,
                            hasFlash = hasFlash,
                            yuvOutputResolutions = yuvResolutions,
                            previewOutputResolutions = previewResolutions,
                            supportedResolutions = pipelineResolutions,
                            hardwareFingerprint = hardwareFingerprint
                        )
                        cameraCharacteristicsCache[id] = cached
                        
                        Log.d(
                            TAG,
                            "Cached characteristics for camera $id: facing=$facing, hasFlash=$hasFlash, " +
                                "yuvResolutions=${yuvResolutions.size}, previewResolutions=${previewResolutions.size}, " +
                                "pipelineResolutions=${pipelineResolutions.size}"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cache characteristics for camera $id", e)
                    }
                }
                Log.d(TAG, "Camera characteristics cache initialized with ${cameraCharacteristicsCache.size} cameras")
                cameraCharacteristicsCacheInitialized = true
                ensureSelectedCameraId()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing camera characteristics cache", e)
                // Don't set initialized flag on failure, allowing retry
            }
        }
    }
    
    /**
     * Toggle flashlight on/off
     * Works for any camera with flash unit capability
     * Does NOT require camera to be active - uses CameraManager directly when unbound
     */
    override fun toggleFlashlight(): Boolean {
        if (!isFlashlightAvailable()) {
            Log.w(TAG, "No flash unit available for current camera")
            return false
        }

        desiredTorchEnabled = !desiredTorchEnabled
        reconcileTorchState("flashlight toggled")
        saveSettings()

        broadcastCameraState()
        safeInvokeCameraStateCallback()
        return desiredTorchEnabled
    }
    
    /**
     * Set flashlight to specific state (on or off)
     * Returns true if state was set successfully, false otherwise
     * Does NOT require camera to be active - uses CameraManager directly
     */
    fun setFlashlight(enabled: Boolean): Boolean {
        if (!isFlashlightAvailable()) {
            Log.w(TAG, "No flash unit available")
            return false
        }

        if (desiredTorchEnabled != enabled) {
            desiredTorchEnabled = enabled
            reconcileTorchState("flashlight state updated")
            saveSettings()
            broadcastCameraState()
            safeInvokeCameraStateCallback()
        }

        return true
    }
    
    /**
     * Get current flashlight state
     */
    override fun isFlashlightEnabled(): Boolean = effectiveTorchEnabled
    
    /**
     * Check if flashlight is available (current camera has flash unit)
     */
    override fun isFlashlightAvailable(): Boolean {
        return isTorchAvailableForGroup(getCameraGroupForRawId(ensureSelectedCameraId())?.groupKey)
    }
    
    override fun getAvailableCameras(): List<CameraOption> = buildCameraOptions()

    override fun getSelectedCameraId(): String? = ensureSelectedCameraId()

    override fun getSelectedCameraLabel(): String {
        val selectedId = ensureSelectedCameraId()
        return buildCameraOptions().firstOrNull { it.cameraId == selectedId }?.displayName ?: "Not available"
    }

    override fun getSelectedCameraFacing(): String {
        val selectedId = ensureSelectedCameraId()
        return buildCameraOptions().firstOrNull { it.cameraId == selectedId }?.facing ?: "unknown"
    }

    override fun getCameraCatalogVersion(): Int = cameraCatalogVersion
    
    override fun getSupportedResolutions(): List<Size> {
        val cameraId = ensureSelectedCameraId() ?: return emptyList()
        return getSupportedResolutions(cameraId)
    }
    
    override fun getSelectedResolution(): Size? = selectedResolution
    
    /**
     * Update the resolution for the current camera in both selectedResolution
     * and the per-camera resolution variable.
     */
    private fun updateCurrentCameraResolution(resolution: Size?) {
        selectedResolution = resolution
        val cameraId = ensureSelectedCameraId() ?: return
        if (resolution == null) {
            resolutionByCameraId.remove(cameraId)
        } else {
            resolutionByCameraId[cameraId] = resolution
        }
    }
    
    fun setResolution(resolution: Size?) {
        updateCurrentCameraResolution(resolution)
        saveSettings()
        // DON'T call requestBindCamera() here!
        // This method just saves the resolution setting.
        // Callers must explicitly call requestBindCamera() if they want to rebind the camera.
        // If we call it here, it creates infinite loop:
        // bindCamera completes → callback → loadResolutions → setSelection → onItemSelected → setResolution → requestBindCamera → repeat
        // Note: requestBindCamera() has debouncing protection to prevent rapid successive calls
    }
    
    /**
     * Set resolution and trigger camera rebinding.
     * This is the recommended way for external callers (MainActivity, HTTP endpoints)
     * to change resolution, as it encapsulates both the setting change and rebinding.
     * 
     * SAFETY: Uses requestBindCamera() which has built-in debouncing protection.
     * Multiple rapid calls are automatically queued and executed safely.
     */
    override fun setResolutionAndRebind(resolution: Size?) {
        updateCurrentCameraResolution(resolution)
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()

        if (!hasConsumers()) {
            val currentState = synchronized(cameraStateLock) { cameraState }
            if (currentState == CameraState.IDLE || currentState == CameraState.ERROR) {
                Log.d(TAG, "Resolution updated with no active consumers, deferring camera rebind until next activation")
                return
            }
        }
        
        requestBindCamera()
    }
    
    override fun setCameraOrientation(orientation: String) {
        cameraOrientation = orientation
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of orientation change so it can reload resolutions
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback()
    }
    
    fun getCameraOrientation(): String = cameraOrientation
    
    override fun setRotation(rot: Int) {
        rotation = rot
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of rotation change
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback()
    }
    
    fun getRotation(): Int = rotation
    
    override fun setShowResolutionOverlay(show: Boolean) {
        showResolutionOverlay = show
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of overlay setting change
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback()
    }
    
    fun getShowResolutionOverlay(): Boolean = showResolutionOverlay
    
    // OSD overlay settings
    override fun setShowDateTimeOverlay(show: Boolean) {
        showDateTimeOverlay = show
        saveSettings()
        broadcastCameraState()
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback()
    }
    
    override fun getShowDateTimeOverlay(): Boolean = showDateTimeOverlay
    
    override fun setShowBatteryOverlay(show: Boolean) {
        showBatteryOverlay = show
        saveSettings()
        broadcastCameraState()
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback()
    }
    
    override fun getShowBatteryOverlay(): Boolean = showBatteryOverlay
    
    override fun setShowFpsOverlay(show: Boolean) {
        showFpsOverlay = show
        saveSettings()
        broadcastCameraState()
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback()
    }
    
    override fun getShowFpsOverlay(): Boolean = showFpsOverlay
    
    // FPS settings
    override fun setTargetMjpegFps(fps: Int) {
        val newFps = fps.coerceIn(1, 60)
        
        // Only update if value actually changed to avoid unnecessary broadcasts
        if (targetMjpegFps != newFps) {
            targetMjpegFps = newFps
            // Note: MJPEG FPS throttling is applied in processMjpegFrame()
            // No need to rebind camera - frame skipping handles the throttling
            saveSettings()
            broadcastCameraState()
            // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
            safeInvokeCameraStateCallback()
            Log.d(TAG, "Target MJPEG FPS set to $targetMjpegFps (throttling applied in frame processing)")
        }
    }
    
    override fun getTargetMjpegFps(): Int = targetMjpegFps
    
    override fun setTargetRtspFps(fps: Int) {
        val oldFps = targetRtspFps
        val newFps = fps.coerceIn(1, 60)
        
        // Only update if value actually changed
        if (oldFps != newFps) {
            targetRtspFps = newFps
            rtspServer?.updateEncoderConfig(targetFps = targetRtspFps)
            saveSettings()
            
            // Only a live RTSP pipeline needs an immediate rebind; otherwise the next RTSP lease
            // will pick up the new FPS automatically.
            if (hasBoundRtspPipeline()) {
                Log.d(TAG, "RTSP FPS changed from $oldFps to $targetRtspFps, rebinding active RTSP pipeline")
                broadcastCameraState()
                // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
                safeInvokeCameraStateCallback()
                requestBindCamera()
            } else {
                // No active RTSP pipeline, just broadcast the setting change
                broadcastCameraState()
                // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
                safeInvokeCameraStateCallback()
            }
        }
    }
    
    override fun getTargetRtspFps(): Int = targetRtspFps

    private fun rebindActiveRtspPipeline(reason: String) {
        if (hasBoundRtspPipeline()) {
            Log.d(TAG, "$reason, rebinding active RTSP pipeline")
            requestBindCamera()
        } else {
            Log.d(TAG, "$reason, no active RTSP pipeline bound; setting will apply on next RTSP activation")
        }
    }
    
    override fun getCurrentFps(): Float = currentCameraFps
    
    /**
     * Get current MJPEG streaming FPS (frames actually served to clients)
     */
    fun getCurrentMjpegFps(): Float = currentMjpegFps
    
    /**
     * Get current RTSP streaming FPS (frames actually encoded)
     */
    fun getCurrentRtspFps(): Float {
        // Return actual encoder output rate instead of frame queue rate
        return rtspServer?.getMetrics()?.encodedFps ?: 0f
    }
    
    /**
     * Record that a frame was served via MJPEG stream
     * Called by HttpServer when a frame is sent to a client
     */
    override fun recordMjpegFrameServed() {
        val now = System.currentTimeMillis()
        synchronized(mjpegFpsLock) {
            mjpegFpsFrameTimes.add(now)
            // Keep only the last 2 seconds of frame times
            val cutoffTime = now - 2000
            mjpegFpsFrameTimes.removeAll { it < cutoffTime }
            
            // Calculate FPS every 500ms
            if (now - lastMjpegFpsCalculation > 500) {
                if (mjpegFpsFrameTimes.size > 1) {
                    val timeSpan = mjpegFpsFrameTimes.last() - mjpegFpsFrameTimes.first()
                    val totalFps = if (timeSpan > 0) {
                        (mjpegFpsFrameTimes.size - 1) * 1000f / timeSpan
                    } else {
                        0f
                    }
                    
                    // Calculate average FPS per client (streaming FPS per client)
                    // This shows the actual streaming rate each client receives
                    val clientCount = getMjpegClientCount()
                    val newFps = if (clientCount > 0) {
                        totalFps / clientCount
                    } else {
                        0f
                    }
                    
                    // Only broadcast if FPS changed significantly (more than 0.5 fps difference)
                    if (kotlin.math.abs(newFps - currentMjpegFps) > 0.5f) {
                        currentMjpegFps = newFps
                        broadcastCameraState()
                    } else {
                        currentMjpegFps = newFps
                    }
                } else if (mjpegFpsFrameTimes.isEmpty() && currentMjpegFps != 0f) {
                    // No frames in window - reset to 0
                    currentMjpegFps = 0f
                    broadcastCameraState()
                }
                lastMjpegFpsCalculation = now
            }
        }
    }

    private fun resetMjpegFpsCounters(
        reason: String,
        onlyWhenIdle: Boolean = false,
        broadcastImmediately: Boolean = true
    ): Boolean {
        if (onlyWhenIdle && getMjpegClientCount() > 0) {
            return false
        }

        var changed = false
        synchronized(mjpegFpsLock) {
            if (currentMjpegFps != 0f || mjpegFpsFrameTimes.isNotEmpty()) {
                currentMjpegFps = 0f
                mjpegFpsFrameTimes.clear()
                lastMjpegFpsCalculation = 0L
                changed = true
                Log.d(TAG, "Reset MJPEG FPS to 0 ($reason)")
            }
        }

        if (changed && broadcastImmediately) {
            broadcastImmediateTelemetrySnapshot()
        }

        return changed
    }
    
    /**
     * Check and reset FPS counters when no clients are connected
     * Called periodically to ensure FPS displays accurately reflect current state
     */
    fun checkAndResetFpsCounters() {
        var needsMetricsBroadcast = false
        
        // Check MJPEG FPS - reset if no clients and FPS is not zero
        if (resetMjpegFpsCounters("no MJPEG clients", onlyWhenIdle = true, broadcastImmediately = false)) {
            needsMetricsBroadcast = true
        }
        
        // Check RTSP FPS - reset if no clients and FPS is not zero
        if (resetRtspFpsCounters("no RTSP clients", onlyWhenIdle = true, broadcastImmediately = false)) {
            needsMetricsBroadcast = true
        }
        
        if (needsMetricsBroadcast) {
            broadcastImmediateTelemetrySnapshot()
        }
    }
    
    /**
     * Record that a frame was encoded via RTSP
     * Called by RTSPServer when a frame is successfully encoded
     */
    fun recordRtspFrameEncoded() {
        val now = System.currentTimeMillis()
        synchronized(rtspFpsLock) {
            rtspFpsFrameTimes.add(now)
            // Keep only the last 2 seconds of frame times
            val cutoffTime = now - 2000
            rtspFpsFrameTimes.removeAll { it < cutoffTime }
            
            // Calculate FPS every 500ms
            if (now - lastRtspFpsCalculation > 500) {
                if (rtspFpsFrameTimes.size > 1) {
                    val timeSpan = rtspFpsFrameTimes.last() - rtspFpsFrameTimes.first()
                    val totalFps = if (timeSpan > 0) {
                        (rtspFpsFrameTimes.size - 1) * 1000f / timeSpan
                    } else {
                        0f
                    }
                    
                    // Note: RTSP encoding happens once and is broadcast to all clients
                    // So we don't divide by client count here - this represents the encoding FPS
                    // which is independent of the number of clients receiving the stream
                    val newFps = totalFps
                    
                    // Only broadcast if FPS changed significantly (more than 0.5 fps difference)
                    if (kotlin.math.abs(newFps - currentRtspFps) > 0.5f) {
                        currentRtspFps = newFps
                        broadcastCameraState()
                    } else {
                        currentRtspFps = newFps
                    }
                }
                lastRtspFpsCalculation = now
            }
        }
    }

    private fun resetRtspFpsCounters(
        reason: String,
        onlyWhenIdle: Boolean = false,
        broadcastImmediately: Boolean = true
    ): Boolean {
        if (onlyWhenIdle && getRtspClientCount() > 0) {
            return false
        }

        var changed = false
        synchronized(rtspFpsLock) {
            if (currentRtspFps != 0f || rtspFpsFrameTimes.isNotEmpty()) {
                currentRtspFps = 0f
                rtspFpsFrameTimes.clear()
                lastRtspFpsCalculation = 0L
                changed = true
                Log.d(TAG, "Reset RTSP FPS to 0 ($reason)")
            }
        }

        if (changed && broadcastImmediately) {
            broadcastImmediateTelemetrySnapshot()
        }

        return changed
    }
    
    /**
     * Get current CPU usage percentage
     */
    fun getCurrentCpuUsage(): Float {
        return currentCpuUsage
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        cameraOrientation = prefs.getString("cameraOrientation", "landscape") ?: "landscape"
        rotation = prefs.getInt("rotation", 0)
        
        // OSD overlay settings
        showDateTimeOverlay = prefs.getBoolean("showDateTimeOverlay", true)
        showBatteryOverlay = prefs.getBoolean("showBatteryOverlay", true)
        showResolutionOverlay = prefs.getBoolean("showResolutionOverlay", true)
        showFpsOverlay = prefs.getBoolean("showFpsOverlay", true)
        
        // FPS settings
        targetMjpegFps = prefs.getInt("targetMjpegFps", 10).coerceIn(1, 60)
        targetRtspFps = prefs.getInt("targetRtspFps", 30).coerceIn(1, 60)
        
        // Adaptive quality is currently removed from runtime logic.
        adaptiveQualityEnabled = false

        connectionLimits = if (
            prefs.contains(PREF_MAX_MJPEG_STREAMS) ||
            prefs.contains(PREF_MAX_SSE_CLIENTS) ||
            prefs.contains(PREF_MAX_RTSP_SESSIONS)
        ) {
            ConnectionLimits(
                maxMjpegStreams = prefs.getInt(PREF_MAX_MJPEG_STREAMS, ConnectionLimits.DEFAULT.maxMjpegStreams),
                maxSseClients = prefs.getInt(PREF_MAX_SSE_CLIENTS, ConnectionLimits.DEFAULT.maxSseClients),
                maxRtspSessions = prefs.getInt(PREF_MAX_RTSP_SESSIONS, ConnectionLimits.DEFAULT.maxRtspSessions)
            ).normalized()
        } else {
            val legacyMaxConnections = prefs.getInt(
                PREF_LEGACY_MAX_CONNECTIONS,
                ConnectionLimits.DEFAULT.maxMjpegStreams
            )
            ConnectionLimits.fromLegacyMaxConnections(legacyMaxConnections)
        }

        desiredTorchEnabled = when {
            prefs.contains("desiredTorchEnabled") -> prefs.getBoolean("desiredTorchEnabled", false)
            prefs.contains("flashlightOn") -> prefs.getBoolean("flashlightOn", false)
            else -> false
        }
        effectiveTorchEnabled = false
        effectiveTorchOwnerCameraId = null
        
        // NOTE: RTSP is now on-demand only, no persistence of enabled state
        // Only persist RTSP configuration (bitrate, mode) for when it's activated
        rtspBitrate = prefs.getInt("rtspBitrate", -1)
        rtspBitrateMode = prefs.getString("rtspBitrateMode", "VBR") ?: "VBR"
        
        // Load device name with default based on device model
        val defaultDeviceName = "IP_CAM_${Build.MODEL.replace(" ", "_")}"
        deviceName = prefs.getString("deviceName", defaultDeviceName) ?: defaultDeviceName
        
        resolutionByCameraId.clear()
        cameraCharacteristicsCache.keys.forEach { cameraId ->
            val width = prefs.getInt("cameraResolution.$cameraId.width", -1)
            val height = prefs.getInt("cameraResolution.$cameraId.height", -1)
            if (width > 0 && height > 0) {
                resolutionByCameraId[cameraId] = Size(width, height)
            }
        }

        // Legacy migration from front/back settings to cameraId-based storage.
        val oldResWidth = prefs.getInt("resolutionWidth", -1)
        val oldResHeight = prefs.getInt("resolutionHeight", -1)
        if (oldResWidth > 0 && oldResHeight > 0) {
            getDefaultCameraIdForFacing(CameraCharacteristics.LENS_FACING_BACK)?.let { backCameraId ->
                resolutionByCameraId.putIfAbsent(backCameraId, Size(oldResWidth, oldResHeight))
                Log.d(TAG, "Migrated legacy resolution ${oldResWidth}x${oldResHeight} to cameraId=$backCameraId")
            }
        }

        val legacyBackResWidth = prefs.getInt("backCameraResolutionWidth", -1)
        val legacyBackResHeight = prefs.getInt("backCameraResolutionHeight", -1)
        if (legacyBackResWidth > 0 && legacyBackResHeight > 0) {
            getDefaultCameraIdForFacing(CameraCharacteristics.LENS_FACING_BACK)?.let { backCameraId ->
                resolutionByCameraId.putIfAbsent(backCameraId, Size(legacyBackResWidth, legacyBackResHeight))
            }
        }

        val legacyFrontResWidth = prefs.getInt("frontCameraResolutionWidth", -1)
        val legacyFrontResHeight = prefs.getInt("frontCameraResolutionHeight", -1)
        if (legacyFrontResWidth > 0 && legacyFrontResHeight > 0) {
            getDefaultCameraIdForFacing(CameraCharacteristics.LENS_FACING_FRONT)?.let { frontCameraId ->
                resolutionByCameraId.putIfAbsent(frontCameraId, Size(legacyFrontResWidth, legacyFrontResHeight))
            }
        }

        val persistedSelectedCameraId = prefs.getString("selectedCameraId", null)
        val legacyCameraType = prefs.getString("cameraType", null)
        val migratedSelectedCameraId = when (legacyCameraType) {
            "front" -> getDefaultCameraIdForFacing(CameraCharacteristics.LENS_FACING_FRONT)
            "back" -> getDefaultCameraIdForFacing(CameraCharacteristics.LENS_FACING_BACK)
            else -> null
        }
        selectedCameraId = when {
            persistedSelectedCameraId != null && cameraCharacteristicsCache.containsKey(persistedSelectedCameraId) -> persistedSelectedCameraId
            migratedSelectedCameraId != null -> migratedSelectedCameraId
            else -> getDefaultCameraId()
        }

        selectedResolution = selectedCameraId?.let { resolutionByCameraId[it] }

        Log.d(
            TAG,
                "Loaded settings: cameraId=${selectedCameraId ?: "none"}, orientation=$cameraOrientation, rotation=$rotation, " +
                "resolution=${selectedResolution?.let { "${it.width}x${it.height}" } ?: "auto"}, " +
                "connectionLimits=$connectionLimits, desiredTorch=$desiredTorchEnabled, mjpegFps=$targetMjpegFps, " +
                "rtspFps=$targetRtspFps, rtspBitrate=$rtspBitrate, rtspBitrateMode=$rtspBitrateMode, " +
                "adaptiveQuality=$adaptiveQualityEnabled, deviceName=$deviceName"
        )
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("cameraOrientation", cameraOrientation)
            putInt("rotation", rotation)
            
            // OSD overlay settings
            putBoolean("showDateTimeOverlay", showDateTimeOverlay)
            putBoolean("showBatteryOverlay", showBatteryOverlay)
            putBoolean("showResolutionOverlay", showResolutionOverlay)
            putBoolean("showFpsOverlay", showFpsOverlay)
            
            // FPS settings
            putInt("targetMjpegFps", targetMjpegFps)
            putInt("targetRtspFps", targetRtspFps)
            
            // Keep deprecated setting persisted as disabled for compatibility.
            putBoolean("adaptiveQualityEnabled", false)

            putInt(PREF_MAX_MJPEG_STREAMS, connectionLimits.maxMjpegStreams)
            putInt(PREF_MAX_SSE_CLIENTS, connectionLimits.maxSseClients)
            putInt(PREF_MAX_RTSP_SESSIONS, connectionLimits.maxRtspSessions)
            remove(PREF_LEGACY_MAX_CONNECTIONS)
            remove("maxMjpegStreamsPerIp")
            putBoolean("desiredTorchEnabled", desiredTorchEnabled)
            remove("flashlightOn")
            
            // NOTE: RTSP enabled state is NOT persisted (on-demand only)
            putInt("rtspBitrate", rtspBitrate)
            putString("rtspBitrateMode", rtspBitrateMode)
            
            // Save device name
            putString("deviceName", deviceName)

            putString("selectedCameraId", selectedCameraId)
            cameraCharacteristicsCache.keys.forEach { cameraId ->
                val resolution = resolutionByCameraId[cameraId]
                if (resolution != null) {
                    putInt("cameraResolution.$cameraId.width", resolution.width)
                    putInt("cameraResolution.$cameraId.height", resolution.height)
                } else {
                    remove("cameraResolution.$cameraId.width")
                    remove("cameraResolution.$cameraId.height")
                }
            }

            // Remove legacy camera selection and resolution keys after migration to cameraId-based storage.
            remove("cameraType")
            remove("resolutionWidth")
            remove("resolutionHeight")
            remove("backCameraResolutionWidth")
            remove("backCameraResolutionHeight")
            remove("frontCameraResolutionWidth")
            remove("frontCameraResolutionHeight")
            
            apply()
        }
    }
    
    fun onDeviceOrientationChanged() {
        // Device orientation changes only affect the app UI, not the camera recording
        // This method is kept for compatibility but device orientation doesn't affect camera
    }
    
    private fun setupOrientationListener() {
        // Device orientation listener is disabled since camera recording is independent of device orientation
        // The camera orientation mode (portrait/landscape) is set manually or stays at default (landscape)
        orientationEventListener = null
    }
    
    fun registerActivityCallbacks(
        ownerId: String,
        onCameraStateChanged: () -> Unit,
        onConnectionsChanged: () -> Unit
    ) {
        activityCallbackOwnerId = ownerId
        onCameraStateChangedCallback = onCameraStateChanged
        onConnectionsChangedCallback = onConnectionsChanged
    }

    fun setPreviewFrameCallback(ownerId: String, callback: ((Bitmap) -> Unit)?) {
        if (activityCallbackOwnerId != ownerId) {
            Log.d(TAG, "Ignoring preview frame callback from stale owner=$ownerId")
            return
        }
        onFrameAvailableCallback = callback
    }

    fun releasePreviewBitmap(bitmap: Bitmap?) {
        bitmapPool.recycleBitmap(bitmap)
    }
    
    fun clearActivityCallbacks(ownerId: String) {
        if (activityCallbackOwnerId != ownerId) {
            Log.d(TAG, "Ignoring callback clear from stale owner=$ownerId")
            return
        }
        Log.d(TAG, "Clearing MainActivity callbacks for owner=$ownerId")
        onCameraStateChangedCallback = null
        onFrameAvailableCallback = null
        onConnectionsChangedCallback = null
        activityCallbackOwnerId = null
    }

    private fun clearAllActivityCallbacks() {
        onCameraStateChangedCallback = null
        onFrameAvailableCallback = null
        onConnectionsChangedCallback = null
        activityCallbackOwnerId = null
    }
    
    /**
     * Safely invoke camera state changed callback with lifecycle checks.
     * Only invokes if callback is set and we're not in a terminal lifecycle state.
     * 
     * LIFECYCLE SAFETY: Checks that callback exists and service is in a valid lifecycle state
     * before invoking to prevent crashes from callbacks to destroyed contexts.
     */
    private fun safeInvokeCameraStateCallback() {
        // Check if service is in valid lifecycle state (not DESTROYED)
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Skipping camera state callback - service lifecycle is DESTROYED")
            return
        }
        
        // Invoke callback if set
        onCameraStateChangedCallback?.invoke()
    }
    
    /**
     * Safely invoke frame available callback with lifecycle checks.
     * Only invokes if callback is set and we're not in a terminal lifecycle state.
     * 
     * LIFECYCLE SAFETY: Checks that callback exists and service is in a valid lifecycle state.
     * Bitmap is recycled if callback can't be invoked to prevent memory leaks.
     */
    private fun safeInvokeFrameCallback(bitmap: Bitmap) {
        // Check if service is in valid lifecycle state (not DESTROYED)
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Skipping frame callback - service lifecycle is DESTROYED, recycling bitmap")
            bitmapPool.returnBitmap(bitmap)
            return
        }
        
        // Invoke callback if set, otherwise recycle bitmap
        val callback = onFrameAvailableCallback
        if (callback != null) {
            callback(bitmap)
        } else {
            // No callback set - return bitmap to pool to prevent memory leak
            bitmapPool.returnBitmap(bitmap)
        }
    }
    
    /**
     * Safely invoke connections changed callback with lifecycle checks.
     * Only invokes if callback is set and we're not in a terminal lifecycle state.
     * 
     * LIFECYCLE SAFETY: Checks that callback exists and service is in a valid lifecycle state
     * before invoking to prevent crashes from callbacks to destroyed contexts.
     */
    private fun safeInvokeConnectionsCallback() {
        // Check if service is in valid lifecycle state (not DESTROYED)
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Skipping connections callback - service lifecycle is DESTROYED")
            return
        }
        
        // Invoke callback if set
        onConnectionsChangedCallback?.invoke()
    }

    private fun attachCameraStateObserver(boundCamera: androidx.camera.core.Camera) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                attachCameraStateObserver(boundCamera)
            }
            return
        }

        detachCameraStateObserver()

        val liveData = boundCamera.cameraInfo.cameraState
        val observer = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { cameraState ->
            Log.d(TAG, "Camera state changed: ${cameraState.type}, error: ${cameraState.error?.toString() ?: "none"}")

            when (cameraState.type) {
                androidx.camera.core.CameraState.Type.CLOSED -> {
                    Log.w(TAG, "Camera CLOSED state detected - camera may need rebinding")
                    // Don't automatically rebind here to avoid infinite loops
                    // Let the watchdog handle recovery via frame stale detection
                }
                androidx.camera.core.CameraState.Type.CLOSING -> {
                    Log.d(TAG, "Camera closing...")
                }
                androidx.camera.core.CameraState.Type.PENDING_OPEN -> {
                    Log.d(TAG, "Camera opening...")
                }
                androidx.camera.core.CameraState.Type.OPENING -> {
                    Log.d(TAG, "Camera is opening...")
                }
                androidx.camera.core.CameraState.Type.OPEN -> {
                    Log.i(TAG, "Camera is open and ready")
                    if (desiredTorchEnabled && isTorchAvailableForGroup(boundCameraGroupKey)) {
                        Log.d(TAG, "Camera opened - reconciling requested torch state")
                        reconcileTorchState("camera opened")
                    }
                }
            }

            cameraState.error?.let { error ->
                Log.e(TAG, "Camera error: code=${error.code}, ${error.cause?.message ?: "no cause"}", error.cause)

                if (camera != null &&
                    (error.code == androidx.camera.core.CameraState.ERROR_CAMERA_DISABLED ||
                     error.code == androidx.camera.core.CameraState.ERROR_CAMERA_FATAL_ERROR ||
                     error.code == androidx.camera.core.CameraState.ERROR_CAMERA_IN_USE)) {
                    Log.e(TAG, "Critical camera error detected, clearing camera reference for recovery")
                    boundCameraId?.let { failedCameraId ->
                        markCameraCandidateState(failedCameraId, CameraCandidateState.BROKEN, "critical camera error")
                    }
                    camera = null
                }
            }
        }

        liveData.observe(this, observer)
        cameraStateLiveData = liveData
        cameraStateObserver = observer
    }

    private fun detachCameraStateObserver() {
        val liveData = cameraStateLiveData
        val observer = cameraStateObserver
        if (liveData == null || observer == null) {
            return
        }

        cameraStateLiveData = null
        cameraStateObserver = null

        val detachAction = {
            try {
                liveData.removeObserver(observer)
            } catch (e: Exception) {
                Log.w(TAG, "Error detaching camera state observer", e)
            }
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            detachAction()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                detachAction()
            }
        }
    }
    
    override fun getActiveConnectionsCount(): Int {
        return getConnectionSnapshots().count { it.active }
    }
    
    override fun getMjpegClientCount(): Int {
        // MJPEG streaming clients: only count actual video streams (/stream endpoint)
        // SSE clients are for status updates, not video streaming
        return httpServer?.getActiveStreamsCount() ?: 0
    }
    
    override fun getRtspClientCount(): Int {
        // Count of active RTSP sessions
        return rtspServer?.getMetrics()?.playingSessions ?: 0
    }
    
    override fun getTotalCameraClientCount(): Int {
        // Total of MJPEG clients plus RTSP clients
        return getMjpegClientCount() + getRtspClientCount()
    }
    
    override fun getConnectionLimits(): ConnectionLimits = connectionLimits

    override fun updateConnectionLimits(limits: ConnectionLimits): Boolean {
        val normalizedLimits = limits.normalized()
        if (normalizedLimits == connectionLimits) {
            return false
        }

        connectionLimits = normalizedLimits
        saveSettings()
        broadcastCameraState()
        safeInvokeCameraStateCallback()
        safeInvokeConnectionsCallback()
        broadcastImmediateTelemetrySnapshot()
        return true
    }

    override fun getConnectionSnapshots(): List<ConnectionSnapshot> {
        val httpConnections = httpServer?.getConnectionSnapshots().orEmpty()
        val rtspConnections = rtspServer?.getConnectionSnapshots().orEmpty()
        return (httpConnections + rtspConnections).sortedBy { it.startTimeMs }
    }

    override fun closeConnection(connectionId: String): Boolean {
        val closed = httpServer?.closeConnection(connectionId) == true ||
            rtspServer?.closeConnection(connectionId) == true

        if (closed) {
            Log.d(TAG, "Manually closed connection $connectionId")
            onLongLivedConnectionsChanged()
        }

        return closed
    }

    override fun onLongLivedConnectionsChanged() {
        safeInvokeConnectionsCallback()
        broadcastImmediateTelemetrySnapshot()
    }
    
    fun isServerRunning(): Boolean = httpServer?.isAlive() == true
    
    fun stopServer() {
        try {
            serverIntentionallyStopped = true
            httpServer?.stop()
            httpServer = null
            updateNotification("Camera preview active. Server stopped.")
            
            // Broadcast state change to any remaining web clients before they disconnect
            broadcastCameraState()
            
            Log.d(TAG, "Server stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
            // Still update notification even if stop failed
            updateNotification("Camera preview active")
        }
    }
    
    /**
     * Restart the server by stopping and starting it.
     * Useful for applying configuration changes that require server restart,
     * or for recovering from server issues remotely.
     * Runs in background thread to avoid blocking.
     */
    override fun restartServer() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Restarting server...")
                stopServer()
                // Give server time to fully stop before restarting
                delay(500)
                startServer()
                Log.d(TAG, "Server restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting server", e)
            }
        }
    }
    
    override fun getServerUrl(): String {
        val ipAddress = getIpAddress()
        return "http://$ipAddress:$actualPort"
    }
    
    /**
     * Get ADB WiFi connection information
     */
    fun getADBConnectionInfo(): String {
        val adbInfo = wifiDebuggingManager?.getADBConnectionInfo()
        return if (adbInfo != null && adbInfo.enabled && adbInfo.isDeviceOwner) {
            val ipAddress = getIpAddress()
            "ADB: $ipAddress:${adbInfo.port}"
        } else {
            ""
        }
    }
    
    @Suppress("DEPRECATION")
    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        
        return if (ipInt != 0) {
            InetAddress.getByAddress(
                ByteBuffer.allocate(4).putInt(Integer.reverseBytes(ipInt)).array()
            ).hostAddress ?: "0.0.0.0"
        } else {
            "0.0.0.0"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() - cleaning up service resources")
        
        // LIFECYCLE MANAGEMENT: Transition to DESTROYED state FIRST to stop callback invocations
        // This prevents new callbacks from being invoked during cleanup
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        // Clear all MainActivity callbacks immediately to prevent memory leaks
        // This breaks the reference cycle between Service and Activity
        clearAllActivityCallbacks()

        torchVerificationJob?.cancel()
        torchVerificationJob = null
        clearBoundCameraStartupTimeout()
        val torchOwnerToDisable = effectiveTorchOwnerCameraId ?: boundCameraId ?: selectedCameraId
        desiredTorchEnabled = false
        if (effectiveTorchEnabled && torchOwnerToDisable != null) {
            Log.d(TAG, "Disabling torch before destroying service")
            runBlocking {
                applyTorchState(
                    ownerCameraId = torchOwnerToDisable,
                    shouldEnable = false,
                    useCameraControl = camera != null && boundCameraId == torchOwnerToDisable,
                    boundCamera = camera,
                    reason = "service destroy"
                )
            }
        }
        effectiveTorchEnabled = false
        effectiveTorchOwnerCameraId = null
        isTorchOperationInProgress = false
        
        unregisterNetworkReceiver()
        unregisterBatteryReceiver()
        orientationEventListener?.disable()
        httpServer?.stop()
        wifiDebuggingManager?.stopMonitoring()
        detachCameraStateObserver()
        cameraProvider?.unbindAll()
        
        // Shutdown executors in proper order
        // 1. Camera executor (stops frame capture)
        cameraExecutor.shutdown()
        
        // 2. Processing executor (stops frame processing)
        processingExecutor.shutdown()
        try {
            // Wait for pending processing tasks to complete (up to 2 seconds)
            if (!processingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Processing executor did not terminate cleanly, forcing shutdown")
                processingExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for processing executor to terminate")
            processingExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        clearLastProcessedFrame("service destroy")
        
        // Clear bitmap pool to free all memory
        bitmapPool.clear()
        Log.d(TAG, "Bitmap pool cleared on service destroy")
        
        telemetryJob?.cancel()
        // LIFECYCLE MANAGEMENT: Cancel all coroutines LAST after other cleanup
        // This ensures no coroutines try to use resources that have been cleaned up
        serviceScope.cancel()
        
        releaseLocks() // Use the new releaseLocks() method
        
        Log.i(TAG, "Service destroyed - all resources cleaned up")
    }
    
    /**
     * Determine battery management mode based on current battery level and charging status.
     * Implements hysteresis to prevent flapping between states.
     * 
     * State transitions:
     * - NORMAL → LOW_BATTERY: battery ≤ 20% and not charging
     * - LOW_BATTERY → CRITICAL_BATTERY: battery ≤ 10% and not charging
     * - CRITICAL_BATTERY → LOW_BATTERY: battery > 10% and (charging OR user override)
     * - LOW_BATTERY → NORMAL: battery > 20% OR charging OR (battery > 50% and was in CRITICAL)
     * - Any state → NORMAL: charging = true
     * 
     * @return Current battery management mode
     */
    private fun determineBatteryMode(batteryInfo: BatteryInfo): BatteryManagementMode {
        val batteryLevel = batteryInfo.level
        val isCharging = batteryInfo.isCharging
        
        // If charging, always return to NORMAL mode (unless in CRITICAL and battery still very low)
        if (isCharging) {
            // Only restore from CRITICAL if battery > CRITICAL threshold to avoid immediate re-critical on unplug
            return if (batteryMode == BatteryManagementMode.CRITICAL_BATTERY && batteryLevel < BATTERY_CRITICAL_PERCENT) {
                BatteryManagementMode.CRITICAL_BATTERY
            } else {
                BatteryManagementMode.NORMAL
            }
        }
        
        // Not charging - apply state machine with hysteresis
        return when (batteryMode) {
            BatteryManagementMode.NORMAL -> {
                when {
                    batteryLevel <= BATTERY_CRITICAL_PERCENT -> BatteryManagementMode.CRITICAL_BATTERY
                    batteryLevel <= BATTERY_LOW_PERCENT -> BatteryManagementMode.LOW_BATTERY
                    else -> BatteryManagementMode.NORMAL
                }
            }
            BatteryManagementMode.LOW_BATTERY -> {
                when {
                    batteryLevel <= BATTERY_CRITICAL_PERCENT -> BatteryManagementMode.CRITICAL_BATTERY
                    batteryLevel > BATTERY_LOW_PERCENT -> BatteryManagementMode.NORMAL
                    else -> BatteryManagementMode.LOW_BATTERY
                }
            }
            BatteryManagementMode.CRITICAL_BATTERY -> {
                when {
                    // Auto-recovery: battery charged to recovery threshold
                    batteryLevel >= BATTERY_RECOVERY_PERCENT -> {
                        userOverrideBatteryLimit = false // Clear override on full recovery
                        BatteryManagementMode.NORMAL
                    }
                    // User override: battery > 10% and user explicitly requested restore
                    batteryLevel > BATTERY_CRITICAL_PERCENT && userOverrideBatteryLimit -> {
                        BatteryManagementMode.LOW_BATTERY // Go to LOW mode first, not NORMAL
                    }
                    // Stay in critical if still below 10%
                    else -> BatteryManagementMode.CRITICAL_BATTERY
                }
            }
        }
    }
    
    /**
     * Update battery management mode and apply appropriate actions.
     * Called periodically by watchdog to monitor battery state and adjust behavior.
     */
    private fun updateBatteryManagement(batteryInfo: BatteryInfo = getBatteryInfo()): Boolean {
        val newMode = determineBatteryMode(batteryInfo)
        
        // Only log and take action if mode changed
        if (newMode != batteryMode) {
            val oldMode = batteryMode
            batteryMode = newMode
            
            Log.i(TAG, "Battery mode changed: $oldMode → $newMode (battery: ${batteryInfo.level}%, charging: ${batteryInfo.isCharging})")
            
            // Apply mode-specific actions
            when (newMode) {
                BatteryManagementMode.NORMAL -> {
                    // Restore full operation
                    acquireLocks()
                    // Note: Camera remains running, streaming resumes automatically
                    Log.i(TAG, "Full operation restored (battery: ${batteryInfo.level}%)")
                }
                BatteryManagementMode.LOW_BATTERY -> {
                    // Release wakelocks to reduce power consumption
                    releaseLocks()
                    // Camera and streaming continue, but device can sleep more aggressively
                    Log.w(TAG, "Low battery mode: wakelocks released (battery: ${batteryInfo.level}%)")
                }
                BatteryManagementMode.CRITICAL_BATTERY -> {
                    // Disable streaming to preserve battery
                    releaseLocks()
                    // Camera frames still captured but not served to clients (handled in HttpServer)
                    Log.e(TAG, "CRITICAL battery mode: streaming disabled to preserve battery (battery: ${batteryInfo.level}%)")
                    
                    // Show notification to user
                    showUserNotification(
                        "Critical Battery - Streaming Paused",
                        "Battery at ${batteryInfo.level}%. IP camera streaming has been paused to preserve battery. Please charge the device or streaming will resume automatically when battery reaches 50%."
                    )
                }
            }
            return true
        }
        return false
    }
    
    /**
     * Check if streaming should be allowed based on battery management mode.
     * Called by HttpServer to determine if streaming endpoints should serve frames or info page.
     * 
     * @return true if streaming is allowed, false if battery is too critical
     */
    override fun isStreamingAllowed(): Boolean {
        return batteryMode != BatteryManagementMode.CRITICAL_BATTERY
    }
    
    /**
     * Get current battery mode for status reporting
     */
    override fun getBatteryMode(): String {
        return batteryMode.name
    }
    
    /**
     * Get the critical battery threshold percentage
     */
    override fun getBatteryCriticalPercent(): Int {
        return BATTERY_CRITICAL_PERCENT
    }
    
    /**
     * Get the low battery threshold percentage
     */
    override fun getBatteryLowPercent(): Int {
        return BATTERY_LOW_PERCENT
    }
    
    /**
     * Get the recovery battery threshold percentage
     */
    override fun getBatteryRecoveryPercent(): Int {
        return BATTERY_RECOVERY_PERCENT
    }
    
    /**
     * Allow user to override critical battery mode and restore streaming.
     * Only works if battery > 10%. If battery drops below 10% again, critical mode re-activates.
     * 
     * @return true if override was successful, false if battery still too low
     */
    override fun overrideBatteryLimit(): Boolean {
        val batteryInfo = getBatteryInfo()
        val batteryLevel = batteryInfo.level
        
        // Can only override if battery > CRITICAL threshold (strictly greater than, not equal)
        if (batteryLevel <= BATTERY_CRITICAL_PERCENT) {
            Log.w(TAG, "Battery override rejected: battery too low ($batteryLevel% ≤ $BATTERY_CRITICAL_PERCENT%)")
            return false
        }
        
        // Can only override from CRITICAL mode
        if (batteryMode != BatteryManagementMode.CRITICAL_BATTERY) {
            Log.d(TAG, "Battery override not needed: already in $batteryMode mode")
            return true // Already operating normally
        }
        
        // Set override flag and update mode
        userOverrideBatteryLimit = true
        if (updateBatteryManagement()) {
            broadcastCameraState()
        }
        
        Log.i(TAG, "User override battery limit: streaming restored (battery: $batteryLevel%)")
        showUserNotification(
            "Streaming Restored",
            "Battery at $batteryLevel%. Streaming has been manually restored. It will pause again if battery drops below 10%."
        )
        
        return true
    }
    
    /**
     * Acquire wake locks for optimal streaming performance.
     * Only acquires locks when in NORMAL battery mode.
     * 
     * PARTIAL_WAKE_LOCK: Keeps CPU running for camera processing and streaming
     * WIFI_MODE_FULL_HIGH_PERF: Prevents WiFi from entering power save mode for consistent streaming
     */
    private fun acquireLocks() {
        val batteryInfo = getBatteryInfo()
        val batteryLevel = batteryInfo.level
        val isCharging = batteryInfo.isCharging
        
        // Only acquire locks in NORMAL mode
        if (batteryMode == BatteryManagementMode.NORMAL) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$TAG:WakeLock"
                ).apply {
                    acquire()
                    Log.i(TAG, "Wake lock acquired (battery: $batteryLevel%, charging: $isCharging, mode: $batteryMode)")
                }
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$TAG:WifiLock"
                ).apply {
                    acquire()
                    Log.i(TAG, "WiFi lock acquired (battery: $batteryLevel%, charging: $isCharging, mode: $batteryMode)")
                }
            }
        } else {
            // Ensure locks are released in non-NORMAL modes
            releaseLocks()
            Log.d(TAG, "Wake locks not acquired: battery mode is $batteryMode (battery: $batteryLevel%)")
        }
    }
    
    /**
     * Release wake locks to conserve battery
     */
    private fun releaseLocks() {
        val wasHeld = wakeLock?.isHeld == true || wifiLock?.isHeld == true
        
        wakeLock?.let { 
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wifiLock?.let { 
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WiFi lock released")
            }
        }
        
        if (wasHeld) {
            val batteryInfo = getBatteryInfo()
            Log.i(TAG, "All locks released (battery: ${batteryInfo.level}%, charging: ${batteryInfo.isCharging})")
        }
    }
    
    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(watchdogRetryDelay)
                
                var needsRecovery = false
                
                // Only check camera health if there are active consumers
                val hasActiveConsumers = hasConsumers()
                val currentCameraLifecycleState = synchronized(cameraStateLock) { cameraState }
                val bindingInProgress = synchronized(bindingLock) { isBindingInProgress }
                val now = System.currentTimeMillis()
                val startupGraceActive = isWithinCameraStartupGracePeriod(now)
                
                if (hasActiveConsumers) {
                    if (bindingInProgress) {
                        Log.d(TAG, "Watchdog: Managed rebind in progress, skipping recovery checks")
                    } else if (currentCameraLifecycleState == CameraState.INITIALIZING ||
                        currentCameraLifecycleState == CameraState.STOPPING) {
                        Log.d(TAG, "Watchdog: Camera transition in progress ($currentCameraLifecycleState), skipping recovery checks")
                    } else {
                        // Check camera provider health - ensure camera is initialized when consumers need it
                        // This handles cases where permission was granted after service started
                        if (cameraProvider == null) {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                this@CameraService, 
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            
                            if (hasPermission) {
                                Log.w(TAG, "Watchdog: Camera provider not initialized but consumers waiting, starting camera...")
                                noteCameraStartup("watchdog startCamera")
                                startCamera()
                                needsRecovery = true
                            } else {
                                // Permission not granted - don't count as recovery needed
                                // Log every 30 seconds to avoid spam
                                if (watchdogRetryDelay >= 30_000L) {
                                    Log.d(TAG, "Watchdog: Camera provider not initialized, waiting for permission...")
                                }
                            }
                        } else if (camera == null) {
                            // Camera provider exists but camera not bound, with active consumers
                            // This happens after ERROR_CAMERA_DISABLED or other binding failures
                            Log.w(TAG, "Watchdog: Camera provider exists but camera not bound (${getConsumerCount()} consumers), binding camera...")
                            
                            // CameraX requires main thread for binding operations
                            // Post to main thread to avoid IllegalStateException
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                try {
                                    noteCameraStartup("watchdog bindCamera")
                                    bindCamera()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Watchdog: Error binding camera from main thread", e)
                                }
                            }
                            needsRecovery = true
                        }
                        
                        // Check camera health - only if camera is bound
                        if (camera != null) {
                            if (startupGraceActive) {
                                Log.d(TAG, "Watchdog: Camera startup grace active, skipping frame health checks")
                            } else {
                                // ATOMIC FRAME ACCESS: Get timestamp from atomic reference
                                val currentFrame = lastProcessedFrame.get()
                                val lastTimestamp = currentFrame?.timestamp ?: 0L
                                val frameAge = now - lastTimestamp
                                
                                // Check 1: Frame stale (no new frames)
                                if (frameAge > FRAME_STALE_THRESHOLD_MS) {
                                    Log.w(TAG, "Watchdog: Frame stale (${frameAge}ms), restarting camera...")
                                    requestBindCamera()
                                    needsRecovery = true
                                    // Reset frozen frame detection since we're doing basic recovery
                                    frozenFrameDetectionCount = 0
                                    lastWatchdogFrameTimestamp = 0L
                                } else {
                                    // Check 2: Frozen frame detection (same frame being served repeatedly)
                                    // This catches cases where timestamp updates but content is frozen
                                    if (lastTimestamp > 0L) {
                                        if (lastTimestamp == lastWatchdogFrameTimestamp) {
                                            // Same frame as last check - increment frozen counter
                                            frozenFrameDetectionCount++
                                            Log.w(TAG, "Watchdog: Same frame detected (timestamp=$lastTimestamp) - frozen count: $frozenFrameDetectionCount/$FROZEN_FRAME_DETECTION_COUNT")
                                            
                                            // If frozen for too many consecutive checks, trigger full reset
                                            if (frozenFrameDetectionCount >= FROZEN_FRAME_DETECTION_COUNT) {
                                                Log.e(TAG, "Watchdog: Camera frozen detected (same frame $FROZEN_FRAME_DETECTION_COUNT times), performing FULL RESET...")
                                                serviceScope.launch {
                                                    val resetSuccess = fullCameraReset()
                                                    if (!resetSuccess) {
                                                        Log.e(TAG, "Watchdog: Full camera reset failed")
                                                    }
                                                }
                                                needsRecovery = true
                                                frozenFrameDetectionCount = 0
                                            }
                                        } else {
                                            // Different frame - camera is working, reset counter
                                            if (frozenFrameDetectionCount > 0) {
                                                Log.d(TAG, "Watchdog: Frame updated (new timestamp=$lastTimestamp), reset frozen counter")
                                            }
                                            frozenFrameDetectionCount = 0
                                        }
                                        lastWatchdogFrameTimestamp = lastTimestamp
                                    }
                                }
                                
                                // Check camera state - detect CLOSED state which indicates camera was released
                                val cameraState = try {
                                    camera?.cameraInfo?.cameraState?.value
                                } catch (e: Exception) {
                                    Log.w(TAG, "Watchdog: Error reading camera state", e)
                                    null
                                }
                                
                                if (cameraState?.type == androidx.camera.core.CameraState.Type.CLOSED) {
                                    Log.w(TAG, "Watchdog: Camera is in CLOSED state, rebinding...")
                                    requestBindCamera()
                                    needsRecovery = true
                                }
                            }
                        }
                    }
                } else {
                    // No active consumers - camera should be idle
                    // Log consumer count periodically for monitoring
                    if (watchdogRetryDelay >= 10_000L) {
                        synchronized(cameraStateLock) {
                            Log.d(TAG, "Watchdog: No consumers, camera state: $cameraState")
                        }
                    }
                }
                
                // Check server health - only restart if it wasn't intentionally stopped
                if (!serverIntentionallyStopped && httpServer?.isAlive() != true) {
                    Log.w(TAG, "Watchdog: Server not alive, restarting...")
                    startServer()
                    needsRecovery = true
                }
                
                // Check battery level and manage power/streaming accordingly
                // This is the main battery management check that runs every watchdog cycle
                updateBatteryManagement()
                
                // Exponential backoff for retry delay
                if (needsRecovery) {
                    watchdogRetryDelay = (watchdogRetryDelay * 2).coerceAtMost(WATCHDOG_MAX_RETRY_DELAY_MS)
                    Log.d(TAG, "Watchdog: Increasing retry delay to ${watchdogRetryDelay}ms")
                } else {
                    // Reset to initial delay when everything is healthy
                    if (watchdogRetryDelay != WATCHDOG_RETRY_DELAY_MS) {
                        watchdogRetryDelay = WATCHDOG_RETRY_DELAY_MS
                        Log.d(TAG, "Watchdog: System healthy, reset retry delay")
                    }
                }
            }
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            while (isActive) {
                val snapshot = sampleRuntimeTelemetry()
                latestRuntimeTelemetry = snapshot
                httpServer?.broadcastMetrics(snapshot)

                delay(TELEMETRY_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun sampleRuntimeTelemetry(nowMs: Long = System.currentTimeMillis()): RuntimeTelemetrySnapshot {
        val cpuUsage = performanceMetrics.getCpuUsage().processUsagePercent.toFloat()
        currentCpuUsage = cpuUsage

        val batteryInfo = getCachedBatteryInfo()
        val activeHttpStreams = httpServer?.getActiveStreamsCount() ?: 0
        val activeSseClients = httpServer?.getActiveSseClientsCount() ?: 0
        val rtspMetrics = rtspServer?.getMetrics()
        val activeRtspConnections = rtspMetrics?.activeSessions ?: 0
        val rtspPlayingSessions = rtspMetrics?.playingSessions ?: 0
        val currentRtspFpsForSnapshot = if (rtspEnabled && rtspPlayingSessions > 0) {
            currentRtspFps
        } else {
            0f
        }

        return runtimeTelemetrySampler.sample(
            cpuUsagePercent = cpuUsage,
            currentCameraFps = currentCameraFps,
            currentMjpegFps = currentMjpegFps,
            currentRtspFps = currentRtspFpsForSnapshot,
            activeHttpStreams = activeHttpStreams,
            activeSseClients = activeSseClients,
            activeRtspConnections = activeRtspConnections,
            rtspPlayingSessions = rtspPlayingSessions,
            totalCameraClients = activeHttpStreams + rtspPlayingSessions,
            totalLongLivedConnections = activeHttpStreams + activeSseClients + activeRtspConnections,
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.isCharging,
            nowMs = nowMs
        )
    }

    private fun broadcastImmediateTelemetrySnapshot(nowMs: Long = System.currentTimeMillis()) {
        val batteryInfo = getCachedBatteryInfo()
        val activeHttpStreams = httpServer?.getActiveStreamsCount() ?: 0
        val activeSseClients = httpServer?.getActiveSseClientsCount() ?: 0
        val rtspMetrics = rtspServer?.getMetrics()
        val activeRtspConnections = rtspMetrics?.activeSessions ?: 0
        val rtspPlayingSessions = rtspMetrics?.playingSessions ?: 0
        val currentRtspFpsForSnapshot = if (rtspEnabled && rtspPlayingSessions > 0) {
            currentRtspFps
        } else {
            0f
        }

        val snapshot = latestRuntimeTelemetry.copy(
            timestampMs = nowMs,
            currentCameraFps = currentCameraFps,
            currentMjpegFps = currentMjpegFps,
            currentRtspFps = currentRtspFpsForSnapshot,
            activeHttpStreams = activeHttpStreams,
            activeSseClients = activeSseClients,
            activeRtspConnections = activeRtspConnections,
            rtspPlayingSessions = rtspPlayingSessions,
            totalCameraClients = activeHttpStreams + rtspPlayingSessions,
            totalLongLivedConnections = activeHttpStreams + activeSseClients + activeRtspConnections,
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.isCharging
        )

        latestRuntimeTelemetry = snapshot
        httpServer?.broadcastMetrics(snapshot)
    }
    
    private fun registerNetworkReceiver() {
        val filter = IntentFilter().apply {
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        
        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    android.net.ConnectivityManager.CONNECTIVITY_ACTION,
                    android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        Log.d(TAG, "Network state changed, checking server...")
                        serviceScope.launch {
                            delay(2000) // Wait for network to stabilize
                            if (httpServer?.isAlive() != true) {
                                Log.d(TAG, "Network recovered, restarting server")
                                startServer()
                            }
                        }
                    }
                }
            }
        }
        
        registerReceiver(networkReceiver, filter)
        Log.d(TAG, "Network receiver registered")
    }
    
    private fun unregisterNetworkReceiver() {
        networkReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Network receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network receiver", e)
            }
        }
        networkReceiver = null
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val batteryInfo = batteryInfoFromIntent(intent)
                updateBatteryCache(batteryInfo)
                if (updateBatteryManagement(batteryInfo)) {
                    broadcastCameraState()
                }
            }
        }

        val initialIntent = registerReceiver(batteryReceiver, filter)
        if (initialIntent != null) {
            val batteryInfo = batteryInfoFromIntent(initialIntent)
            updateBatteryCache(batteryInfo)
            updateBatteryManagement(batteryInfo)
        }
        Log.d(TAG, "Battery receiver registered")
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Battery receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering battery receiver", e)
            }
        }
        batteryReceiver = null
    }
    
    /**
     * Annotate bitmap with OSD overlays (date/time, battery, FPS, resolution).
     * Creates a mutable copy from the bitmap pool for drawing annotations.
     * 
     * @param source Source bitmap to annotate (immutable)
     * @return Annotated bitmap (mutable) from pool, or null if allocation fails or source is recycled
     */
    private fun annotateBitmap(source: Bitmap): Bitmap? {
        // Safety check: return null if already recycled
        if (source.isRecycled) {
            Log.w(TAG, "annotateBitmap called with recycled bitmap")
            return null
        }
        
        // Use bitmap pool for memory-efficient copy with OOME protection
        val mutable = try {
            bitmapPool.copy(source, source.config ?: Bitmap.Config.ARGB_8888, true)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to copy bitmap for annotation", t)
            null
        }
        
        if (mutable == null) {
            Log.e(TAG, "Unable to create mutable bitmap for annotation")
            return null
        }
        
        val canvas = Canvas(mutable)
        // Use cached density to avoid Binder calls from HTTP threads
        val density = cachedDensity
        val padding = 8f * density
        val textSize = 14f * density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            style = Paint.Style.FILL
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
        }
        
        fun drawLabel(text: String, alignRight: Boolean, topOffset: Float, bottomAlign: Boolean = false) {
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            val left = if (alignRight) {
                canvas.width - padding - textWidth - padding
            } else {
                padding
            }
            
            val verticalPos = if (bottomAlign) {
                // Bottom aligned - calculate from bottom of canvas
                val bottom = canvas.height.toFloat() - padding
                bottom - padding - textPaint.fontMetrics.bottom
            } else {
                // Top aligned - use topOffset
                val bottom = topOffset + textHeight + padding
                bottom - padding - textPaint.fontMetrics.bottom
            }
            
            canvas.drawText(text, left + padding, verticalPos, textPaint)
        }
        
        // Prepare OSD text elements
        val timeText = if (showDateTimeOverlay) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        } else null
        
        val batteryText = if (showBatteryOverlay) {
            val batteryInfo = getCachedBatteryInfo()
            buildString {
                append(batteryInfo.level)
                append("%")
                if (batteryInfo.isCharging) append(" ⚡")
            }
        } else null
        
        val fpsText = if (showFpsOverlay) {
            // Show MJPEG streaming FPS (actual frames served to clients) instead of camera FPS
            "%.1f fps".format(currentMjpegFps)
        } else null
        
        val resolutionText = if (showResolutionOverlay) {
            "${source.width}x${source.height}"
        } else null
        
        // Calculate widths to check for overlap (only for top row)
        val timeWidth = timeText?.let { textPaint.measureText(it) + padding * 3 } ?: 0f
        val batteryWidth = batteryText?.let { textPaint.measureText(it) + padding * 3 } ?: 0f
        val availableWidth = canvas.width.toFloat()
        
        // Check if top labels would overlap
        val wouldOverlap = (timeWidth + batteryWidth) > availableWidth
        val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
        
        // Draw date/time in top left (if enabled)
        if (timeText != null) {
            drawLabel(timeText, alignRight = false, topOffset = padding)
        }
        
        // Draw battery in top right (if enabled)
        if (batteryText != null) {
            if (wouldOverlap && timeText != null) {
                // Stack vertically if they would overlap and both are shown
                drawLabel(batteryText, alignRight = true, topOffset = padding + textHeight + padding * 2)
            } else {
                // Draw side by side
                drawLabel(batteryText, alignRight = true, topOffset = padding)
            }
        }
        
        // Draw FPS in bottom left (if enabled)
        if (fpsText != null) {
            drawLabel(fpsText, alignRight = false, topOffset = 0f, bottomAlign = true)
        }
        
        // Draw resolution in bottom right (if enabled)
        if (resolutionText != null) {
            drawLabel(resolutionText, alignRight = true, topOffset = 0f, bottomAlign = true)
        }
        
        return mutable
    }
    
    private data class BatteryInfo(val level: Int, val isCharging: Boolean)
    
    private fun batteryInfoFromIntent(intent: Intent?): BatteryInfo {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            0
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryInfo(percentage, isCharging)
    }

    private fun updateBatteryCache(batteryInfo: BatteryInfo) {
        cachedBatteryInfo = batteryInfo
        lastBatteryUpdate = System.currentTimeMillis()
    }
    
    private fun getCachedBatteryInfo(): BatteryInfo {
        return cachedBatteryInfo
    }
    
    private fun getBatteryInfo(): BatteryInfo {
        return cachedBatteryInfo
    }
    
    override fun sizeLabel(size: Size): String = "${size.width}$RESOLUTION_DELIMITER${size.height}"
    
    private fun getSupportedResolutions(cameraId: String): List<Size> {
        val descriptor = cameraCharacteristicsCache[cameraId] ?: return emptyList()

        return descriptor.supportedResolutions.filter { size ->
            val isLandscape = size.width > size.height
            val isPortrait = size.height > size.width

            when (cameraOrientation) {
                "landscape" -> isLandscape || size.width == size.height
                "portrait" -> isPortrait || size.width == size.height
                else -> true
            }
        }.sortedByDescending { it.width * it.height }
    }
    
    // ==================== CameraServiceInterface Implementation ====================
    
    override fun getLastFrameJpegBytes(): ByteArray? {
        // ATOMIC FRAME ACCESS: Retrieve JPEG bytes from atomic reference
        // This ensures we get the JPEG bytes that correspond to the current frame
        return lastProcessedFrame.get()?.jpegBytes
    }
    
    override fun getSelectedResolutionLabel(): String {
        return selectedResolution?.let { sizeLabel(it) } ?: "auto"
    }
    
    private fun buildCameraStateMap(): Map<String, Any?> {
        val cameraId = ensureSelectedCameraId()
        val selectedCamera = cameraId?.let { cameraCharacteristicsCache[it] }
        val cameraLabel = buildCameraOptions().firstOrNull { it.cameraId == cameraId }?.displayName ?: "Not available"
        val cameraFacing = selectedCamera?.let { lensFacingToLabel(it.lensFacing) } ?: "unknown"
        val resolutionLabel = selectedResolution?.let { sizeLabel(it) } ?: "auto"
        val rtspEnabled = isRTSPEnabled()
        val limits = connectionLimits

        return mapOf(
            "cameraId" to cameraId,
            "cameraLabel" to cameraLabel,
            "cameraFacing" to cameraFacing,
            "cameraCatalogVersion" to cameraCatalogVersion,
            "resolution" to resolutionLabel,
            "cameraOrientation" to cameraOrientation,
            "rotation" to rotation,
            "showDateTimeOverlay" to showDateTimeOverlay,
            "showBatteryOverlay" to showBatteryOverlay,
            "showResolutionOverlay" to showResolutionOverlay,
            "showFpsOverlay" to showFpsOverlay,
            "targetMjpegFps" to targetMjpegFps,
            "targetRtspFps" to targetRtspFps,
            "maxMjpegStreams" to limits.maxMjpegStreams,
            "maxSseClients" to limits.maxSseClients,
            "maxRtspSessions" to limits.maxRtspSessions,
            "adaptiveQualityEnabled" to false,
            "flashlightAvailable" to isFlashlightAvailable(),
            "flashlightOn" to isFlashlightEnabled(),
            "batteryMode" to batteryMode.name,
            "streamingAllowed" to isStreamingAllowed(),
            "rtspEnabled" to rtspEnabled
        )
    }

    private fun toJsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Boolean, is Int, is Float -> value.toString()
            else -> "\"$value\""
        }
    }

    override fun getCameraStateJson(): String {
        val json = buildCameraStateMap().entries.joinToString(",") { (key, value) ->
            "\"$key\":${toJsonValue(value)}"
        }
        return "{$json}"
    }

    /**
     * Get camera state JSON with only changed values (delta broadcasting)
     * This reduces bandwidth and prevents unnecessary UI updates for unchanged values
     * Returns null if nothing has changed since last broadcast
     */
    override fun getCameraStateDeltaJson(): String? {
        val currentState = buildCameraStateMap()
        
        // Find changed fields
        val changes = mutableListOf<String>()
        
        synchronized(broadcastLock) {
            currentState.forEach { (key, value) ->
                val lastValue = lastBroadcastState[key]
                val hasChanged = when (value) {
                    is Float -> lastValue == null || kotlin.math.abs(value - (lastValue as? Float ?: 0f)) > 0.01f
                    else -> lastValue != value
                }
                
                if (hasChanged) {
                    val jsonValue = toJsonValue(value)
                    changes.add("\"$key\":$jsonValue")
                    // Update last broadcast state
                    lastBroadcastState[key] = value
                }
            }
        }
        
        // If nothing changed, return null
        if (changes.isEmpty()) {
            return null
        }
        
        // Build JSON with only changed fields
        return "{${changes.joinToString(",")}}"
    }
    
    /**
     * Initialize last broadcast state with current values
     * Called when a new SSE client connects to prevent sending full state again on next delta
     */
    override fun initializeLastBroadcastState() {
        synchronized(broadcastLock) {
            lastBroadcastState.clear()
            lastBroadcastState.putAll(buildCameraStateMap())
        }
    }
    
    override fun recordStreamingBytes(transport: StreamTransport, bytes: Long) {
        runtimeTelemetrySampler.recordBytes(transport, bytes)
    }

    override fun getDetailedStats(): String {
        val cpuStats = performanceMetrics.createCpuStats(latestRuntimeTelemetry.cpuUsagePercent.toDouble())
        val performanceStats = performanceMetrics.getDetailedStats(cpuStats)
        
        return """
            ${latestRuntimeTelemetry.toDebugString()}
            
            $performanceStats
        """.trimIndent()
    }
    
    override fun getCpuUsagePercent(): Double {
        return latestRuntimeTelemetry.cpuUsagePercent.toDouble()
    }
    
    override fun getBandwidthBps(): Long {
        return latestRuntimeTelemetry.bandwidthBps
    }

    override fun getRuntimeTelemetrySnapshot(): RuntimeTelemetrySnapshot {
        return latestRuntimeTelemetry
    }
    
    override fun setAdaptiveQualityEnabled(enabled: Boolean) {
        adaptiveQualityEnabled = false
        saveSettings()
        Log.w(TAG, "Adaptive quality toggle ignored - feature is deprecated")
    }
    
    // ==================== RTSP Streaming Control ====================
    
    /**
     * Enable RTSP streaming
     */
    override fun enableRTSPStreaming(): Boolean {
        if (rtspEnabled && rtspServer != null && rtspServer?.isAlive() == true) {
            Log.d(TAG, "RTSP already enabled and server is running")
            broadcastCameraState()
            broadcastImmediateTelemetrySnapshot()
            return true
        }
        
        if (rtspEnabled) {
            Log.w(TAG, "RTSP flag was set but server not running, restarting...")
            disableRTSPStreaming()
        }
        
        try {
            // Check if hardware encoder is available
            if (!RTSPServer.isHardwareEncoderAvailable()) {
                Log.w(TAG, "Hardware encoder not available, RTSP may use software fallback")
                InMemoryLogBuffer.add("W", TAG, "Hardware encoder not available, RTSP may use software fallback")
            }
            
            // Get current camera resolution for encoder
            val resolution = selectedResolution ?: run {
                // Use a default resolution if none is set
                Log.d(TAG, "No resolution set, using 1920x1080 for RTSP")
                Size(1920, 1080)
            }
            
            // Determine bitrate to use: saved value if valid, otherwise calculate default
            val bitrateToUse = if (rtspBitrate > 0) {
                Log.d(TAG, "Using saved RTSP bitrate: $rtspBitrate bps")
                rtspBitrate
            } else {
                val calculated = RTSPServer.calculateBitrate(resolution.width, resolution.height)
                Log.d(TAG, "Using calculated RTSP bitrate: $calculated bps")
                calculated
            }
            
            // Create RTSP server with saved/calculated settings
            rtspServer = RTSPServer(
                port = 8554,
                width = resolution.width,
                height = resolution.height,
                initialFps = targetRtspFps,  // Use saved target FPS instead of hardcoded 30
                initialBitrate = bitrateToUse,
                initialBitrateMode = rtspBitrateMode,
                cameraService = this@CameraService  // Pass reference for FPS tracking
            )
            
            if (rtspServer?.start() != true) {
                val startError = rtspServer?.getMetrics()?.lastError ?: "unknown error"
                Log.e(TAG, "Failed to start RTSP server: $startError")
                InMemoryLogBuffer.add("E", TAG, "Failed to start RTSP server: $startError")
                rtspServer = null
                rtspEnabled = false
                saveSettings()
                return false
            }
            
            rtspEnabled = true
            
            // NOTE: Camera is NOT activated here - it stays idle.
            // RTSP server just starts listening for connections.
            // Camera/pipeline will activate on-demand when a client acquires an RTSP session lease.
            
            // Reset RTSP FPS counter when enabling to start fresh
            resetRtspFpsCounters("RTSP enabled", broadcastImmediately = false)
            
            saveSettings()
            Log.i(TAG, "RTSP server started on port 8554 (fps=$targetRtspFps, bitrate=$bitrateToUse, mode=$rtspBitrateMode)")
            Log.i(TAG, "Server is idling - camera will activate when clients connect and send PLAY")
            InMemoryLogBuffer.add("I", TAG, "RTSP server started on port 8554 (fps=$targetRtspFps, bitrate=$bitrateToUse, mode=$rtspBitrateMode)")
            
            // DO NOT rebind camera here - it will activate on-demand when clients connect
            broadcastCameraState()
            broadcastImmediateTelemetrySnapshot()
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling RTSP streaming", e)
            InMemoryLogBuffer.add("E", TAG, "Error enabling RTSP streaming: ${e.message}")
            rtspEnabled = false
            rtspServer = null
            saveSettings()
            return false
        }
    }
    
    /**
     * Disable RTSP streaming
     */
    override fun disableRTSPStreaming() {
        if (!rtspEnabled) {
            Log.w(TAG, "RTSP already disabled")
            broadcastCameraState()
            broadcastImmediateTelemetrySnapshot()
            return
        }
        
        try {
            rtspEnabled = false
            
            // Stop RTSP server (this will unregister consumers if clients were connected)
            rtspServer?.stop()
            rtspServer = null
            
            // NOTE: Consumer unregistration is handled by RTSPServer.stop()
            // which checks for active playing sessions and unregisters accordingly
            
            // Reset RTSP FPS counter to avoid showing stale values
            resetRtspFpsCounters("RTSP disabled", broadcastImmediately = false)
            
            saveSettings()
            Log.i(TAG, "RTSP streaming disabled")
            broadcastCameraState()
            broadcastImmediateTelemetrySnapshot()
            
            // Rebind only if a camera is still active for other consumers and the RTSP pipeline
            // is currently attached. Otherwise stopCamera()/next bind will handle cleanup.
            if (hasConsumers() && hasBoundRtspPipeline()) {
                Log.d(TAG, "Rebinding camera to remove active H.264 encoder pipeline")
                requestBindCamera()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling RTSP streaming", e)
        }
    }
    
    /**
     * Check if RTSP is enabled
     */
    override fun isRTSPEnabled(): Boolean = rtspEnabled
    
    /**
     * Get RTSP server metrics
     */
    override fun getRTSPMetrics(): RTSPServer.ServerMetrics? {
        return rtspServer?.getMetrics()
    }
    
    /**
     * Get RTSP URL
     */
    override fun getRTSPUrl(): String {
        val ipAddress = getServerUrl().substringAfter("http://").substringBefore(":")
        return "rtsp://$ipAddress:8554/stream"
    }
    
    /**
     * Set RTSP bitrate
     */
    override fun setRTSPBitrate(bitrate: Int): Boolean {
        if (bitrate <= 0) {
            Log.w(TAG, "Cannot set RTSP bitrate: invalid bitrate=$bitrate")
            return false
        }

        rtspBitrate = bitrate
        rtspServer?.updateEncoderConfig(bitrate = bitrate)
        saveSettings()
        rebindActiveRtspPipeline("RTSP bitrate changed to $bitrate")
        broadcastImmediateTelemetrySnapshot()
        return true
    }
    
    /**
     * Set RTSP bitrate mode (VBR/CBR/CQ)
     */
    override fun setRTSPBitrateMode(mode: String): Boolean {
        val normalizedMode = when (mode.uppercase()) {
            "VBR", "CBR", "CQ" -> mode.uppercase()
            else -> {
                Log.w(TAG, "Cannot set RTSP bitrate mode: invalid mode=$mode")
                return false
            }
        }

        rtspBitrateMode = normalizedMode
        rtspServer?.updateEncoderConfig(bitrateMode = normalizedMode)
        saveSettings()
        rebindActiveRtspPipeline("RTSP bitrate mode changed to $normalizedMode")
        broadcastImmediateTelemetrySnapshot()
        return true
    }
    
    // ==================== End RTSP Streaming Control ====================
    
    /**
     * Broadcast camera state changes to all SSE clients via HttpServer
     */
    private fun broadcastCameraState() {
        httpServer?.broadcastCameraState()
    }
    
    // ==================== Device Identification ====================
    
    /**
     * Get the user-defined device name
     */
    override fun getDeviceName(): String = deviceName
    
    /**
     * Set the user-defined device name and persist it
     */
    override fun setDeviceName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isNotEmpty()) {
            deviceName = trimmedName
            saveSettings()
            Log.d(TAG, "Device name updated to: $deviceName")
            
            // Update notification to reflect new device name
            updateNotification(getNotificationText())
            
            // Broadcast state change to web clients
            broadcastCameraState()
        }
    }
    
    // ==================== Consumer Management ====================
    
    /**
     * Register a consumer (preview, MJPEG, RTSP)
     * Activates camera if this is the first consumer
     */
    private fun registerConsumer(type: ConsumerType) {
        synchronized(consumersLock) {
            val totalConsumersBefore = consumers.values.sum()
            val nextCount = consumers.getOrDefault(type, 0) + 1
            consumers[type] = nextCount
            val totalConsumersAfter = totalConsumersBefore + 1
            Log.d(
                TAG,
                "Registered consumer: $type, typeCount=$nextCount, total consumers: $totalConsumersAfter"
            )
            
            if (totalConsumersBefore == 0) {
                Log.d(TAG, "First consumer registered, activating camera...")
                activateCameraForConsumers()
            }
        }
    }
    
    /**
     * Unregister a consumer (preview, MJPEG, RTSP)
     * Deactivates camera if this was the last consumer
     */
    private fun unregisterConsumer(type: ConsumerType) {
        synchronized(consumersLock) {
            val currentCount = consumers[type]
            if (currentCount == null) {
                Log.w(TAG, "Ignoring unregister for non-existent consumer: $type")
                return
            }

            if (currentCount <= 1) {
                consumers.remove(type)
            } else {
                consumers[type] = currentCount - 1
            }

            val remainingConsumers = consumers.values.sum()
            Log.d(
                TAG,
                "Unregistered consumer: $type, typeCount=${consumers[type] ?: 0}, remaining consumers: $remainingConsumers"
            )
            
            if (remainingConsumers == 0) {
                Log.d(TAG, "Last consumer unregistered, deactivating camera...")
                deactivateCameraForConsumers()
            }
        }
    }
    
    /**
     * Check if camera has any active consumers
     */
    fun hasConsumers(): Boolean {
        synchronized(consumersLock) {
            return consumers.isNotEmpty()
        }
    }
    
    /**
     * Get consumer count
     */
    fun getConsumerCount(): Int {
        synchronized(consumersLock) {
            return consumers.values.sum()
        }
    }
    
    /**
     * Activate camera when first consumer appears
     */
    private fun activateCameraForConsumers() {
        synchronized(cameraStateLock) {
            when (cameraState) {
                CameraState.IDLE -> {
                    Log.d(TAG, "Camera IDLE → INITIALIZING (on-demand activation)")
                    cameraState = CameraState.INITIALIZING
                    noteCameraStartup("consumer activation from IDLE")
                    serviceScope.launch {
                        delay(CAMERA_ACTIVATION_DELAY_MS)
                        startCamera()
                    }
                }
                CameraState.ERROR -> {
                    Log.d(TAG, "Camera in ERROR state, retrying initialization...")
                    cameraState = CameraState.INITIALIZING
                    noteCameraStartup("consumer activation from ERROR")
                    serviceScope.launch {
                        delay(CAMERA_ACTIVATION_DELAY_MS)
                        startCamera()
                    }
                }
                CameraState.INITIALIZING -> {
                    Log.d(TAG, "Camera already initializing, waiting...")
                }
                CameraState.ACTIVE -> {
                    Log.d(TAG, "Camera already active")
                }
                CameraState.STOPPING -> {
                    Log.d(TAG, "Camera stopping, will restart after stopping completes")
                    // Will be handled by the stopping process
                }
            }
        }
    }
    
    /**
     * Deactivate camera when last consumer disconnects
     * Double-checks consumer count for safety
     */
    private fun deactivateCameraForConsumers() {
        synchronized(cameraStateLock) {
            // Double-check that there are really no consumers
            // This prevents race conditions where a new consumer registered
            // between the check in unregisterConsumer and this call
            if (hasConsumers()) {
                Log.d(TAG, "Consumers still present (${getConsumerCount()}), skipping deactivation")
                return
            }
            
            when (cameraState) {
                CameraState.ACTIVE, CameraState.INITIALIZING -> {
                    Log.d(TAG, "Camera $cameraState → STOPPING (no consumers)")
                    cameraState = CameraState.STOPPING
                    serviceScope.launch {
                        stopCamera(reactivateConsumersAfterStop = false)
                    }
                }
                CameraState.IDLE -> {
                    Log.d(TAG, "Camera already idle")
                }
                CameraState.STOPPING -> {
                    Log.d(TAG, "Camera already stopping")
                }
                CameraState.ERROR -> {
                    // On ERROR state, mark as IDLE to allow re-initialization when next consumer appears
                    // This is safe because:
                    // 1. No consumers need the camera right now
                    // 2. Next consumer registration will trigger fresh initialization
                    // 3. ERROR state is preserved in logs for debugging
                    Log.d(TAG, "Camera in ERROR state with no consumers, resetting to IDLE for next activation attempt")
                    cameraState = CameraState.IDLE
                }
            }
        }
    }
    
    // Consumer registration methods for HttpServer interface
    override fun registerMjpegConsumer() {
        registerConsumer(ConsumerType.MJPEG)
    }
    
    override fun unregisterMjpegConsumer() {
        unregisterConsumer(ConsumerType.MJPEG)
        resetMjpegFpsCounters("last MJPEG client disconnected", onlyWhenIdle = true)
    }
    
    override fun registerSnapshotConsumer() {
        registerConsumer(ConsumerType.SNAPSHOT)
    }
    
    override fun unregisterSnapshotConsumer() {
        unregisterConsumer(ConsumerType.SNAPSHOT)
    }
    
    override fun getCameraStateString(): String {
        synchronized(cameraStateLock) {
            return cameraState.name
        }
    }
    
    // Public methods for MainActivity to register/unregister preview consumer
    fun registerPreviewConsumer() {
        registerConsumer(ConsumerType.PREVIEW)
    }
    
    fun unregisterPreviewConsumer() {
        unregisterConsumer(ConsumerType.PREVIEW)
    }
    
    // Public methods for RTSP client connections to register/unregister consumers
    fun registerRtspConsumer() {
        Log.d(TAG, "RTSP client connected, registering consumer")
        registerConsumer(ConsumerType.RTSP)
        refreshRtspPipelineIfNeeded("RTSP consumer registered")
    }
    
    fun unregisterRtspConsumer() {
        Log.d(TAG, "RTSP client disconnected, unregistering consumer")
        unregisterConsumer(ConsumerType.RTSP)
        refreshRtspPipelineIfNeeded("RTSP consumer unregistered")
        resetRtspFpsCounters("last RTSP client disconnected")
    }
    
    // Manual activation methods implementing interface (for testing/debugging)
    override fun manualActivateCamera() {
        Log.d(TAG, "Manual camera activation requested")
        registerConsumer(ConsumerType.MANUAL)
    }
    
    override fun manualDeactivateCamera() {
        Log.d(TAG, "Manual camera deactivation requested")
        unregisterConsumer(ConsumerType.MANUAL)
    }

    override fun getLogs(): String = InMemoryLogBuffer.getText()
}
