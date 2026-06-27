package site.wuzeyu.phonote

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.fluid.afm.markdown.widget.PrinterMarkDownTextView
import com.fluid.afm.styles.MarkdownStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
class MainActivity : ComponentActivity() {
    private var httpServer: NotesHttpServer? = null
    private var onTreePicked: ((android.net.Uri) -> Unit)? = null

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onTreePicked?.invoke(it) }
    }

    private fun getPrefs() = getSharedPreferences("phonote_prefs", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val savedTheme = remember {
                val name = getPrefs().getString("theme_mode", "LIGHT") ?: "LIGHT"
                try { ThemeMode.valueOf(name) } catch (_: Exception) { ThemeMode.LIGHT }
            }
            val themeMode = remember { mutableStateOf(savedTheme) }
            val serverIpState = remember { mutableStateOf("获取中...") }
            val savedPort = remember { mutableStateOf(getPrefs().getInt("server_port", 8080).toString()) }

            LaunchedEffect(themeMode.value) {
                getPrefs().edit().putString("theme_mode", themeMode.value.name).apply()
            }

            PhonoteTheme(themeMode = themeMode.value) {
                PhonoteApp(
                    httpServer = httpServer,
                    onServerControl = { start -> controlServer(start, serverIpState) },
                    themeMode = themeMode.value,
                    onThemeChange = { themeMode.value = it },
                    onExport = { onTreePicked = { uri -> launchExport(uri) }; treePicker.launch(null) },
                    onImport = { parentId -> onTreePicked = { uri -> launchImport(uri, parentId) }; treePicker.launch(null) },
                    serverIp = serverIpState.value,
                    serverPort = savedPort.value,
                    onPortChange = { port ->
                        savedPort.value = port
                        getPrefs().edit().putInt("server_port", port.toIntOrNull() ?: 8080).apply()
                    }
                )
            }
        }
    }



    private fun controlServer(start: Boolean, serverIpState: MutableState<String>) {
        if (start) {
            if (httpServer == null) httpServer = NotesHttpServer(this, 8080) { }
            try { httpServer?.start() } catch (_: Exception) {}
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lifecycleScope.launch(Dispatchers.IO) {
                val ip = httpServer?.getLocalIpAddress() ?: "unknown"
                withContext(Dispatchers.Main) { serverIpState.value = ip }
            }
        } else {
            httpServer?.stop(); httpServer = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            serverIpState.value = "获取中..."
        }
    }

    private fun launchExport(treeUri: android.net.Uri) {
        val dao = (applicationContext as PhonoteApp).database.noteDao()
        lifecycleScope.launch(Dispatchers.IO) {
            val rootDir = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return@launch
            var skippedCount = 0; var updatedCount = 0

            fun doExport(parentId: Long, dir: DocumentFile) {
                val folders = runBlocking { dao.getFoldersByParent(parentId) }
                val notes = runBlocking { dao.getNotesByParent(parentId) }
                for (note in notes) {
                    val fileName = "${note.title}.md"
                    val existingFile = dir.findFile(fileName)
                    if (existingFile != null) {
                        val fileTime = existingFile.lastModified()
                        if (fileTime >= note.updatedAt) { skippedCount++; continue }
                        applicationContext.contentResolver.openOutputStream(existingFile.uri, "wt")?.use { it.write(note.content.toByteArray(Charsets.UTF_8)) }
                    } else {
                        dir.createFile("text/markdown", note.title)?.let { newFile ->
                            applicationContext.contentResolver.openOutputStream(newFile.uri)?.use { it.write(note.content.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                    updatedCount++
                }
                for (folder in folders) {
                    val subDir = dir.findFile(folder.title) ?: dir.createDirectory(folder.title)
                    if (subDir != null) doExport(folder.id, subDir)
                }
            }
            doExport(0, rootDir)
            withContext(Dispatchers.Main) { snackbarMessage.value = "导出完成：更新 $updatedCount 项，跳过 $skippedCount 项（无需更新）" }
        }
    }

    private fun launchImport(treeUri: android.net.Uri, parentId: Long) {
        val dao = (applicationContext as PhonoteApp).database.noteDao()
        lifecycleScope.launch(Dispatchers.IO) {
            var skippedCount = 0; var importedCount = 0
            val rootDir = DocumentFile.fromTreeUri(applicationContext, treeUri)
            if (rootDir == null) { withContext(Dispatchers.Main) { snackbarMessage.value = "导入失败：无法访问所选目录" }; return@launch }

            fun doImport(dir: DocumentFile, targetParentId: Long) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory) {
                        val existingFolder = runBlocking { dao.getFoldersByParent(targetParentId).find { it.title == name } }
                        val folderId = existingFolder?.id ?: runBlocking { dao.insert(NoteEntity(title = name, isFolder = true, parentId = targetParentId)) }
                        doImport(child, folderId)
                    } else if (name.endsWith(".md", ignoreCase = true)) {
                        val title = name.removeSuffix(".md").removeSuffix(".MD")
                        val content = try { applicationContext.contentResolver.openInputStream(child.uri)?.use { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: "" } catch (_: Exception) { "" }
                        val fileTime = child.lastModified()
                        val existingNote = runBlocking { dao.getNotesByParent(targetParentId).find { it.title == title } }
                        if (existingNote != null) {
                            if (fileTime <= existingNote.updatedAt) { skippedCount++ }
                            else { runBlocking { dao.update(existingNote.copy(content = content, updatedAt = fileTime)) }; importedCount++ }
                        } else {
                            runBlocking { dao.insert(NoteEntity(title = title, content = content, parentId = targetParentId, createdAt = fileTime, updatedAt = fileTime)) }; importedCount++
                        }
                    }
                }
            }
            doImport(rootDir, parentId)
            withContext(Dispatchers.Main) { snackbarMessage.value = "导入完成：更新 $importedCount 项，跳过 $skippedCount 项"; refreshTrigger.intValue++ }
        }
    }

    override fun onDestroy() { super.onDestroy(); httpServer?.stop() }
}

val snackbarMessage = mutableStateOf<String?>(null)
val refreshTrigger = mutableIntStateOf(0)
private var lastBackPress = 0L

enum class ThemeMode(val label: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }

@Composable
private fun resolveColor(@androidx.annotation.ColorRes resId: Int, isDark: Boolean): Color {
    val context = LocalContext.current
    val config = android.content.res.Configuration(context.resources.configuration).apply {
        uiMode = if (isDark) android.content.res.Configuration.UI_MODE_NIGHT_YES
        else android.content.res.Configuration.UI_MODE_NIGHT_NO
    }
    return Color(androidx.core.content.ContextCompat.getColor(context.createConfigurationContext(config), resId))
}

@Composable
fun PhonoteTheme(themeMode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = resolveColor(R.color.md_theme_dark_primary, isDark),
            onPrimary = resolveColor(R.color.md_theme_dark_onPrimary, isDark),
            primaryContainer = resolveColor(R.color.md_theme_dark_primaryContainer, isDark),
            onPrimaryContainer = resolveColor(R.color.md_theme_dark_onPrimaryContainer, isDark),
            secondary = resolveColor(R.color.md_theme_dark_secondary, isDark),
            onSecondary = resolveColor(R.color.md_theme_dark_onSecondary, isDark),
            secondaryContainer = resolveColor(R.color.md_theme_dark_secondaryContainer, isDark),
            onSecondaryContainer = resolveColor(R.color.md_theme_dark_onSecondaryContainer, isDark),
            background = resolveColor(R.color.md_theme_dark_background, isDark),
            onBackground = resolveColor(R.color.md_theme_dark_onBackground, isDark),
            surface = resolveColor(R.color.md_theme_dark_surface, isDark),
            onSurface = resolveColor(R.color.md_theme_dark_onSurface, isDark),
            surfaceVariant = resolveColor(R.color.md_theme_dark_surfaceVariant, isDark),
            onSurfaceVariant = resolveColor(R.color.md_theme_dark_onSurfaceVariant, isDark),
            outline = resolveColor(R.color.md_theme_dark_outline, isDark),
            error = resolveColor(R.color.md_theme_dark_error, isDark),
            onError = resolveColor(R.color.md_theme_dark_onError, isDark)
        )
    } else {
        lightColorScheme(
            primary = resolveColor(R.color.md_theme_light_primary, isDark),
            onPrimary = resolveColor(R.color.md_theme_light_onPrimary, isDark),
            primaryContainer = resolveColor(R.color.md_theme_light_primaryContainer, isDark),
            onPrimaryContainer = resolveColor(R.color.md_theme_light_onPrimaryContainer, isDark),
            secondary = resolveColor(R.color.md_theme_light_secondary, isDark),
            onSecondary = resolveColor(R.color.md_theme_light_onSecondary, isDark),
            secondaryContainer = resolveColor(R.color.md_theme_light_secondaryContainer, isDark),
            onSecondaryContainer = resolveColor(R.color.md_theme_light_onSecondaryContainer, isDark),
            background = resolveColor(R.color.md_theme_light_background, isDark),
            onBackground = resolveColor(R.color.md_theme_light_onBackground, isDark),
            surface = resolveColor(R.color.md_theme_light_surface, isDark),
            onSurface = resolveColor(R.color.md_theme_light_onSurface, isDark),
            surfaceVariant = resolveColor(R.color.md_theme_light_surfaceVariant, isDark),
            onSurfaceVariant = resolveColor(R.color.md_theme_light_onSurfaceVariant, isDark),
            outline = resolveColor(R.color.md_theme_light_outline, isDark),
            error = resolveColor(R.color.md_theme_light_error, isDark),
            onError = resolveColor(R.color.md_theme_light_onError, isDark)
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhonoteApp(
    httpServer: NotesHttpServer?, onServerControl: (Boolean) -> Unit,
    themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit,
    onExport: () -> Unit, onImport: (Long) -> Unit,
    serverIp: String, serverPort: String, onPortChange: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PhonoteApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = context as? ComponentActivity

    var folders by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var notes by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var currentFolderId by remember { mutableLongStateOf(0L) }
    var folderStack by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var isViewMode by remember { mutableStateOf(false) }
    var serverRunning by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showServerInfo by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var deleteTargetFolder by remember { mutableStateOf<NoteEntity?>(null) }
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var highlightKeyword by remember { mutableStateOf("") }

    val msg = snackbarMessage.value
    LaunchedEffect(msg) { msg?.let { snackbarHostState.showSnackbar(it); snackbarMessage.value = null } }

    suspend fun loadNotes() = withContext(Dispatchers.IO) {
        folders = app.database.noteDao().getFoldersByParent(currentFolderId)
        notes = app.database.noteDao().getNotesByParent(currentFolderId)
    }
    LaunchedEffect(currentFolderId) { loadNotes() }
    LaunchedEffect(refreshTrigger.intValue) { if (refreshTrigger.intValue > 0) loadNotes() }

    BackHandler(enabled = true) {
        when {
            editingNote != null -> { editingNote = null; isViewMode = false; highlightKeyword = ""; scope.launch { loadNotes() } }
            showSearch -> { showSearch = false }
            batchMode -> { batchMode = false; selectedIds = emptySet() }
            folderStack.isNotEmpty() -> { folderStack = folderStack.dropLast(1); currentFolderId = if (folderStack.isNotEmpty()) folderStack.last().first else 0L }
            else -> { if (System.currentTimeMillis() - lastBackPress < 2000) activity?.finish() else { lastBackPress = System.currentTimeMillis(); Toast.makeText(context, "再按一次返回键退出", Toast.LENGTH_SHORT).show() } }
        }
    }

    if (showSearch) {
        Log.d("Phonote", "Showing SearchScreen")
        SearchScreen(query = searchQuery, results = searchResults, highlightKeyword = highlightKeyword,
            onQueryChange = { q -> searchQuery = q; searchResults = if (q.isBlank()) emptyList() else runBlocking { app.database.noteDao().search(q).filter { !it.isFolder } } },
            onOpenNote = { note, kw -> highlightKeyword = kw; showSearch = false; editingNote = note },
            onBack = { showSearch = false })
        return@PhonoteApp
    }

    if (editingNote != null) {
        Log.d("Phonote", "Showing NoteEditorScreen for: ${editingNote!!.title}")
        NoteEditorScreen(note = editingNote!!, isViewMode = isViewMode, highlightKeyword = highlightKeyword,
            onBack = { editingNote = null; isViewMode = false; highlightKeyword = ""; scope.launch { loadNotes() } },
            onToggleMode = { isViewMode = !isViewMode },
            onSave = { t, c -> scope.launch(Dispatchers.IO) { app.database.noteDao().update(editingNote!!.copy(title = t, content = c, updatedAt = System.currentTimeMillis())) } },
            onDelete = { scope.launch(Dispatchers.IO) { app.database.noteDao().deleteByIdCascade(editingNote!!.id); withContext(Dispatchers.Main) { editingNote = null; isViewMode = false; highlightKeyword = ""; loadNotes(); snackbarHostState.showSnackbar("已删除") } } })
        return@PhonoteApp
    }

    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("设置") },
            text = {
                Column {
                    Text("外观", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(4.dp))
                    ThemeMode.entries.forEach { mode ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { onThemeChange(mode) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (themeMode == mode) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            else Spacer(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(mode.label)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("网络", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(4.dp))
                    var portText by remember { mutableStateOf(serverPort) }
                    OutlinedTextField(value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() }; onPortChange(portText) },
                        label = { Text("HTTP 端口") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("数据", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showSettingsDialog = false; onExport() }, modifier = Modifier.weight(1f)) { Text("导出") }
                        Button(onClick = { showSettingsDialog = false; onImport(currentFolderId) }, modifier = Modifier.weight(1f)) { Text("导入") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("关闭") } }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (batchMode) {
                TopAppBar(title = { Text("已选择 ${selectedIds.size} 项") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = Color.White),
                    navigationIcon = { IconButton(onClick = { batchMode = false; selectedIds = emptySet() }) { Icon(Icons.Filled.Close, "取消", tint = Color.White) } },
                    actions = {
                        IconButton(onClick = { selectedIds = (folders.map { it.id } + notes.map { it.id }).toSet() }) { Icon(Icons.Filled.SelectAll, "全选", tint = Color.White) }
                        if (selectedIds.isNotEmpty()) IconButton(onClick = { showBatchDeleteDialog = true }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFFFCDD2)) }
                    })
            } else {
                TopAppBar(title = { Text("Phonote") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = Color.White),
                    navigationIcon = { IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Filled.Settings, "设置", tint = Color.White) } },
                    actions = {
                        IconButton(onClick = { showNewFolderDialog = true }) { Icon(Icons.Filled.CreateNewFolder, "新建文件夹", tint = Color.White) }
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { val id = app.database.noteDao().insert(NoteEntity(title = "新笔记", parentId = currentFolderId)); val n = app.database.noteDao().getById(id); withContext(Dispatchers.Main) { editingNote = n } } }) { Icon(Icons.Filled.NoteAdd, "新建笔记", tint = Color.White) }
                        IconButton(onClick = { Log.d("Phonote", "Search button clicked"); showSearch = true }) { Icon(Icons.Filled.Search, "搜索", tint = Color.White) }
                        IconButton(onClick = { serverRunning = !serverRunning; onServerControl(serverRunning); if (serverRunning) showServerInfo = true }) {
                            Icon(if (serverRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Server", tint = Color.White) }
                    })
            }
        },
        floatingActionButton = {}
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (showServerInfo && serverRunning) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("HTTP 服务已启动", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("http://$serverIp:$serverPort", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("同一 WiFi 网络下的设备可通过浏览器访问", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                }
            }
            if (folderStack.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("全部", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { currentFolderId = 0; folderStack = emptyList() })
                    folderStack.forEach { (id, name) ->
                        Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(16.dp))
                        Text(name, color = if (id == currentFolderId) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { val idx = folderStack.indexOfFirst { it.first == id }; folderStack = folderStack.take(idx + 1); currentFolderId = id })
                    }
                }
            }
            if (folders.isEmpty() && notes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.FolderOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无内容", color = MaterialTheme.colorScheme.outline)
                        Text("点击右下角按钮创建", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                    }
                }
            } else {
                PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { isRefreshing = true; scope.launch { loadNotes(); delay(300); isRefreshing = false } }, state = rememberPullToRefreshState(), modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(folders, key = { it.id }) { folder ->
                            val sel = selectedIds.contains(folder.id); var showMenu by remember { mutableStateOf(false) }
                            ListItem(headlineContent = { Text(folder.title, fontWeight = FontWeight.Medium) },
                                leadingContent = { if (batchMode) Checkbox(checked = sel, onCheckedChange = { selectedIds = if (sel) selectedIds - folder.id else selectedIds + folder.id }) else Icon(Icons.Filled.Folder, null, tint = Color(0xFFFFC107)) },
                                trailingContent = { if (!batchMode) Box { IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "更多") }; DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) { DropdownMenuItem(text = { Text("删除", color = Color(0xFFEF5350)) }, onClick = { showMenu = false; deleteTargetFolder = folder }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color(0xFFEF5350)) }) } } },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (batchMode) {
                                            selectedIds = if (sel) selectedIds - folder.id else selectedIds + folder.id
                                        } else {
                                            folderStack = folderStack + (folder.id to folder.title)
                                            currentFolderId = folder.id
                                        }
                                    },
                                    onLongClick = {
                                        if (!batchMode) {
                                            batchMode = true
                                            selectedIds = setOf(folder.id)
                                        }
                                    }
                                ))
                            HorizontalDivider(modifier = Modifier.padding(start = if (batchMode) 0.dp else 56.dp))
                        }
                        items(notes, key = { it.id }) { note ->
                            val sel = selectedIds.contains(note.id)
                            val preview = note.content.take(80).replace(Regex("[#*`>\\[\\]]"), "").trim()
                            ListItem(headlineContent = { Text(note.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { if (preview.isNotEmpty()) Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline) },
                                leadingContent = { if (batchMode) Checkbox(checked = sel, onCheckedChange = { selectedIds = if (sel) selectedIds - note.id else selectedIds + note.id }) else Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { if (!batchMode) Text(formatTime(note.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) },
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (batchMode) {
                                            selectedIds = if (sel) selectedIds - note.id else selectedIds + note.id
                                        } else {
                                            editingNote = note
                                        }
                                    },
                                    onLongClick = {
                                        if (!batchMode) {
                                            batchMode = true
                                            selectedIds = setOf(note.id)
                                        }
                                    }
                                ))
                            HorizontalDivider(modifier = Modifier.padding(start = if (batchMode) 0.dp else 56.dp))
                        }
                    }
                }
            }
        }
    }

    if (showBatchDeleteDialog) { val count = selectedIds.size; AlertDialog(onDismissRequest = { showBatchDeleteDialog = false }, title = { Text("批量删除") }, text = { Text("确定要删除选中的 $count 项吗？此操作不可恢复。") }, confirmButton = { TextButton(onClick = { showBatchDeleteDialog = false; scope.launch(Dispatchers.IO) { selectedIds.forEach { id -> app.database.noteDao().deleteByIdCascade(id) }; withContext(Dispatchers.Main) { batchMode = false; selectedIds = emptySet(); loadNotes(); snackbarHostState.showSnackbar("已删除 $count 项") } } }) { Text("删除", color = Color(0xFFEF5350)) } }, dismissButton = { TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") } }) }
    if (deleteTargetFolder != null) { AlertDialog(onDismissRequest = { deleteTargetFolder = null }, title = { Text("确认删除") }, text = { Text("确定要删除文件夹 \"${deleteTargetFolder!!.title}\" 及其所有内容吗？此操作不可恢复。") }, confirmButton = { TextButton(onClick = { val f = deleteTargetFolder!!; deleteTargetFolder = null; scope.launch(Dispatchers.IO) { app.database.noteDao().deleteByIdCascade(f.id); withContext(Dispatchers.Main) { loadNotes() } } }) { Text("删除", color = Color(0xFFEF5350)) } }, dismissButton = { TextButton(onClick = { deleteTargetFolder = null }) { Text("取消") } }) }
    if (showNewFolderDialog) { var folderName by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { showNewFolderDialog = false }, title = { Text("新建文件夹") }, text = { OutlinedTextField(value = folderName, onValueChange = { folderName = it }, label = { Text("文件夹名称") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (folderName.isNotBlank()) { scope.launch(Dispatchers.IO) { app.database.noteDao().insert(NoteEntity(title = folderName.trim(), isFolder = true, parentId = currentFolderId)); withContext(Dispatchers.Main) { showNewFolderDialog = false; loadNotes() } } } }) { Text("创建") } }, dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(query: String, results: List<NoteEntity>, highlightKeyword: String, onQueryChange: (String) -> Unit, onOpenNote: (NoteEntity, String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { OutlinedTextField(value = query, onValueChange = onQueryChange, placeholder = { Text("搜索笔记...") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface)) }) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(results, key = { it.id }) { note ->
                val titleHL = highlightMatch(note.title, query)
                val contentSnippet = getContentSnippet(note.content, query)
                val contentHL = highlightMatch(contentSnippet, query)
                val mc = countMatches(note.content, query)
                Column(modifier = Modifier.fillMaxWidth().clickable { onOpenNote(note, query) }) {
                    ListItem(headlineContent = { Text(titleHL, fontWeight = FontWeight.Medium, maxLines = 1) }, supportingContent = { Column { if (contentSnippet.isNotEmpty()) Text(contentHL, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline, fontSize = 13.sp); if (mc > 1) Text("共 $mc 处匹配", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp)) } }, leadingContent = { Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) })
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

private fun getContentSnippet(content: String, query: String): String {
    if (query.isBlank() || content.isBlank()) return ""
    val idx = content.lowercase().indexOf(query.lowercase())
    if (idx < 0) return content.take(150).replace(Regex("[#*`>\\[\\]]"), "").trim()
    val start = (idx - 60).coerceAtLeast(0)
    val end = (idx + query.length + 60).coerceAtMost(content.length)
    var snippet = content.substring(start, end).trim()
    if (start > 0) snippet = "...$snippet"
    if (end < content.length) snippet = "$snippet..."
    return snippet
}

private fun highlightMatch(text: String, keyword: String): androidx.compose.ui.text.AnnotatedString {
    if (keyword.isBlank()) return androidx.compose.ui.text.AnnotatedString(text)
    return buildAnnotatedString { var s = 0; val lt = text.lowercase(); val lk = keyword.lowercase(); while (true) { val i = lt.indexOf(lk, s); if (i < 0) { append(text.substring(s)); break }; append(text.substring(s, i)); withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFF6F00))) { append(text.substring(i, i + keyword.length)) }; s = i + keyword.length } }
}

private fun countMatches(text: String, keyword: String): Int {
    if (keyword.isBlank()) return 0; var c = 0; var i = 0; val lt = text.lowercase(); val lk = keyword.lowercase(); while (true) { i = lt.indexOf(lk, i); if (i < 0) break; c++; i += lk.length }; return c
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(note: NoteEntity, isViewMode: Boolean, highlightKeyword: String = "", onBack: () -> Unit, onToggleMode: () -> Unit, onSave: (String, String) -> Unit, onDelete: () -> Unit) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var content by remember(note.id) { mutableStateOf(note.content) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var autoSaveText by remember(note.id) { mutableStateOf("") }
    var hasChanged by remember(note.id) { mutableStateOf(false) }
    val titleFocusRequester = remember { FocusRequester() }
    var titleFieldValue by remember(note.id) { mutableStateOf(TextFieldValue(note.title, selection = androidx.compose.ui.text.TextRange(note.title.length))) }

    LaunchedEffect(note.id) {
        if (note.title.startsWith("新笔记")) {
            titleFieldValue = titleFieldValue.copy(selection = androidx.compose.ui.text.TextRange(0, note.title.length))
            titleFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(title, content) { if (!hasChanged) return@LaunchedEffect; delay(3500); autoSaveText = "自动保存中..."; onSave(title, content); delay(500); autoSaveText = "已自动保存"; delay(2500); autoSaveText = "" }

    val matchPositions = remember(content, highlightKeyword) { if (highlightKeyword.isBlank()) emptyList() else { val pos = mutableListOf<Int>(); var i = 0; val lc = content.lowercase(); val lk = highlightKeyword.lowercase(); while (true) { i = lc.indexOf(lk, i); if (i < 0) break; pos.add(i); i += lk.length }; pos } }
    var showMatchNav by remember { mutableStateOf(false) }
    var currentMatchIdx by remember { mutableIntStateOf(0) }

    Scaffold(topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { Text("编辑", fontSize = 16.sp); if (autoSaveText.isNotEmpty()) { Spacer(modifier = Modifier.width(8.dp)); Text(autoSaveText, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)) } } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
            actions = {
                if (matchPositions.size > 1) IconButton(onClick = { showMatchNav = !showMatchNav }) { Icon(Icons.Filled.FormatListNumbered, "匹配位置", tint = Color.White) }
                IconButton(onClick = onToggleMode) { Icon(if (isViewMode) Icons.Filled.Edit else Icons.Filled.Visibility, null) }
                IconButton(onClick = { onSave(title, content); autoSaveText = "已保存"; hasChanged = false }) { Icon(Icons.Filled.Save, "保存") }
                IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFEF5350)) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
    }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (showMatchNav && matchPositions.isNotEmpty()) { Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(modifier = Modifier.padding(8.dp)) { Text("匹配位置 (${currentMatchIdx + 1}/${matchPositions.size})", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline); matchPositions.forEachIndexed { index, pos -> val ls = content.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)) + 1; val le = content.indexOf('\n', pos).let { if (it < 0) content.length else it }; val snip = content.substring(ls, le).take(60); Row(modifier = Modifier.fillMaxWidth().clickable { currentMatchIdx = index }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text("${index + 1}. ", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(snip, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (index == currentMatchIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } } } } }
            OutlinedTextField(value = titleFieldValue, onValueChange = { titleFieldValue = it; title = it.text; hasChanged = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(titleFocusRequester), label = { Text("标题") }, singleLine = true)
            if (isViewMode) MarkdownPreview(content = content, modifier = Modifier.fillMaxSize().padding(16.dp)) else OutlinedTextField(value = content, onValueChange = { content = it; hasChanged = true }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), label = { Text("内容 (Markdown)") }, textStyle = TextStyle(fontSize = 15.sp, lineHeight = 24.sp))
        }
    }
    if (showDeleteDialog) { AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("确认删除") }, text = { Text("确定要删除 \"${note.title}\" 吗？此操作不可恢复。") }, confirmButton = { TextButton(onClick = { showDeleteDialog = false; onDelete() }) { Text("删除", color = Color(0xFFEF5350)) } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }) }
}

class MdScrollView(context: android.content.Context) : android.widget.ScrollView(context) {
    var mdView: PrinterMarkDownTextView? = null
}

@Composable
fun MarkdownPreview(content: String, modifier: Modifier = Modifier) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    AndroidView(
        factory = { ctx ->
            val scrollView = MdScrollView(ctx)
            val mdView = PrinterMarkDownTextView(ctx)
            mdView.setTextColor(if (isDark) android.graphics.Color.parseColor("#E0E0E0") else android.graphics.Color.parseColor("#1A1A1A"))
            mdView.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.WHITE)
            mdView.setPadding(32, 16, 32, 16)
            mdView.textSize = 15f
            mdView.isVerticalScrollBarEnabled = true
            try {
                val styles = MarkdownStyles.getDefaultStyles()
                if (isDark) {
                    styles.codeStyle().inlineCodeBackgroundColor(android.graphics.Color.parseColor("#2C2C2C"))
                    styles.codeStyle().inlineFontColor(android.graphics.Color.parseColor("#E0E0E0"))
                    styles.codeStyle().codeBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                    styles.codeStyle().codeFontColor(android.graphics.Color.parseColor("#ABB2BF"))
                    styles.codeStyle().titleFontColor(android.graphics.Color.parseColor("#636D83"))
                    styles.codeStyle().titleBackgroundColor(android.graphics.Color.parseColor("#252525"))
                    styles.paragraphStyle().fontColor(android.graphics.Color.parseColor("#E0E0E0"))
                    styles.tableStyle().fontColor(android.graphics.Color.parseColor("#E0E0E0"))
                    styles.tableStyle().bodyBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
                    styles.tableStyle().headerBackgroundColor(android.graphics.Color.parseColor("#2C2C2C"))
                    styles.tableStyle().borderColor(android.graphics.Color.parseColor("#424242"))
                    styles.tableStyle().titleBackgroundColor(android.graphics.Color.parseColor("#252525"))
                    styles.tableStyle().titleFontColor(android.graphics.Color.parseColor("#9E9E9E"))
                } else {
                    styles.codeStyle().inlineCodeBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                    styles.codeStyle().inlineFontColor(android.graphics.Color.parseColor("#D63384"))
                    styles.codeStyle().codeBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
                    styles.codeStyle().codeFontColor(android.graphics.Color.parseColor("#333333"))
                    styles.codeStyle().titleFontColor(android.graphics.Color.parseColor("#666666"))
                    styles.codeStyle().titleBackgroundColor(android.graphics.Color.parseColor("#E8E8E8"))
                    styles.paragraphStyle().fontColor(android.graphics.Color.parseColor("#1A1A1A"))
                    styles.tableStyle().fontColor(android.graphics.Color.parseColor("#333333"))
                    styles.tableStyle().bodyBackgroundColor(android.graphics.Color.WHITE)
                    styles.tableStyle().headerBackgroundColor(android.graphics.Color.parseColor("#F6F7F9"))
                    styles.tableStyle().borderColor(android.graphics.Color.parseColor("#DEDEDE"))
                    styles.tableStyle().titleBackgroundColor(android.graphics.Color.parseColor("#EDEFF3"))
                    styles.tableStyle().titleFontColor(android.graphics.Color.parseColor("#999999"))
                }
                mdView.init(styles, null)
            } catch (_: Exception) {}
            scrollView.addView(mdView, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            scrollView.mdView = mdView
            scrollView
        },
        update = { scrollView ->
            val mdView = (scrollView as MdScrollView).mdView ?: return@AndroidView
            mdView.setTextColor(if (isDark) android.graphics.Color.parseColor("#E0E0E0") else android.graphics.Color.parseColor("#1A1A1A"))
            mdView.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#1E1E1E") else android.graphics.Color.WHITE)
            mdView.setMarkdownText(content)
        },
        modifier = modifier
    )
}

private fun androidx.compose.ui.graphics.Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue

private fun formatTime(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
