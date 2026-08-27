package com.gdrop.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Callback
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private var laptopBaseUrl: String = "http://192.168.137.1:8000"
    private var pairedLaptopName: String = "Not paired"
    private var currentToken: String? = null
    private var currentTransferName: String = ""
    private var lastReceivedUri: Uri? = null

    private val receivedTransferNames = mutableSetOf<String>()

    private var cameraProvider: ProcessCameraProvider? = null
    private var scannerRunning = false

    @Volatile
    private var cancelRequested: Boolean = false

    // Notifications
    private val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "gdrop_downloads"
    private val DOWNLOAD_NOTIFICATION_ID = 1001
    private lateinit var notificationManager: NotificationManager

    // Storage permission handling for older Android versions.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }

        if (granted) {
            Toast.makeText(
                this,
                "Storage access ready",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                "Storage permission required for receiving files",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Camera permission for QR scanner.
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openQrScanner()
        } else {
            Log.w(
                "GDrop",
                "QR_SCAN_FAILED: camera permission denied"
            )

            Toast.makeText(
                this,
                "Camera permission is required. Use browser pairing instead.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Notification permission for Android 13+.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(
                "GDrop",
                "NOTIFICATION_PERMISSION_GRANTED"
            )
        } else {
            Log.w(
                "GDrop",
                "NOTIFICATION_PERMISSION_DENIED"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Notification setup.
        notificationManager =
            getSystemService(NotificationManager::class.java)

        createDownloadNotificationChannel()

        // Android 13+ notification permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }

        binding.pairButton.setOnClickListener {
            startQrScan()
        }

        binding.refreshButton.setOnClickListener {
            loadDeviceInfo()
            refreshTransfers()
        }

        binding.downloadButton.setOnClickListener {
            if (currentToken == null) {
                Toast.makeText(
                    this,
                    "Pair with a laptop first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            downloadLatestFile()
        }

        ensureStoragePermission()
        loadDeviceInfo()
        refreshTransfers()
    }

    private fun ensureStoragePermission() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    // ---------------------------------------------------------
    // QR PAIRING
    // ---------------------------------------------------------

    private fun startQrScan() {
        Log.i(
            "GDrop",
            "PAIRING_STARTED"
        )

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openQrScanner()
        } else {
            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun openQrScanner() {
        Log.i(
            "GDrop",
            "QR_SCANNER_OPENED"
        )

        binding.scannerPreview.visibility = View.VISIBLE
        scannerRunning = true

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            binding.scannerPreview.surfaceProvider
                        )
                    }

                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                            Barcode.FORMAT_QR_CODE
                        )
                        .build()
                )

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()

                analysis.setAnalyzer(
                    ContextCompat.getMainExecutor(this)
                ) { imageProxy ->
                    analyzeQrFrame(
                        scanner,
                        imageProxy
                    )
                }

                cameraProvider?.unbindAll()

                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

            } catch (e: Exception) {

                scannerRunning = false
                binding.scannerPreview.visibility = View.GONE

                Log.e(
                    "GDrop",
                    "QR_SCAN_FAILED: unable to open camera",
                    e
                )

                Toast.makeText(
                    this,
                    "Unable to open camera. Use browser pairing instead.",
                    Toast.LENGTH_LONG
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeQrFrame(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        imageProxy: ImageProxy
    ) {

        val mediaImage = imageProxy.image

        if (
            mediaImage == null ||
            !scannerRunning
        ) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(image)
            .addOnSuccessListener { barcodes ->

                val rawValue =
                    barcodes.firstOrNull()?.rawValue

                if (
                    !rawValue.isNullOrBlank() &&
                    scannerRunning
                ) {
                    scannerRunning = false

                    cameraProvider?.unbindAll()

                    binding.scannerPreview.visibility =
                        View.GONE

                    Log.i(
                        "GDrop",
                        "QR_SCANNED"
                    )

                    handlePairingUrl(rawValue)
                }
            }
            .addOnFailureListener { error ->

                Log.w(
                    "GDrop",
                    "QR_SCAN_FAILED: ${error.message}"
                )
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handlePairingUrl(
        rawUrl: String
    ) {

        val url = rawUrl.trim()

        if (
            !url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {

            Log.w(
                "GDrop",
                "PAIRING_FAILED: invalid pairing URL"
            )

            updateStatus(
                "Invalid pairing link"
            )

            return
        }

        val parsed = Uri.parse(url)

        val token =
            parsed.getQueryParameter("token")
                ?: ""

        if (token.isBlank()) {

            Log.w(
                "GDrop",
                "PAIRING_FAILED: pairing URL missing token"
            )

            updateStatus(
                "Pairing URL missing token"
            )

            return
        }

        val base =
            "${parsed.scheme}://${parsed.host}" +
                if (parsed.port > 0) {
                    ":${parsed.port}"
                } else {
                    ""
                }

        Log.i(
            "GDrop",
            "PAIRING_URL_PARSED: $base"
        )

        laptopBaseUrl = base
        currentToken = token

        lifecycleScope.launch {

            val result =
                withContext(Dispatchers.IO) {
                    callPairingEndpoint(
                        base,
                        token
                    )
                }

            if (result) {

                Log.i(
                    "GDrop",
                    "PAIRING_SUCCESS"
                )

                binding.pairedLabel.text =
                    "Paired with: $pairedLaptopName"

                binding.statusLabel.text =
                    "Connected ✓"

                binding.connectionCard.visibility =
                    View.VISIBLE

                updateStatus(
                    "Paired successfully"
                )

                loadDeviceInfo()
                refreshTransfers()

                // Start gesture-command polling.
                startCommandPolling()

            } else {

                Log.w(
                    "GDrop",
                    "PAIRING_FAILED: pairing request was unsuccessful"
                )

                updateStatus(
                    "Pairing failed"
                )
            }
        }
    }

    private fun callPairingEndpoint(
        baseUrl: String,
        token: String
    ): Boolean {

        Log.i(
            "GDrop",
            "PAIRING_REQUEST: $baseUrl/pair/connect"
        )

        val request = Request.Builder()
            .url(
                "$baseUrl/pair/connect?token=$token"
            )
            .get()
            .build()

        return try {

            client.newCall(request)
                .execute()
                .use { response ->

                    if (!response.isSuccessful) {

                        Log.w(
                            "GDrop",
                            "PAIRING_FAILED: HTTP ${response.code}"
                        )

                        return false
                    }

                    val body =
                        response.body?.string()
                            ?: ""

                    val start =
                        body.indexOf("Device name:")

                    val end =
                        body.indexOf("</p>", start)

                    if (
                        start >= 0 &&
                        end > start
                    ) {

                        val raw =
                            body.substring(
                                start + "Device name:".length,
                                end
                            ).trim()

                        pairedLaptopName =
                            raw.removePrefix(
                                "</strong>"
                            ).trim()
                    }

                    true
                }

        } catch (e: Exception) {

            Log.e(
                "GDrop",
                "PAIRING_FAILED: pairing request failed",
                e
            )

            false
        }
    }

    private fun loadDeviceInfo() {

        val uri =
            "$laptopBaseUrl/device"

        val request =
            Request.Builder()
                .url(uri)
                .get()
                .build()

        client.newCall(request)
            .enqueue(object : Callback {

                override fun onFailure(
                    call: Call,
                    e: IOException
                ) {

                    runOnUiThread {

                        binding.pairedLabel.text =
                            "Paired with: Not paired"

                        binding.statusLabel.text =
                            "Disconnected"

                        binding.connectionCard.visibility =
                            View.GONE

                        updateStatus(
                            "Laptop unreachable"
                        )
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response
                ) {

                    response.use {

                        val body =
                            response.body?.string()
                                ?: "{}"

                        val json =
                            JSONObject(body)

                        runOnUiThread {

                            val deviceName =
                                json.optString(
                                    "device_name",
                                    "GDrop Laptop"
                                )

                            val deviceId =
                                json.optString(
                                    "device_id",
                                    ""
                                )

                            val status =
                                json.optString(
                                    "status",
                                    "offline"
                                )

                            pairedLaptopName =
                                deviceName

                            binding.pairedLabel.text =
                                "Paired with: $deviceName"

                            binding.statusLabel.text =
                                if (
                                    status == "online"
                                ) {
                                    "Connected ✓"
                                } else {
                                    "Disconnected"
                                }

                            binding.connectionCard.visibility =
                                View.VISIBLE

                            if (
                                deviceId.isNotBlank()
                            ) {
                                binding.deviceId.text =
                                    "Device ID: $deviceId"
                            }

                            updateStatus(
                                "Connected"
                            )
                        }
                    }
                }
            })
    }

    // ---------------------------------------------------------
    // GESTURE COMMAND POLLING
    // ---------------------------------------------------------

    private fun startCommandPolling() {

        lifecycleScope.launch(Dispatchers.IO) {

            while (isActive) {

                try {

                    val request =
                        Request.Builder()
                            .url(
                                "$laptopBaseUrl/gesture-command/poll"
                            )
                            .get()
                            .build()

                    client.newCall(request)
                        .execute()
                        .use { response ->

                            if (
                                response.isSuccessful &&
                                response.body != null
                            ) {

                                val body =
                                    response.body!!
                                        .string()

                                if (body.isNotBlank()) {

                                    val json =
                                        JSONObject(body)

                                    val name =
                                        json.optString(
                                            "name",
                                            ""
                                        )

                                    if (
                                        name.isNotBlank()
                                    ) {

                                        runOnUiThread {
                                            handleGestureCommand(
                                                name
                                            )
                                        }
                                    }
                                }
                            }
                        }

                } catch (e: Exception) {

                    Log.w(
                        "GDrop",
                        "COMMAND_POLL_FAILED: ${e.message}"
                    )
                }

                delay(1000)
            }
        }
    }

    private fun handleGestureCommand(
        name: String
    ) {

        Log.i(
            "GDrop",
            "COMMAND_RECEIVED: $name"
        )

        when (name) {

            "SEND_REQUEST" -> {

                lifecycleScope.launch {

                    val files =
                        withContext(Dispatchers.IO) {
                            fetchFileList()
                        }

                    if (files != null) {

                        val pendingFiles =
                            files.filterNot {
                                it.name in receivedTransferNames
                            }

                        if (
                            pendingFiles.isNotEmpty()
                        ) {

                            receiveFile(
                                pendingFiles[0]
                            )
                        } else {

                            updateStatus(
                                "No pending files"
                            )
                        }
                    }
                }
            }

            "CANCEL_REQUEST" -> {

                cancelRequested = true

                updateStatus(
                    "Cancel requested"
                )
            }
        }
    }

    // ---------------------------------------------------------
    // FILE LIST / RECEIVE
    // ---------------------------------------------------------

    private fun downloadLatestFile() {
        refreshTransfers()
    }

    private fun refreshTransfers() {

        lifecycleScope.launch {

            val files =
                withContext(Dispatchers.IO) {
                    fetchFileList()
                }

            if (files == null) {

                updateStatus(
                    "Unable to load incoming transfers"
                )

                return@launch
            }

            val pendingFiles =
                files.filterNot {
                    it.name in receivedTransferNames
                }

            binding.transferList.removeAllViews()

            if (pendingFiles.isEmpty()) {

                addTransferMessage(
                    "No new files"
                )

            } else {

                pendingFiles.forEach {
                    addTransferRow(it)
                }
            }
        }
    }

    private fun addTransferMessage(
        message: String
    ) {

        val label =
            TextView(this).apply {
                text = message
                setPadding(
                    0,
                    8,
                    0,
                    8
                )
            }

        binding.transferList.addView(label)
    }

    private fun addTransferRow(
        file: GDropFile
    ) {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    0,
                    8,
                    0,
                    8
                )
            }

        val label =
            TextView(this).apply {

                text =
                    "${file.name}\n${formatFileSize(file.size)}"

                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
            }

        val receive =
            Button(this).apply {

                text = "Receive"

                setOnClickListener {

                    isEnabled = false

                    receiveFile(file)
                }
            }

        row.addView(label)
        row.addView(receive)

        binding.transferList.addView(row)
    }

    private fun formatFileSize(
        size: Long
    ): String {

        if (size < 1024) {
            return "$size B"
        }

        if (size < 1024 * 1024) {
            return "%.1f KB".format(
                size / 1024.0
            )
        }

        return "%.1f MB".format(
            size / (1024.0 * 1024.0)
        )
    }

    private fun receiveFile(
        file: GDropFile
    ) {

        lifecycleScope.launch {

            cancelRequested = false

            binding.progressBar.progress = 0
            binding.progressBar.visibility =
                View.VISIBLE

            binding.transferStatus.text =
                "Receiving ${file.name}..."

            showDownloadNotification(
                file.name
            )

            Log.i(
                "GDrop",
                "RECEIVE_START: ${file.name}"
            )

            val saved =
                withContext(Dispatchers.IO) {
                    downloadAndSave(file)
                }

            if (saved != null) {

                currentTransferName =
                    saved.second

                receivedTransferNames.add(
                    file.name
                )

                binding.transferStatus.text =
                    "Received ✓"

                binding.transferName.text =
                    "Incoming Transfers"

                binding.progressBar.visibility =
                    View.GONE

                Log.i(
                    "GDrop",
                    "RECEIVE_SUCCESS: ${saved.second}"
                )

                showDownloadCompleteNotification(
                    saved.second,
                    saved.first
                )

            } else {

                binding.transferStatus.text =
                    if (cancelRequested) {
                        "Transfer cancelled"
                    } else {
                        "Receive failed"
                    }

                binding.progressBar.visibility =
                    View.GONE

                Log.e(
                    "GDrop",
                    "RECEIVE_FAILED: ${file.name}"
                )

                cancelDownloadNotification()
            }

            refreshTransfers()
        }
    }

    private fun fetchFileList():
        List<GDropFile>? {

        val request =
            Request.Builder()
                .url("$laptopBaseUrl/files")
                .get()
                .build()

        return try {

            client.newCall(request)
                .execute()
                .use { response ->

                    if (!response.isSuccessful) {
                        return null
                    }

                    val body =
                        response.body?.string()
                            ?: return null

                    val json =
                        JSONObject(body)

                    val arr =
                        json.optJSONArray("files")
                            ?: return emptyList()

                    val files =
                        mutableListOf<GDropFile>()

                    for (
                        i in 0 until arr.length()
                    ) {

                        val item =
                            arr.getJSONObject(i)

                        files.add(
                            GDropFile(
                                item.optString(
                                    "name"
                                ),
                                item.optLong(
                                    "size",
                                    0L
                                )
                            )
                        )
                    }

                    files
                }

        } catch (e: Exception) {

            Log.e(
                "GDrop",
                "FETCH_FILES_FAILED",
                e
            )

            null
        }
    }

    private fun downloadAndSave(
        file: GDropFile
    ): Pair<Uri, String>? {

        val url =
            "$laptopBaseUrl/download/${Uri.encode(file.name)}"

        Log.i(
            "GDrop",
            "DOWNLOAD_STARTED: ${file.name}"
        )

        Log.i(
            "GDrop",
            "DOWNLOAD_URL: $url"
        )

        val request =
            Request.Builder()
                .url(url)
                .get()
                .build()

        val temp =
            File.createTempFile(
                "gdrop-",
                ".part",
                cacheDir
            )

        return try {

            client.newCall(request)
                .execute()
                .use { response ->

                    Log.i(
                        "GDrop",
                        "DOWNLOAD_RESPONSE: ${response.code}"
                    )

                    if (!response.isSuccessful) {

                        Log.e(
                            "GDrop",
                            "DOWNLOAD_FAILED: HTTP ${response.code}"
                        )

                        return null
                    }

                    val body =
                        response.body
                            ?: return null

                    val total =
                        if (
                            body.contentLength() > 0
                        ) {
                            body.contentLength()
                        } else {
                            file.size
                        }

                    body.byteStream().use { input ->

                        FileOutputStream(temp)
                            .use { output ->

                                val buffer =
                                    ByteArray(8192)

                                var copied = 0L
                                var count: Int

                                while (
                                    input.read(buffer)
                                        .also {
                                            count = it
                                        } != -1
                                ) {

                                    if (cancelRequested) {

                                        Log.i(
                                            "GDrop",
                                            "DOWNLOAD_CANCELLED"
                                        )

                                        cancelRequested =
                                            false

                                        return null
                                    }

                                    output.write(
                                        buffer,
                                        0,
                                        count
                                    )

                                    copied += count

                                    val progress =
                                        if (total > 0) {
                                            (
                                                copied * 100
                                            ).div(total)
                                                .toInt()
                                        } else {
                                            0
                                        }

                                    runOnUiThread {

                                        binding.progressBar.progress =
                                            progress

                                        updateDownloadNotificationProgress(
                                            file.name,
                                            progress,
                                            100
                                        )
                                    }
                                }

                                Log.i(
                                    "GDrop",
                                    "DOWNLOAD_BYTES: ${temp.length()}"
                                )
                            }
                    }

                    val saved =
                        saveReceivedFile(
                            temp,
                            file.name
                        )

                    if (saved != null) {

                        Log.i(
                            "GDrop",
                            "DOWNLOAD_COMPLETE: ${saved.second}"
                        )

                    } else {

                        Log.e(
                            "GDrop",
                            "DOWNLOAD_FAILED: file save returned no destination"
                        )
                    }

                    saved
                }

        } catch (e: Exception) {

            Log.e(
                "GDrop",
                "DOWNLOAD_FAILED: ${e.message}",
                e
            )

            null

        } finally {

            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    // ---------------------------------------------------------
    // FILE SAVING
    // ---------------------------------------------------------

    private fun saveReceivedFile(
        source: File,
        fileName: String
    ): Pair<Uri, String>? {

        val safeName =
            sanitizeFileName(fileName)

        Log.i(
            "GDrop",
            "FILE_SAVE_STARTED: $safeName (${source.length()} bytes)"
        )

        val destination =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                saveToDownloadsScoped(
                    safeName,
                    source
                )

            } else {

                saveToLegacyDownloads(
                    safeName,
                    source
                )
            }

        if (destination != null) {

            Log.i(
                "GDrop",
                "FILE_SAVE_SUCCESS: ${destination.second}"
            )

        } else {

            Log.e(
                "GDrop",
                "FILE_SAVE_FAILED: no destination"
            )
        }

        return destination
    }

    private fun sanitizeFileName(
        fileName: String
    ): String {

        val clean =
            buildString {

                for (char in fileName) {

                    when {

                        char == '\u0000' ->
                            append('_')

                        char == '\r' ||
                        char == '\n' ||
                        char == '\t' ->
                            append('_')

                        char == '\\' ||
                        char == '/' ->
                            append('_')

                        char == ':' ||
                        char == '*' ||
                        char == '?' ->
                            append('_')

                        char == '"' ||
                        char == '<' ||
                        char == '>' ||
                        char == '|' ->
                            append('_')

                        else ->
                            append(char)
                    }
                }
            }.trim()

        if (
            clean.isBlank() ||
            clean == "." ||
            clean == ".."
        ) {
            return "gdrop-file.bin"
        }

        return clean
    }

    private fun getMimeType(
        fileName: String
    ): String {

        return when (
            fileName.substringAfterLast(
                '.',
                ""
            ).lowercase()
        ) {

            "png" ->
                "image/png"

            "jpg",
            "jpeg" ->
                "image/jpeg"

            "webp" ->
                "image/webp"

            "gif" ->
                "image/gif"

            "pdf" ->
                "application/pdf"

            "txt" ->
                "text/plain"

            "md" ->
                "text/markdown"

            "zip" ->
                "application/zip"

            "mp4" ->
                "video/mp4"

            "mp3" ->
                "audio/mpeg"

            else ->
                "application/octet-stream"
        }
    }

    private fun buildUniqueFileName(
        baseName: String
    ): String {

        val file =
            File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ),
                "GDrop/$baseName"
            )

        if (
            !file.exists() &&
            !mediaStoreFileExists(baseName)
        ) {
            return baseName
        }

        val nameOnly =
            file.nameWithoutExtension

        val ext =
            file.extension

        var index = 1

        while (true) {

            val candidate =
                if (ext.isBlank()) {
                    "$nameOnly ($index)"
                } else {
                    "$nameOnly ($index).$ext"
                }

            val nextFile =
                File(
                    file.parentFile,
                    candidate
                )

            if (
                !nextFile.exists() &&
                !mediaStoreFileExists(candidate)
            ) {
                return candidate
            }

            index++
        }
    }

    private fun mediaStoreFileExists(
        fileName: String
    ): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {
            return false
        }

        val projection =
            arrayOf(
                MediaStore.MediaColumns._ID
            )

        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"

        val selectionArgs =
            arrayOf(
                fileName,
                Environment.DIRECTORY_DOWNLOADS +
                    "/GDrop/"
            )

        return contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use {
            it.moveToFirst()
        } == true
    }

    private fun saveToLegacyDownloads(
        fileName: String,
        source: File
    ): Pair<Uri, String>? {

        val downloadsDir =
            File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ),
                "GDrop"
            )

        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val uniqueName =
            buildUniqueFileName(fileName)

        val target =
            File(
                downloadsDir,
                uniqueName
            )

        return try {

            FileInputStream(source).use { input ->

                FileOutputStream(target).use { output ->

                    input.copyTo(output)
                }
            }

            val uri =
                Uri.fromFile(target)

            Pair(
                uri,
                uniqueName
            )

        } catch (e: Exception) {

            Log.e(
                "GDrop",
                "FILE_SAVE_LEGACY_FAILED",
                e
            )

            if (target.exists()) {
                target.delete()
            }

            null
        }
    }

    private fun saveToDownloadsScoped(
        fileName: String,
        source: File
    ): Pair<Uri, String>? {

        val unique =
            buildUniqueFileName(fileName)

        val mimeType =
            getMimeType(unique)

        val values =
            ContentValues().apply {

                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    unique
                )

                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    mimeType
                )

                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS +
                        "/GDrop"
                )

                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    1
                )
            }

        val resolver =
            contentResolver

        val uri =
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: run {

                Log.e(
                    "GDrop",
                    "FILE_SAVE_FAILED: MediaStore insert returned null"
                )

                return null
            }

        return try {

            val output =
                resolver.openOutputStream(uri)
                    ?: run {

                        resolver.delete(
                            uri,
                            null,
                            null
                        )

                        Log.e(
                            "GDrop",
                            "FILE_SAVE_FAILED: output stream unavailable"
                        )

                        return null
                    }

            output.use {

                FileInputStream(source).use { input ->

                    input.copyTo(it)
                }
            }

            resolver.update(
                uri,
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        0
                    )
                },
                null,
                null
            )

            lastReceivedUri =
                uri

            Log.i(
                "GDrop",
                "FILE_SAVE_SUCCESS: URI=$uri, displayName=$unique"
            )

            Pair(
                uri,
                unique
            )

        } catch (e: Exception) {

            Log.e(
                "GDrop",
                "FILE_SAVE_FAILED: exception during MediaStore save",
                e
            )

            try {
                resolver.delete(
                    uri,
                    null,
                    null
                )
            } catch (_: Exception) {
            }

            null
        }
    }

    // ---------------------------------------------------------
    // NOTIFICATIONS
    // ---------------------------------------------------------

    private fun createDownloadNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                    "File Transfers",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {

                    description =
                        "Notifications for GDrop file transfers"
                }

            notificationManager.createNotificationChannel(
                channel
            )
        }
    }

    private fun canShowNotifications(): Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        } else {
            true
        }
    }

    private fun showDownloadNotification(
        fileName: String
    ) {

        if (!canShowNotifications()) {
            Log.w(
                "GDrop",
                "NOTIFICATION_SKIPPED: permission denied"
            )
            return
        }

        val notification =
            NotificationCompat.Builder(
                this,
                DOWNLOAD_NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_save
                )
                .setContentTitle(
                    "Receiving"
                )
                .setContentText(
                    fileName
                )
                .setProgress(
                    100,
                    0,
                    true
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(
                    NotificationCompat.PRIORITY_DEFAULT
                )
                .build()

        notificationManager.notify(
            DOWNLOAD_NOTIFICATION_ID,
            notification
        )
    }

    private fun updateDownloadNotificationProgress(
        fileName: String,
        progress: Int,
        max: Int
    ) {

        if (!canShowNotifications()) {
            return
        }

        val notification =
            NotificationCompat.Builder(
                this,
                DOWNLOAD_NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_save
                )
                .setContentTitle(
                    "Receiving"
                )
                .setContentText(
                    fileName
                )
                .setProgress(
                    max,
                    progress.coerceIn(0, max),
                    false
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(
                    NotificationCompat.PRIORITY_DEFAULT
                )
                .build()

        notificationManager.notify(
            DOWNLOAD_NOTIFICATION_ID,
            notification
        )
    }

    private fun showDownloadCompleteNotification(
        fileName: String,
        uri: Uri
    ) {

        if (!canShowNotifications()) {
            Log.w(
                "GDrop",
                "NOTIFICATION_COMPLETE_SKIPPED: permission denied"
            )
            return
        }

        val mimeType =
            getMimeType(fileName)

        val openIntent =
            Intent(Intent.ACTION_VIEW).apply {

                setDataAndType(
                    uri,
                    mimeType
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                fileName.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                this,
                DOWNLOAD_NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_save
                )
                .setContentTitle(
                    "File received ✓"
                )
                .setContentText(
                    fileName
                )
                .setContentIntent(
                    pendingIntent
                )
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setPriority(
                    NotificationCompat.PRIORITY_DEFAULT
                )
                .build()

        // Replace the ongoing "Receiving" notification.
        notificationManager.cancel(
            DOWNLOAD_NOTIFICATION_ID
        )

        notificationManager.notify(
            DOWNLOAD_NOTIFICATION_ID,
            notification
        )

        Log.i(
            "GDrop",
            "NOTIFICATION_DOWNLOAD_COMPLETE: $fileName"
        )
    }

    private fun cancelDownloadNotification() {

        notificationManager.cancel(
            DOWNLOAD_NOTIFICATION_ID
        )
    }

    // ---------------------------------------------------------
    // UI
    // ---------------------------------------------------------

    private fun updateStatus(
        message: String
    ) {

        runOnUiThread {
            binding.transferStatus.text =
                message
        }
    }
}

data class GDropFile(
    val name: String,
    val size: Long
)