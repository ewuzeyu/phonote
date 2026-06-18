package com.phonote.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedReader
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

    private val webHtml: String by lazy {
        context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms

        return when {
            uri == "/" || uri == "/index.html" -> {
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", webHtml)
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
                val note = kotlinx.coroutines.runBlocking { db.noteDao().getById(id) }
                    ?: return notFound()
                jsonResp(noteToMap(note))
            }
            uri.startsWith("/api/notes/") && method == Method.PUT -> {
                handleUpdate(session)
            }
            uri == "/api/notes" && method == Method.POST -> {
                handleCreate(session)
            }
            uri.startsWith("/api/notes/") && method == Method.DELETE -> {
                handleDelete(uri.removePrefix("/api/notes/"))
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun handleCreate(session: IHTTPSession): Response {
        val body = readBody(session)
        val map = com.google.gson.Gson().fromJson(body, Map::class.java) ?: return badRequest("Invalid JSON")
        val title = map["title"] as? String ?: "新笔记"
        val content = map["content"] as? String ?: ""
        val folderId = (map["folderId"] as? Number)?.toLong() ?: 0
        val isFolder = map["isFolder"] as? Boolean ?: false
        val entity = NoteEntity(
            title = title,
            content = content,
            folderPath = "",
            isFolder = isFolder,
            parentId = folderId
        )
        val id = kotlinx.coroutines.runBlocking { db.noteDao().insert(entity) }
        onServerEvent("创建: $title")
        return jsonResp(mapOf("id" to id, "title" to title))
    }

    private fun handleUpdate(session: IHTTPSession): Response {
        val id = session.uri.removePrefix("/api/notes/").toLongOrNull()
            ?: return badRequest("Invalid id")
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
        // NanoHTTPD puts POST body in "postData" key
        val postData = bodyMap["postData"]
        if (!postData.isNullOrEmpty()) return postData
        // Fallback: read from temp file if parseBody stored it there
        val tmpFile = bodyMap["content"]
        if (!tmpFile.isNullOrEmpty()) {
            return try {
                BufferedReader(InputStreamReader(java.io.FileInputStream(tmpFile), Charsets.UTF_8)).use { it.readText() }
            } catch (_: Exception) { "" }
        }
        return ""
    }

    private fun notFound(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json; charset=utf-8", """{"error":"not found"}""")
    }

    private fun badRequest(msg: String): Response {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", """{"error":"$msg"}""")
    }

    private fun jsonResp(data: Any): Response {
        val json = com.google.gson.Gson().toJson(data)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun noteToMap(n: NoteEntity): Map<String, Any> {
        return mapOf(
            "id" to n.id,
            "title" to n.title,
            "content" to n.content,
            "folderPath" to n.folderPath,
            "isFolder" to n.isFolder,
            "createdAt" to n.createdAt,
            "updatedAt" to n.updatedAt,
            "parentId" to n.parentId
        )
    }

    fun getLocalIpAddress(): String {
        // Method 1: UDP socket trick — most reliable across all Android versions
        try {
            val socket = java.net.DatagramSocket()
            socket.soTimeout = 1500
            socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53)
            val ip = socket.localAddress.hostAddress
            socket.close()
            if (!ip.isNullOrEmpty() && ip != "0.0.0.0") return ip
        } catch (_: Exception) {}

        // Method 2: NetworkInterface enumeration
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

        // Method 3: ConnectivityManager LinkProperties
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
