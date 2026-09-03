package ai.sealgate.stdiod.mcp

import ai.sealgate.stdiod.CameraSettings
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Production [CameraSource] over the framework Camera2 API.
 *
 * Headless: a single still capture through an [ImageReader] surface, no
 * preview activity and no new dependencies. Every precondition failure
 * (toggle off, permission denied, no lens, camera busy) is an in-band
 * [CameraOperationResult] error, never a crash.
 */
class AndroidCameraSource(context: Context) : CameraSource {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    override fun status(): CameraOperationResult = CameraOperationResult(
        payload = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("enabled", JsonPrimitive(CameraSettings.isEnabled(appContext)))
            put("has_permission", JsonPrimitive(hasPermission()))
            put("camera_count", JsonPrimitive(cameraIds().size))
            put("lenses", buildJsonArray { lenses().forEach { add(JsonPrimitive(it)) } })
        },
    )

    override fun list(): CameraOperationResult {
        if (!CameraSettings.isEnabled(appContext)) return gated("camera capture is disabled in Mobile Tunnel")
        if (!hasPermission()) return gated("camera permission is not granted")
        val cameras = describeCameras() ?: return gated("cameras are unavailable on this device")
        if (cameras.isEmpty()) return gated("this device has no cameras")
        return CameraOperationResult(
            payload = buildJsonObject {
                put("ok", JsonPrimitive(true))
                put(
                    "cameras",
                    buildJsonArray {
                        cameras.forEach { camera ->
                            add(
                                buildJsonObject {
                                    put("camera_id", JsonPrimitive(camera.cameraId))
                                    put("lens", JsonPrimitive(camera.lens))
                                    put("flash_available", JsonPrimitive(camera.flashAvailable))
                                    put("max_zoom", JsonPrimitive(camera.maxZoom))
                                    put(
                                        "jpeg_sizes",
                                        buildJsonArray {
                                            camera.jpegSizes.forEach { size ->
                                                add(
                                                    buildJsonObject {
                                                        put("width", JsonPrimitive(size.width))
                                                        put("height", JsonPrimitive(size.height))
                                                    },
                                                )
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    override fun snap(options: CameraSnapOptions): CameraOperationResult {
        if (!CameraSettings.isEnabled(appContext)) return gated("camera capture is disabled in Mobile Tunnel")
        if (!hasPermission()) return gated("camera permission is not granted")
        val quality = options.quality ?: DEFAULT_QUALITY
        val zoom = options.zoom ?: 1.0
        return capture(options.lens, options.flash, zoom, options.width, options.height, quality)
    }

    private fun gated(reason: String): CameraOperationResult = CameraOperationResult(
        payload = buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive(reason))
        },
        error = reason,
    )

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun cameraIds(): List<String> = try {
        cameraManager.cameraIdList.toList()
    } catch (_: CameraAccessException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun lenses(): List<String> = describeCameras().orEmpty().map(CameraDescription::lens).distinct()

    private fun describeCameras(): List<CameraDescription>? = try {
        cameraManager.cameraIdList.mapNotNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            val lens = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                else -> return@mapNotNull null
            }
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width * it.height }
                .orEmpty()
            if (sizes.isEmpty()) return@mapNotNull null
            CameraDescription(
                cameraId = cameraId,
                lens = lens,
                flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1f,
                jpegSizes = sizes,
            )
        }
    } catch (_: CameraAccessException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun capture(
        lens: String,
        flash: String,
        zoom: Double,
        width: Int?,
        height: Int?,
        quality: Int,
    ): CameraOperationResult {
        val camera = describeCameras()?.firstOrNull { it.lens == lens }
            ?: return gated("no $lens camera is available on this device")
        if (flash == "on" && !camera.flashAvailable) {
            return gated("flash is unavailable on the $lens camera")
        }
        val characteristics = try {
            cameraManager.getCameraCharacteristics(camera.cameraId)
        } catch (_: CameraAccessException) {
            return gated("the $lens camera is unavailable")
        } catch (_: SecurityException) {
            return gated("camera permission is not granted")
        }
        val size = pickSize(camera.jpegSizes, width, height)
        val background = HandlerThread("camera-snap").apply { start() }
        val handler = Handler(background.looper)
        try {
            val device = openDevice(camera.cameraId, handler) ?: return gated("the $lens camera is busy or unavailable")
            try {
                val bytes = captureStill(device, characteristics, camera, size, flash, zoom, quality, handler)
                    ?: return gated("the $lens camera did not return a photo")
                return encodeResult(bytes, camera, lens, size, flash, zoom, quality)
            } finally {
                runCatching { device.close() }
            }
        } finally {
            background.quitSafely()
        }
    }

    private fun openDevice(cameraId: String, handler: Handler): CameraDevice? {
        // The user can revoke the runtime permission at any moment: the
        // SecurityException catch stays in this function so lint's
        // MissingPermission check is satisfied at the call site.
        val latch = CountDownLatch(1)
        val holder = AtomicReference<CameraDevice?>()
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        holder.set(device)
                        latch.countDown()
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        latch.countDown()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        latch.countDown()
                    }
                },
                handler,
            )
        } catch (_: CameraAccessException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (!latch.await(OPEN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return null
        return holder.get()
    }

    private fun captureStill(
        device: CameraDevice,
        characteristics: CameraCharacteristics,
        camera: CameraDescription,
        size: Size,
        flash: String,
        zoom: Double,
        quality: Int,
        handler: Handler,
    ): ByteArray? {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, MAX_IMAGES)
        val imageLatch = CountDownLatch(1)
        val imageBytes = AtomicReference<ByteArray?>()
        reader.setOnImageAvailableListener(
            { _ ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: IllegalStateException) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes.firstOrNull()?.buffer ?: return@setOnImageAvailableListener
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    imageBytes.compareAndSet(null, bytes)
                } finally {
                    image.close()
                    imageLatch.countDown()
                }
            },
            handler,
        )
        try {
            val sessionLatch = CountDownLatch(1)
            val sessionHolder = AtomicReference<CameraCaptureSession?>()
            // createCaptureSession is deprecated on API 30+ in favour of the
            // SessionConfiguration overload, but the deprecated call works on
            // every API level this app supports (26+) and keeps one code path.
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sessionHolder.set(session)
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionLatch.countDown()
                    }
                },
                handler,
            )
            if (!sessionLatch.await(SESSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) return null
            val session = sessionHolder.get() ?: return null
            try {
                val request = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.JPEG_QUALITY, quality.toByte())
                    applyFlash(this, characteristics, flash)
                    applyZoom(this, characteristics, camera, zoom)
                }.build()
                val captureLatch = CountDownLatch(1)
                session.capture(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: android.hardware.camera2.TotalCaptureResult,
                        ) {
                            captureLatch.countDown()
                        }
                    },
                    handler,
                )
                captureLatch.await(CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } finally {
                runCatching { session.close() }
            }
        } catch (_: CameraAccessException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } finally {
            // The image listener may never fire when capture fails; the
            // reader must still be released so the camera is not leaked.
            imageLatch.await(IMAGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            runCatching { reader.close() }
        }
        return imageBytes.get()
    }

    private fun applyFlash(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        flash: String,
    ) {
        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            return
        }
        when (flash) {
            "on" -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            }
            "off" -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }
        }
    }

    private fun applyZoom(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        camera: CameraDescription,
        zoom: Double,
    ) {
        val clamped = zoom.coerceIn(1.0, camera.maxZoom.toDouble().coerceAtLeast(1.0))
        if (clamped <= 1.0) return
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cropWidth = (active.width() / clamped).toInt().coerceAtLeast(1)
        val cropHeight = (active.height() / clamped).toInt().coerceAtLeast(1)
        val left = active.left + (active.width() - cropWidth) / 2
        val top = active.top + (active.height() - cropHeight) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
    }

    private fun pickSize(sizes: List<Size>, width: Int?, height: Int?): Size {
        if (width != null && height != null) {
            sizes.firstOrNull { it.width >= width && it.height >= height }
                ?.let { return it }
            return sizes.minByOrNull { longerEdge(it) } ?: sizes.first()
        }
        val bound = sizes.firstOrNull { longerEdge(it) <= TARGET_LONG_EDGE } ?: sizes.last()
        if (width != null) {
            return sizes.filter { longerEdge(it) <= TARGET_LONG_EDGE }
                .minByOrNull { kotlin.math.abs(it.width - width) } ?: bound
        }
        if (height != null) {
            return sizes.filter { longerEdge(it) <= TARGET_LONG_EDGE }
                .minByOrNull { kotlin.math.abs(it.height - height) } ?: bound
        }
        return bound
    }

    private fun longerEdge(size: Size): Int = maxOf(size.width, size.height)

    private fun encodeResult(
        bytes: ByteArray,
        camera: CameraDescription,
        lens: String,
        size: Size,
        flash: String,
        zoom: Double,
        quality: Int,
    ): CameraOperationResult {
        val encoded = fitToBudget(bytes, quality) ?: return gated("the photo could not fit the attachment limit")
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, dimensions)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        val payload = buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("lens", JsonPrimitive(lens))
            put("camera_id", JsonPrimitive(camera.cameraId))
            put("width", JsonPrimitive(dimensions.outWidth.takeIf { it > 0 } ?: size.width))
            put("height", JsonPrimitive(dimensions.outHeight.takeIf { it > 0 } ?: size.height))
            put("flash", JsonPrimitive(flash))
            put("zoom", JsonPrimitive(zoom))
            put("quality", JsonPrimitive(quality))
            put("mime_type", JsonPrimitive(MIME_TYPE))
            put("encoded_bytes", JsonPrimitive(encoded.size))
            put("sha256", JsonPrimitive(digest))
            put("downscaled", JsonPrimitive(encoded.size != bytes.size))
        }
        return CameraOperationResult(
            payload = payload,
            photo = CameraPhoto(Base64.encodeToString(encoded, Base64.NO_WRAP), MIME_TYPE),
        )
    }

    private fun fitToBudget(bytes: ByteArray, quality: Int): ByteArray? {
        if (bytes.size <= MAX_PHOTO_BYTES) return bytes
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            var currentQuality = quality.coerceIn(1, 100)
            var encoded = encodeJpeg(bitmap, currentQuality)
            while (encoded.size > MAX_PHOTO_BYTES && currentQuality > MIN_REENCODE_QUALITY) {
                currentQuality -= 8
                encoded = encodeJpeg(bitmap, currentQuality.coerceAtLeast(MIN_REENCODE_QUALITY))
            }
            while (encoded.size > MAX_PHOTO_BYTES && maxOf(bitmap.width, bitmap.height) > MIN_LONG_EDGE) {
                val scale = (TARGET_LONG_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(0.85f)
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
                currentQuality = DEFAULT_REENCODE_QUALITY
                encoded = encodeJpeg(bitmap, currentQuality)
            }
            return encoded.takeIf { it.size <= MAX_PHOTO_BYTES }
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "JPEG encoder failed" }
            output.toByteArray()
        }

    private data class CameraDescription(
        val cameraId: String,
        val lens: String,
        val flashAvailable: Boolean,
        val maxZoom: Float,
        val jpegSizes: List<Size>,
    )

    private companion object {
        const val MIME_TYPE = "image/jpeg"
        const val DEFAULT_QUALITY = 85
        const val DEFAULT_REENCODE_QUALITY = 78
        const val MIN_REENCODE_QUALITY = 65
        const val MAX_PHOTO_BYTES = 1536 * 1024
        const val TARGET_LONG_EDGE = 1_920
        const val MIN_LONG_EDGE = 1_280
        const val MAX_IMAGES = 2
        const val OPEN_TIMEOUT_MILLIS = 8_000L
        const val SESSION_TIMEOUT_MILLIS = 8_000L
        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
        const val IMAGE_TIMEOUT_MILLIS = 10_000L
    }
}
