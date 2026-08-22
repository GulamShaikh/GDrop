package com.gdrop.app

import android.Manifest
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
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

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val raw = data.getStringExtra("SCAN_RESULT")
        if (raw.isNullOrBlank()) {
            updateStatus("QR scan returned no data")
            return@registerForActivityResult
        }
        handlePairingUrl(raw)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pairButton.setOnClickListener {
            startQrScan()
        }

        binding.refreshButton.setOnClickListener {
            loadDeviceInfo()
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
        val intent = Intent("com.google.zxing.client.android.SCAN")
        intent.putExtra("SCAN_MODE", "QR_CODE_MODE")
        try {
            qrLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No QR scanner app found. Use a browser pairing link instead.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePairingUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            updateStatus("Invalid pairing link")
            return
        }

        val parsed = Uri.parse(url)
        val token = parsed.getQueryParameter("token") ?: ""
        if (token.isBlank()) {
            updateStatus("Pairing URL missing token")
            return
        }

        val base = "${parsed.scheme}://${parsed.host}${if (parsed.port > 0) ":${parsed.port}" else ""}"
        laptopBaseUrl = base
        currentToken = token

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { callPairingEndpoint(base, token) }
            if (result) {
                binding.pairedLabel.text = "Paired with: $pairedLaptopName"
                binding.statusLabel.text = "Connected ✓"
                binding.connectionCard.visibility = View.VISIBLE
                updateStatus("Paired successfully")
                loadDeviceInfo()
            } else {
                updateStatus("Pairing failed")
            }
        }
    }

    private fun callPairingEndpoint(baseUrl: String, token: String): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/pair/connect?token=$token")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
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
            Log.e("GDrop", "Pairing request failed", e)
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
        if (laptopBaseUrl.isBlank()) {
            updateStatus("No laptop URL available")
            return
        }

        lifecycleScope.launch {
            val files = fetchFileList() ?: emptyList()
            if (files.isEmpty()) {
                updateStatus("No files available")
                return@launch
            }

            val file = files.first()
            val target = "$laptopBaseUrl/download/${URLEncoder.encode(file.name, "UTF-8")}" 
            val response = withContext(Dispatchers.IO) { performDownload(target) }
            if (response != null) {
                val saved = saveReceivedFile(response, file.name)
                if (saved != null) {
                    currentTransferName = file.name
                    updateStatus("Received: ${file.name}")
                    binding.progressBar.progress = 100
                    binding.transferName.text = currentTransferName
                    binding.transferStatus.text = "Completed ✓"
                } else {
                    updateStatus("Save failed")
                }
            }
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

    private fun performDownload(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.e("GDrop", "Download failed", e)
            null
        }
    }

    private fun saveReceivedFile(data: ByteArray, fileName: String): Uri? {
        val safeName = sanitizeFileName(fileName)
        val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsScoped(safeName, data)
        } else {
            saveToLegacyDownloads(safeName, data)
        }
        return destination
    }

    private fun sanitizeFileName(fileName: String): String {
        val clean = fileName.replace("\\".toRegex(), "_")
            .replace("/".toRegex(), "_")
            .replace("..", "_")
            .trim()
        if (clean.isBlank()) return "gdrop-file.bin"
        return clean
    }

    private fun buildUniqueFileName(baseName: String): String {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GDrop/$baseName")
        if (!file.exists()) return baseName

        val nameOnly = file.nameWithoutExtension
        val ext = file.extension
        var index = 1
        while (true) {
            val candidate = if (ext.isBlank()) "$nameOnly ($index)" else "$nameOnly ($index).$ext"
            val nextFile = File(file.parentFile, candidate)
            if (!nextFile.exists()) return candidate
            index += 1
        }
    }

    private fun saveToLegacyDownloads(fileName: String, data: ByteArray): Uri? {
        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GDrop")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val uniqueName = buildUniqueFileName(fileName)
        val target = File(downloadsDir, uniqueName)
        FileOutputStream(target).use { it.write(data) }
        return Uri.fromFile(target)
    }

    private fun saveToDownloadsScoped(fileName: String, data: ByteArray): Uri? {
        val unique = buildUniqueFileName(fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GDrop")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            output.write(data)
        }
        lastReceivedUri = uri
        return uri
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.transferStatus.text = message
        }
    }
}

data class GDropFile(
    val name: String,
    val size: Long
)
