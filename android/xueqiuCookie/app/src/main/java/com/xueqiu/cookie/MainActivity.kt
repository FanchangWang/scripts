package com.xueqiu.cookie

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERM_WRITE = 100
    }

    private lateinit var etToken: EditText
    private lateinit var etU: EditText
    private lateinit var etApiUrl: EditText
    private lateinit var labelRecentFiles: TextView
    private lateinit var layoutFileList: LinearLayout
    private var loginWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etToken = findViewById(R.id.etToken)
        etU = findViewById(R.id.etU)
        etApiUrl = findViewById(R.id.etApiUrl)

        findViewById<Button>(R.id.btnLogin).setOnClickListener { showLoginDialog() }
        findViewById<Button>(R.id.btnFetch).setOnClickListener { fetchCookie() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyCookie() }
        findViewById<Button>(R.id.btnCopyToken).setOnClickListener {
            copyToClipboard(
                "xq_a_token",
                etToken.text.toString()
            )
        }
        findViewById<Button>(R.id.btnCopyU).setOnClickListener {
            copyToClipboard(
                "u",
                etU.text.toString()
            )
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { apiTest() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { apiSave() }
        labelRecentFiles = findViewById(R.id.labelRecentFiles)
        layoutFileList = findViewById(R.id.layoutFileList)
        findViewById<Button>(R.id.btnFetchJson).setOnClickListener { fetchAndSaveJson() }
        refreshFileList()
    }

    override fun onResume() {
        super.onResume()
        refreshFileList()
    }

    private fun showLoginDialog() {
        val webView = WebView(this).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loginWebView = view
                }
            }
            loadUrl("https://xueqiu.com/")
        }
        loginWebView = webView
        AlertDialog.Builder(this)
            .setTitle("雪球登录")
            .setView(webView)
            .setCancelable(true)
            .setPositiveButton("关闭") { _, _ -> }
            .show()
    }

    private fun fetchCookie() {
        CookieManager.getInstance().run {
            setAcceptCookie(true)
            flush()
        }
        val cookies = CookieManager.getInstance().getCookie("https://xueqiu.com") ?: ""
        if (cookies.isEmpty()) {
            Toast.makeText(this, "未找到 Cookie，请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        var xqAToken = ""
        var uValue = ""
        for (pair in cookies.split(";".toRegex())) {
            val parts = pair.trim().split("=", limit = 2)
            if (parts.size != 2) continue
            when (parts[0]) {
                "xq_a_token" -> xqAToken = parts[1]
                "u" -> uValue = parts[1]
            }
        }
        if (xqAToken.isEmpty() || uValue.isEmpty()) {
            Toast.makeText(this, "未找到完整的 Cookie 信息", Toast.LENGTH_SHORT).show()
            return
        }
        etToken.setText(xqAToken)
        etU.setText(uValue)
        Toast.makeText(this, "Cookie 已获取", Toast.LENGTH_SHORT).show()
    }

    private fun copyCookie() {
        val token = etToken.text.toString()
        val u = etU.text.toString()
        if (token.isEmpty() || u.isEmpty()) {
            Toast.makeText(this, "请先获取 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        val cookieStr = "XUEQIU_COOKIE=\"xq_a_token=$token; u=$u\""
        copyToClipboard("XUEQIU_COOKIE", cookieStr)
        Toast.makeText(this, "Cookie 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isEmpty()) {
            Toast.makeText(this, "$label 为空", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }

    private fun apiTest() {
        val apiUrl = etApiUrl.text.toString().trimEnd('/')
        val token = etToken.text.toString()
        val u = etU.text.toString()
        if (token.isEmpty() || u.isEmpty()) {
            Toast.makeText(this, "请先获取 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val json = JSONObject().apply {
                    put("xq_a_token", token)
                    put("u", u)
                }
                val conn = URL("$apiUrl/api/cookie/test").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                val respCode = conn.responseCode
                val body = if (respCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream.bufferedReader().readText()
                }
                conn.disconnect()
                val result = JSONObject(body)
                runOnUiThread {
                    if (result.optBoolean("valid")) {
                        var msg = "Cookie 有效！\n日期: ${result.optString("date", "未知")}"
                        if (result.has("save_status") && !result.isNull("save_status")) {
                            msg += "\n保存状态: ${result.optString("save_status")}"
                        }
                        showAlert("测试成功", msg)
                    } else {
                        showAlert("测试失败", result.optString("error", "Cookie 无效"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showAlert("请求错误", e.message ?: "未知错误") }
            }
        }.start()
    }

    private fun apiSave() {
        val apiUrl = etApiUrl.text.toString().trimEnd('/')
        val token = etToken.text.toString()
        val u = etU.text.toString()
        if (token.isEmpty() || u.isEmpty()) {
            Toast.makeText(this, "请先获取 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val json = JSONObject().apply {
                    put("xq_a_token", token)
                    put("u", u)
                }
                val conn = URL("$apiUrl/api/cookie").openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                val respCode = conn.responseCode
                val body = if (respCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream.bufferedReader().readText()
                }
                conn.disconnect()
                val result = JSONObject(body)
                runOnUiThread {
                    if (result.optString("status") == "ok") {
                        showAlert("保存成功", "Cookie 已保存到服务器！")
                    } else {
                        showAlert("保存失败", "返回: $result")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showAlert("请求错误", e.message ?: "未知错误") }
            }
        }.start()
    }

    @SuppressLint("NewApi")
    private fun saveJsonToDownload(body: String, dateStr: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fileName = "$dateStr.json"
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/xueqiuCookie"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection =
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val selArgs = arrayOf("$relativePath/", fileName)
            var uri: Uri? = null
            contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                selection,
                selArgs,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    uri = ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "$relativePath/")
                }
                uri = contentResolver.insert(collection, values)
                    ?: throw Exception("MediaStore insert 失败")
            }
            contentResolver.openOutputStream(uri)?.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            } ?: throw Exception("打开输出流失败")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "xueqiuCookie"
            )
            dir.mkdirs()
            File(dir, "$dateStr.json").writeText(body, Charsets.UTF_8)
        }
    }

    private fun fetchAndSaveJson() {
        val token = etToken.text.toString()
        val u = etU.text.toString()
        if (token.isEmpty() || u.isEmpty()) {
            Toast.makeText(this, "请先获取 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERM_WRITE)
            return
        }
        Thread {
            try {
                val url =
                    URL("https://stock.xueqiu.com/v5/stock/chart/minute.json?symbol=SH603533&period=1d")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
                )
                conn.setRequestProperty("Cookie", "xq_a_token=$token; u=$u")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val respCode = conn.responseCode
                val body = if (respCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }
                conn.disconnect()
                if (respCode != 200) {
                    runOnUiThread { showAlert("请求失败", "HTTP $respCode\n${body.take(200)}") }
                    return@Thread
                }
                val json = JSONObject(body)
                val items = json.optJSONObject("data")?.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    runOnUiThread { showAlert("解析失败", "响应中未找到 data.items") }
                    return@Thread
                }
                val ts = items.getJSONObject(0).optLong("timestamp", 0)
                if (ts == 0L) {
                    runOnUiThread { showAlert("解析失败", "timestamp 字段缺失") }
                    return@Thread
                }
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
                saveJsonToDownload(body, dateStr)
                runOnUiThread {
                    refreshFileList()
                    showAlert("保存成功", "已保存: $dateStr.json\n路径: Download/xueqiuCookie/")
                }
            } catch (e: Exception) {
                runOnUiThread { showAlert("错误", e.message ?: "未知错误") }
            }
        }.start()
    }

    private fun refreshFileList() {
        val fileNames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryDownloadFiles()
        } else {
            queryDownloadFilesLegacy()
        }
        if (fileNames.isEmpty()) {
            labelRecentFiles.visibility = View.GONE
            layoutFileList.removeAllViews()
            return
        }
        labelRecentFiles.visibility = View.VISIBLE
        layoutFileList.removeAllViews()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val weekDays = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val cal = Calendar.getInstance()
        for (name in fileNames) {
            val date = sdf.parse(name.removeSuffix(".json"))
            val weekday = if (date != null) {
                cal.time = date
                weekDays[cal.get(Calendar.DAY_OF_WEEK) - 1]
            } else ""
            val tv = TextView(this).apply {
                text = "$weekday $name"
                textSize = 13f
                setPadding(0, 4, 0, 4)
            }
            layoutFileList.addView(tv)
        }
    }

    @SuppressLint("NewApi")
    private fun queryDownloadFiles(): List<String> {
        val names = mutableListOf<String>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/xueqiuCookie/")
        contentResolver.query(
            collection,
            projection,
            selection,
            selArgs,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.let { names.add(it) }
            }
        }
        return names.filter { it.matches(Regex("""\d{4}-\d{2}-\d{2}\.json""")) }.take(5)
    }

    @Suppress("DEPRECATION")
    private fun queryDownloadFilesLegacy(): List<String> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "xueqiuCookie"
        )
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.matches(Regex("""\d{4}-\d{2}-\d{2}\.json""")) }
            ?.map { it.name }
            ?.sortedDescending()
            ?.take(5)
            ?: emptyList()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_WRITE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchAndSaveJson()
            } else {
                Toast.makeText(this, "需要存储权限才能保存文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }
}
