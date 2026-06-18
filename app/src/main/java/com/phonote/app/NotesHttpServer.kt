package com.phonote.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface

class NotesHttpServer(
    private val context: Context,
    port: Int = 8080,
    private val onServerEvent: (String) -> Unit = {}
) : NanoHTTPD(port) {

    private val db: AppDatabase by lazy {
        (context.applicationContext as PhonoteApp).database
    }

    private val assetHtml: String by lazy {
        context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun getCustomHtmlFile(): File = File(context.filesDir, "custom_index.html")

    private fun loadHtml(): String {
        val custom = getCustomHtmlFile()
        return if (custom.exists() && custom.length() > 0) custom.readText(Charsets.UTF_8) else assetHtml
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        return when {
            uri == "/" || uri == "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", loadHtml())
            uri == "/admin" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildAdminPage())
            uri == "/api/html" && method == Method.GET -> jsonResp(mapOf("html" to loadHtml(), "isCustom" to getCustomHtmlFile().exists()))
            uri == "/api/html" && method == Method.PUT -> {
                val body = readBody(session)
                val map = com.google.gson.Gson().fromJson(body, Map::class.java) ?: return badRequest("Invalid JSON")
                val html = map["html"] as? String ?: return badRequest("Missing html")
                getCustomHtmlFile().writeText(html, Charsets.UTF_8)
                onServerEvent("网页模板已更新")
                jsonResp(mapOf("ok" to true))
            }
            uri == "/api/html" && method == Method.DELETE -> {
                val f = getCustomHtmlFile(); if (f.exists()) f.delete()
                onServerEvent("网页模板已恢复默认")
                jsonResp(mapOf("ok" to true))
            }
            uri == "/api/notes" && method == Method.GET -> {
                val folderId = params["folderId"]?.toLongOrNull() ?: 0
                val folders = kotlinx.coroutines.runBlocking { db.noteDao().getFoldersByParent(folderId) }
                val notes = kotlinx.coroutines.runBlocking { db.noteDao().getNotesByParent(folderId) }
                jsonResp(mapOf("folders" to folders.map { noteToMap(it) }, "notes" to notes.map { noteToMap(it) }))
            }
            uri == "/api/notes/search" && method == Method.GET -> {
                val q = params["q"] ?: ""
                val notes = kotlinx.coroutines.runBlocking { db.noteDao().search(q) }
                jsonResp(mapOf("notes" to notes.filter { !it.isFolder }.map { noteToMap(it) }))
            }
            uri.startsWith("/api/notes/") && !uri.contains("/folder") && method == Method.GET -> {
                val id = uri.removePrefix("/api/notes/").toLongOrNull() ?: 0
                val note = kotlinx.coroutines.runBlocking { db.noteDao().getById(id) } ?: return notFound()
                jsonResp(noteToMap(note))
            }
            uri.startsWith("/api/notes/") && method == Method.PUT -> handleUpdate(session)
            uri == "/api/notes" && method == Method.POST -> handleCreate(session)
            uri.startsWith("/api/notes/") && method == Method.DELETE -> handleDelete(uri.removePrefix("/api/notes/"))
            // Static assets: serve any file from assets/ directory
            uri.startsWith("/") && method == Method.GET -> {
                val assetPath = uri.trimStart('/')
                if (assetPath.isEmpty()) notFound()
                else try {
                    val bytes = context.assets.open(assetPath).use { it.readBytes() }
                    newFixedLengthResponse(Response.Status.OK, getMimeType(assetPath), ByteArrayInputStream(bytes), bytes.size.toLong())
                } catch (_: Exception) { notFound() }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun buildAdminPage(): String {
        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Phonote - 网页模板编辑器</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#f5f5f5;color:#333;padding:20px}
.container{max-width:1000px;margin:0 auto}
h1{margin-bottom:8px;color:#1976d2}
.info{color:#666;margin-bottom:16px;font-size:14px}
.toolbar{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
.btn{padding:8px 16px;border:none;border-radius:6px;cursor:pointer;font-size:14px;font-weight:500}
.btn-primary{background:#1976d2;color:#fff}
.btn-primary:hover{background:#1565c0}
.btn-secondary{background:#fff;color:#333;border:1px solid #ddd}
.btn-secondary:hover{background:#f5f5f5}
.btn-danger{background:#ef5350;color:#fff}
.btn-danger:hover{background:#c62828}
textarea{width:100%;height:calc(100vh - 200px);padding:16px;border:1px solid #ddd;border-radius:8px;font-family:'JetBrains Mono',monospace;font-size:13px;line-height:1.6;resize:none;outline:none}
textarea:focus{border-color:#1976d2}
.toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);padding:12px 24px;background:#333;color:#fff;border-radius:8px;font-size:14px;z-index:1000;opacity:0;transition:opacity .3s}
.toast.show{opacity:1}
.status{padding:6px 12px;border-radius:4px;font-size:12px;font-weight:500}
.status-custom{background:#fff3cd;color:#856404}
.status-default{background:#d4edda;color:#155724}
</style>
</head>
<body>
<div class="container">
  <h1>网页模板编辑器</h1>
  <p class="info">编辑 index.html 自定义网页端外观和功能。保存后刷新主页即可生效。</p>
  <div class="toolbar">
    <span class="status" id="statusBadge"></span>
    <button class="btn btn-primary" onclick="save()">💾 保存</button>
    <button class="btn btn-danger" onclick="resetToDefault()">🔄 恢复默认</button>
    <a href="/" class="btn btn-secondary">← 返回主页</a>
  </div>
  <textarea id="editor" spellcheck="false"></textarea>
</div>
<div class="toast" id="toast"></div>
<script>
var currentIsCustom=false;
fetch('/api/html').then(function(r){return r.json()}).then(function(d){
  document.getElementById('editor').value=d.html;
  currentIsCustom=d.isCustom;
  updateStatus();
});
function updateStatus(){
  var el=document.getElementById('statusBadge');
  if(currentIsCustom){el.textContent='已自定义';el.className='status status-custom'}
  else{el.textContent='默认模板';el.className='status status-default'}
}
function save(){
  var html=document.getElementById('editor').value;
  fetch('/api/html',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({html:html})}).then(function(){
    currentIsCustom=true;updateStatus();showToast('已保存');});
}
function resetToDefault(){
  if(!confirm('确定恢复默认模板？'))return;
  fetch('/api/html',{method:'DELETE'}).then(function(){
    fetch('/api/html').then(function(r){return r.json()}).then(function(d){
      document.getElementById('editor').value=d.html;currentIsCustom=false;updateStatus();showToast('已恢复默认');});
  });
}
function showToast(msg){var t=document.getElementById('toast');t.textContent=msg;t.classList.add('show');setTimeout(function(){t.classList.remove('show')},2000)}
</script>
</body>
</html>"""
    }

    private fun handleCreate(session: IHTTPSession): Response {
        val body = readBody(session)
        val map = com.google.gson.Gson().fromJson(body, Map::class.java) ?: return badRequest("Invalid JSON")
        val title = map["title"] as? String ?: "新笔记"
        val content = map["content"] as? String ?: ""
        val folderId = (map["folderId"] as? Number)?.toLong() ?: 0
        val isFolder = map["isFolder"] as? Boolean ?: false
        val entity = NoteEntity(title = title, content = content, folderPath = "", isFolder = isFolder, parentId = folderId)
        val id = kotlinx.coroutines.runBlocking { db.noteDao().insert(entity) }
        onServerEvent("创建: $title")
        return jsonResp(mapOf("id" to id, "title" to title))
    }

    private fun handleUpdate(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/api/notes/").toLongOrNull() ?: return badRequest("Invalid id")
        val body = readBody(session)
        val map = com.google.gson.Gson().fromJson(body, Map::class.java) ?: return badRequest("Invalid JSON")
        val note = kotlinx.coroutines.runBlocking { db.noteDao().getById(id) } ?: return notFound()
        val updated = note.copy(
            title = (map["title"] as? String) ?: note.title,
            content = (map["content"] as? String) ?: note.content,
            updatedAt = System.currentTimeMillis()
        )
        kotlinx.coroutines.runBlocking { db.noteDao().update(updated) }
        onServerEvent("更新: ${updated.title}")
        return jsonResp(mapOf("ok" to true))
    }

    private fun handleDelete(uriId: String): Response {
        val id = uriId.toLongOrNull() ?: return badRequest("Invalid id")
        val note = kotlinx.coroutines.runBlocking { db.noteDao().getById(id) }
        kotlinx.coroutines.runBlocking { db.noteDao().deleteByIdCascade(id) }
        onServerEvent("删除: ${note?.title ?: "unknown"}")
        return jsonResp(mapOf("ok" to true))
    }

    private fun readBody(session: IHTTPSession): String {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val postData = bodyMap["postData"]
        if (!postData.isNullOrEmpty()) return postData
        val tmpFile = bodyMap["content"]
        if (!tmpFile.isNullOrEmpty()) {
            return try { BufferedReader(InputStreamReader(java.io.FileInputStream(tmpFile), Charsets.UTF_8)).use { it.readText() } } catch (_: Exception) { "" }
        }
        return ""
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", """{"error":"not found"}""")
    private fun badRequest(msg: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", """{"error":"$msg"}""")
    private fun jsonResp(data: Any) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", com.google.gson.Gson().toJson(data))

    private fun getMimeType(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".md") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun noteToMap(n: NoteEntity) = mapOf(
        "id" to n.id, "title" to n.title, "content" to n.content, "folderPath" to n.folderPath,
        "isFolder" to n.isFolder, "createdAt" to n.createdAt, "updatedAt" to n.updatedAt, "parentId" to n.parentId
    )

    fun getLocalIpAddress(): String {
        try {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 1500
            socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
            val ip = socket.localAddress.hostAddress
            socket.close()
            if (!ip.isNullOrEmpty() && ip != "0.0.0.0") return ip
        } catch (_: Exception) {}
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: null
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val a = addr.hostAddress
                            if (!a.isNullOrEmpty() && a != "0.0.0.0") return a
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val linkProps = cm.getLinkProperties(activeNetwork)
                if (linkProps != null) {
                    for (addr in linkProps.linkAddresses) {
                        val address = addr.address
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            val a = address.hostAddress
                            if (!a.isNullOrEmpty() && a != "0.0.0.0") return a
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
