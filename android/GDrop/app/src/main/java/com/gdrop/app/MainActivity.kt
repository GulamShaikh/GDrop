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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
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

    private val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "gdrop_downloads"
    private val DOWNLOAD_NOTIFICATION_ID = 1001
    private var currentDownloadFileName = ""
    private lateinit var notificationManager: NotificationManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.all { it.value }
        if (granted) {
            Toast.makeText(this, "Storage access ready", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permission required for receiving files", Toast.LENGTH_LONG).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openQrScanner()
        } else {
            Log.w("GDrop", "QR_SCAN_FAILED: camera permission denied")
            Toast.makeText(this, "Camera permission is required. Use browser pairing instead.", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i("GDrop", "NOTIFICATION_PERMISSION_GRANTED")
        } else {
            Log.w("GDrop", "NOTIFICATION_PERMISSION_DENIED: notifications will not be shown")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize notification manager and create channel
        notificationManager = getSystemService(NotificationManager::class.java)
        createDownloadNotificationChannel()
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                Toast.makeText(this, "Pair with a laptop first", Toast.LENGTH_SHORT).show()
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
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startQrScan() {
        Log.i("GDrop", "PAIRING_STARTED")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openQrScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openQrScanner() {
        Log.i("GDrop", "QR_SCANNER_OPENED")
        binding.scannerPreview.visibility = View.VISIBLE
        scannerRunning = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.scannerPreview.surfaceProvider)
                }
                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                )
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    analyzeQrFrame(scanner, imageProxy)
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
                Log.e("GDrop", "QR_SCAN_FAILED: unable to open camera", e)
                Toast.makeText(this, "Unable to open camera. Use browser pairing instead.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeQrFrame(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !scannerRunning) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val rawValue = barcodes.firstOrNull()?.rawValue
                if (!rawValue.isNullOrBlank() && scannerRunning) {
                    scannerRunning = false
                    cameraProvider?.unbindAll()
                    binding.scannerPreview.visibility = View.GONE
                    Log.i("GDrop", "QR_SCANNED")
                    handlePairingUrl(rawValue)
                }
            }
            .addOnFailureListener { error ->
                Log.w("GDrop", "QR_SCAN_FAILED: ${error.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handlePairingUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w("GDrop", "PAIRING_FAILED: invalid pairing URL")
            updateStatus("Invalid pairing link")
            return
        }

        val parsed = Uri.parse(url)
        val token = parsed.getQueryParameter("token") ?: ""
        if (token.isBlank()) {
            Log.w("GDrop", "PAIRING_FAILED: pairing URL missing token")
            updateStatus("Pairing URL missing token")
            return
        }

        val base = "${parsed.scheme}://${parsed.host}${if (parsed.port > 0) ":${parsed.port}" else ""}"
        Log.i("GDrop", "PAIRING_URL_PARSED: $base")
        laptopBaseUrl = base
        currentToken = token

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { callPairingEndpoint(base, token) }
            if (result) {
                Log.i("GDrop", "PAIRING_SUCCESS")
                binding.pairedLabel.text = "Paired with: $pairedLaptopName"
                binding.statusLabel.text = "Connected ✓"
                binding.connectionCard.visibility = View.VISIBLE
                updateStatus("Paired successfully")
                loadDeviceInfo()
                refreshTransfers()
            } else {
                Log.w("GDrop", "PAIRING_FAILED: pairing request was unsuccessful")
                updateStatus("Pairing failed")
            }
        }
    }

    private fun callPairingEndpoint(baseUrl: String, token: String): Boolean {
        Log.i("GDrop", "PAIRING_REQUEST: $baseUrl/pair/connect")
        val request = Request.Builder()
            .url("$baseUrl/pair/connect?token=$token")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("GDrop", "PAIRING_FAILED: HTTP ${response.code}")
                    return false
                }
                val body = response.body?.string() ?: ""
                val start = body.indexOf("Device name:")
                val end = body.indexOf("</p>", start)
                if (start >= 0 && end > start) {
                    val raw = body.substring(start + "Device name:".length, end).trim()
                    pairedLaptopName = raw.removePrefix("</strong>").trim()
                }
                true
            }
        } catch (e: Exception) {
            Log.e("GDrop", "PAIRING_FAILED: pairing request failed", e)
            false
        }
    }

    private fun loadDeviceInfo() {
        val uri = "$laptopBaseUrl/device"
        val request = Request.Builder().url(uri).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.pairedLabel.text = "Paired with: Not paired"
                    binding.statusLabel.text = "Disconnected"
                    binding.connectionCard.visibility = View.GONE
                    updateStatus("Laptop unreachable")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                runOnUiThread {
                    val deviceName = json.optString("device_name", "GDrop Laptop")
                    val deviceId = json.optString("device_id", "")
                    val status = json.optString("status", "offline")

                    pairedLaptopName = deviceName
                    binding.pairedLabel.text = "Paired with: $deviceName"
                    binding.statusLabel.text = if (status == "online") "Connected ✓" else "Disconnected"
                    binding.connectionCard.visibility = View.VISIBLE
                    if (deviceId.isNotBlank()) {
                        binding.deviceId.text = "Device ID: $deviceId"
                    }
                    updateStatus("Connected")
                }
            }
        })
    }

    private fun downloadLatestFile() {
        refreshTransfers()
    }

    private fun refreshTransfers() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { fetchFileList() }
            if (files == null) {
                updateStatus("Unable to load incoming transfers")
                return@launch
            }
            val pendingFiles = files.filterNot { it.name in receivedTransferNames }
            binding.transferList.removeAllViews()
            if (pendingFiles.isEmpty()) {
                addTransferMessage("No new files")
            } else {
                pendingFiles.forEach { addTransferRow(it) }
            }
        }
    }

    private fun addTransferMessage(message: String) {
        val label = TextView(this).apply {
            text = message
            setPadding(0, 8, 0, 8)
        }
        binding.transferList.addView(label)
    }

    private fun addTransferRow(file: GDropFile) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val label = TextView(this).apply {
            text = "${file.name}\n${formatFileSize(file.size)}"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val receive = Button(this).apply {
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

    private fun formatFileSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return "%.1f KB".format(size / 1024.0)
        return "%.1f MB".format(size / (1024.0 * 1024.0))
    }

    private fun receiveFile(file: GDropFile) {
        lifecycleScope.launch {
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.VISIBLE
            binding.transferStatus.text = "Receiving ${file.name}..."
            Log.i("GDrop", "RECEIVE_START: ${file.name} (${file.size} bytes)")
            
            // Show download notification
            showDownloadNotification(file.name)
            
            val saved = withContext(Dispatchers.IO) { downloadAndSave(file) }
            if (saved != null) {
                currentTransferName = saved.second
                receivedTransferNames.add(file.name)
                binding.transferStatus.text = "Received ✓"
                binding.transferName.text = "Incoming Transfers"
                Log.i("GDrop", "RECEIVE_SUCCESS: ${saved.second} is now available on device")
                binding.progressBar.visibility = View.GONE
                
                // Show completion notification with intent to open file
                showDownloadCompleteNotification(saved.second, saved.first)
            } else {
                binding.transferStatus.text = "Receive failed"
                Log.e("GDrop", "RECEIVE_FAILED: ${file.name} could not be saved")
                binding.progressBar.visibility = View.GONE
                
                // Cancel notification on failure
                cancelDownloadNotification()
            }
            refreshTransfers()
        }
    }

    private fun fetchFileList(): List<GDropFile>? {
        val request = Request.Builder().url("$laptopBaseUrl/files").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val arr = json.optJSONArray("files") ?: return emptyList()
                val files = mutableListOf<GDropFile>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    files.add(GDropFile(item.optString("name"), item.optLong("size", 0L)))
                }
                files
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadAndSave(file: GDropFile): Pair<Uri, String>? {
        val url = "$laptopBaseUrl/download/${Uri.encode(file.name)}"
        Log.i("GDrop", "DOWNLOAD_STARTED: ${file.name}")
        Log.i("GDrop", "DOWNLOAD_URL: $url")
        val request = Request.Builder().url(url).get().build()
        val temp = File.createTempFile("gdrop-", ".part", cacheDir)
        return try {
            client.newCall(request).execute().use { response ->
                Log.i("GDrop", "DOWNLOAD_RESPONSE: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    Log.e("GDrop", "DOWNLOAD_FAILED: HTTP ${response.code} (expected 200)")
                    return null
                }
                val body = response.body ?: run {
                    Log.e("GDrop", "DOWNLOAD_FAILED: response body is null")
                    return null
                }
                val total = if (body.contentLength() > 0) body.contentLength() else file.size
                Log.i("GDrop", "DOWNLOAD_BYTES_EXPECTED: $total bytes")
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(8192)
                        var copied = 0L
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            copied += count
                            val progressPercent = if (total > 0) ((copied * 100) / total).toInt() else 0
                            runOnUiThread {
                                binding.progressBar.progress = progressPercent
                                updateDownloadNotificationProgress(file.name, progressPercent, 100)
                            }
                        }
                        Log.i("GDrop", "DOWNLOAD_BYTES: ${temp.length()} bytes written to temp")
                    }
                }
                val saved = saveReceivedFile(temp, file.name)
                if (saved != null) {
                    Log.i("GDrop", "DOWNLOAD_COMPLETE: ${saved.second} (${temp.length()} bytes)")
                } else {
                    Log.e("GDrop", "DOWNLOAD_FAILED: saveReceivedFile returned null")
                }
                saved
            }
        } catch (e: Exception) {
            Log.e("GDrop", "DOWNLOAD_FAILED: exception during download/save", e)
            Log.e("GDrop", "DOWNLOAD_ERROR_CAUSE: ${e.cause}")
            null
        } finally {
            if (temp.exists()) {
                temp.delete()
                Log.i("GDrop", "DOWNLOAD_TEMP_CLEANED: temp file deleted")
            }
        }
    }

    private fun saveReceivedFile(source: File, fileName: String): Pair<Uri, String>? {
        val safeName = sanitizeFileName(fileName)
        Log.i("GDrop", "FILE_SAVE_STARTED: $safeName (${source.length()} bytes from temp)")
        Log.i("GDrop", "FILE_SAVE_ORIGINAL_FILENAME: $fileName")
        
        if (!source.exists()) {
            Log.e("GDrop", "FILE_SAVE_FAILED: source temp file does not exist")
            return null
        }
        
        val sourceSize = source.length()
        if (sourceSize <= 0) {
            Log.e("GDrop", "FILE_SAVE_FAILED: source file is empty ($sourceSize bytes)")
            return null
        }
        
        Log.i("GDrop", "FILE_SAVE_ANDROID_VERSION: ${Build.VERSION.SDK_INT}")
        val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.i("GDrop", "FILE_SAVE_METHOD: using Android Q+ MediaStore API (SDK ${Build.VERSION.SDK_INT})")
            saveToDownloadsScoped(safeName, source)
        } else {
            Log.i("GDrop", "FILE_SAVE_METHOD: using legacy file API (SDK ${Build.VERSION.SDK_INT})")
            saveToLegacyDownloads(safeName, source)
        }
        
        if (destination != null) {
            val uri = destination.first
            val displayName = destination.second
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            Log.i("GDrop", "FILE_SAVE_SUCCESS: displayName=$displayName")
            Log.i("GDrop", "FILE_URI: $uri")
            Log.i("GDrop", "FILE_DISPLAY_NAME: $displayName")
            Log.i("GDrop", "FILE_MIME_TYPE: $mimeType")
            Log.i("GDrop", "FILE_SAVE_COMPLETE: source was $sourceSize bytes")
        } else {
            Log.e("GDrop", "FILE_SAVE_FAILED: save function returned null")
        }
        return destination
    }

    private fun sanitizeFileName(fileName: String): String {
        // Replace filesystem-dangerous characters with underscores
        // Safe characters: letters, numbers, spaces, dots, hyphens, underscores, parentheses
        val clean = fileName
            .replace("\\", "_")  // backslash - not regex
            .replace("/", "_")   // forward slash - not regex
            .replace(":", "_")   // colon (illegal in Windows filenames)
            .replace("*", "_")   // asterisk (illegal in Windows filenames)
            .replace("?", "_")   // question mark (illegal in Windows filenames)
            .replace("\"", "_")  // quote (illegal in Windows filenames)
            .replace("<", "_")   // less than (illegal in Windows filenames)
            .replace(">", "_")   // greater than (illegal in Windows filenames)
            .replace("|", "_")   // pipe (illegal in Windows filenames)
            .replace("\u0000", "_")  // null character (illegal everywhere)
            .replace("\r", "_")  // carriage return
            .replace("\n", "_")  // newline
            .trim()
        
        if (clean.isBlank()) return "gdrop-file.bin"
        
        // Prevent directory traversal attempts
        if (clean == ".." || clean == ".") return "gdrop-file.bin"
        
        return clean
    }

    private fun buildUniqueFileName(baseName: String): String {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GDrop/$baseName")
        if (!file.exists() && !mediaStoreFileExists(baseName)) return baseName

        val nameOnly = file.nameWithoutExtension
        val ext = file.extension
        var index = 1
        while (true) {
            val candidate = if (ext.isBlank()) "$nameOnly ($index)" else "$nameOnly ($index).$ext"
            val nextFile = File(file.parentFile, candidate)
            if (!nextFile.exists() && !mediaStoreFileExists(candidate)) return candidate
            index += 1
        }
    }

    private fun mediaStoreFileExists(fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/GDrop/")
        return contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { it.moveToFirst() } == true
    }

    private fun saveToLegacyDownloads(fileName: String, source: File): Pair<Uri, String>? {
        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GDrop")
        Log.i("GDrop", "FILE_SAVE_LEGACY_DIR: $downloadsDir")
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                Log.e("GDrop", "FILE_SAVE_FAILED: could not create downloads directory")
                return null
            }
            Log.i("GDrop", "FILE_SAVE_LEGACY_DIR_CREATED: ${downloadsDir.absolutePath}")
        }

        val uniqueName = buildUniqueFileName(fileName)
        val target = File(downloadsDir, uniqueName)
        Log.i("GDrop", "FILE_SAVE_LEGACY_TARGET: ${target.absolutePath}")
        
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output -> 
                    val bytesCopied = input.copyTo(output)
                    Log.i("GDrop", "FILE_SAVE_LEGACY_WRITTEN: $bytesCopied bytes to ${target.absolutePath}")
                }
            }
            
            // Verify file exists on disk
            if (target.exists()) {
                Log.i("GDrop", "FILE_SAVE_LEGACY_EXISTS: ${target.absolutePath} (${target.length()} bytes)")
                Log.i("GDrop", "FILE_SAVE_LEGACY_READABLE: ${target.canRead()}")
            } else {
                Log.e("GDrop", "FILE_SAVE_LEGACY_NOT_FOUND: file does not exist after write")
                return null
            }
            
            val uri = Uri.fromFile(target)
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            Log.i("GDrop", "FILE_SAVE_SUCCESS: legacy path=${target.absolutePath}, displayName=$uniqueName")
            Log.i("GDrop", "FILE_URI: $uri")
            Log.i("GDrop", "FILE_DISPLAY_NAME: $uniqueName")
            Log.i("GDrop", "FILE_MIME_TYPE: $mimeType")
            Log.i("GDrop", "FILE_STORAGE_PATH: ${target.absolutePath}")
            Pair(uri, uniqueName)
        } catch (e: Exception) {
            Log.e("GDrop", "FILE_SAVE_LEGACY_FAILED: ${e.message}", e)
            if (target.exists()) {
                target.delete()
                Log.i("GDrop", "FILE_SAVE_LEGACY_CLEANUP: deleted incomplete file")
            }
            null
        }
    }
private fun getMimeType(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "zip" -> "application/zip"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
    private fun saveToDownloadsScoped(fileName: String, source: File): Pair<Uri, String>? {
        val unique = buildUniqueFileName(fileName)
        Log.i("GDrop", "FILE_SAVE_MEDIASTORE_START: $unique")
        
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
            put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(unique))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GDrop")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e("GDrop", "FILE_SAVE_FAILED: MediaStore insert returned null")
            return null
        }
        Log.i("GDrop", "FILE_SAVE_URI_CREATED: $uri")
        
        return try {
            val output = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                Log.e("GDrop", "FILE_SAVE_FAILED: output stream unavailable")
                return null
            }
            Log.i("GDrop", "FILE_SAVE_OPEN_STREAM: output stream opened successfully")
            output.use {
                FileInputStream(source).use { input -> 
                    val bytesCopied = input.copyTo(it)
                    Log.i("GDrop", "FILE_SAVE_WRITE_COMPLETE: $bytesCopied bytes written")
                }
            }
            Log.i("GDrop", "FILE_SAVE_STREAM_CLOSED: output stream closed")
            
            // Clear pending flag
            val updateResult = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            Log.i("GDrop", "FILE_SAVE_PENDING_CLEAR: MediaStore update result = $updateResult")
            
            // Verify file is registered in MediaStore
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                    val data = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
                    val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "application/octet-stream"
                    Log.i("GDrop", "FILE_SAVE_VERIFIED_MEDIASTORE: displayName=$displayName, size=$size bytes")
                    Log.i("GDrop", "FILE_SAVE_DATA_PATH: $data")
                    Log.i("GDrop", "FILE_MIME_TYPE: $mimeType")
                     
                    // Verify file exists on disk
                    val diskFile = File(data)
                    if (diskFile.exists()) {
                        Log.i("GDrop", "FILE_SAVE_DISK_EXISTS: ${diskFile.absolutePath} (${diskFile.length()} bytes)")
                        Log.i("GDrop", "FILE_STORAGE_PATH: ${diskFile.absolutePath}")
                    } else {
                        Log.w("GDrop", "FILE_SAVE_DISK_NOT_FOUND: $data does not exist")
                    }
                } else {
                    Log.w("GDrop", "FILE_SAVE_MEDIASTORE_QUERY_EMPTY: file not found in MediaStore after insert")
                }
            }
             
            lastReceivedUri = uri
            Log.i("GDrop", "FILE_SAVE_SUCCESS: URI=$uri, displayName=$unique")
            Log.i("GDrop", "FILE_URI: $uri")
            Log.i("GDrop", "FILE_DISPLAY_NAME: $unique")
            Log.i("GDrop", "FILE_MIME_TYPE: ${resolver.getType(uri) ?: "application/octet-stream"}")
            Pair(uri, unique)
        } catch (e: Exception) {
            Log.e("GDrop", "FILE_SAVE_FAILED: exception during MediaStore save", e)
            Log.e("GDrop", "FILE_SAVE_ERROR_MESSAGE: ${e.message}")
            Log.e("GDrop", "FILE_SAVE_ERROR_CAUSE: ${e.cause}")
            try {
                resolver.delete(uri, null, null)
                Log.i("GDrop", "FILE_SAVE_ROLLBACK: MediaStore entry cleaned up after error")
            } catch (cleanupError: Exception) {
                Log.e("GDrop", "FILE_SAVE_ROLLBACK_FAILED: could not clean up MediaStore entry", cleanupError)
            }
            null
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.transferStatus.text = message
        }
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for GDrop file downloads"
            }
            notificationManager.createNotificationChannel(channel)
            Log.i("GDrop", "NOTIFICATION_CHANNEL_CREATED: $DOWNLOAD_NOTIFICATION_CHANNEL_ID")
        }
    }

    private fun showDownloadNotification(fileName: String) {
        currentDownloadFileName = fileName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("GDrop", "NOTIFICATION_SKIPPED: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        val notification = NotificationCompat.Builder(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Receiving")
            .setContentText("$fileName...")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notification)
        Log.i("GDrop", "NOTIFICATION_DOWNLOAD_STARTED: $fileName")
    }

    private fun updateDownloadNotificationProgress(fileName: String, progress: Int, max: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val notification = NotificationCompat.Builder(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Receiving")
            .setContentText("$fileName...")
            .setProgress(max, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notification)
    }

    private fun showDownloadCompleteNotification(fileName: String, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("GDrop", "NOTIFICATION_COMPLETE_SKIPPED: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("File received")
            .setContentText(fileName)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, notification)
        Log.i("GDrop", "NOTIFICATION_DOWNLOAD_COMPLETE: $fileName, uri=$uri")
    }

    private fun cancelDownloadNotification() {
        notificationManager.cancel(DOWNLOAD_NOTIFICATION_ID)
        Log.i("GDrop", "NOTIFICATION_CANCELLED")
    }
}

data class GDropFile(
    val name: String,
    val size: Long
)
