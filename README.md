# Phonote -- a note app with http-server/支持网页服务的笔记app

这是一个手机上的笔记软件，同时提供了一个简易的http服务器，支持在电脑网页上查看和编辑笔记。

This is a note-taking application on mobile phones, which also provides a simple HTTP server, allowing notes to be viewed and edited on a computer's web browser.

本项目开发过程中使用AI辅助工具。

This project was developed with AI assistance.

## 简介 Introduction

Phonote本身是一个用于记录md格式的笔记软件，主页面使用文件夹管理各个md笔记。默认都是编辑模式，但是也有一个查看模式用于查看渲染后的笔记。
这个安卓软件可以启用一个http服务，用于在本地局域网内提供一个网页版的笔记网页，共享手机上的这些笔记，也可以一起编辑。网页版比较简单，不需要鉴权。

Phonote is a note-taking app for recording notes in md format, and the main page uses folders to manage various md notes. By default, it’s in edit mode, but there’s also a view mode for checking out the rendered notes. This Android app can start an HTTP service to provide a web version of the notes on the local network, so you can share the notes on your phone and even edit them together. The web version is pretty simple and doesn’t require authentication.

## 下载安装 Installation

在release中下载编译后的安装包

## 使用方法 How to use

所有可选按钮在最上方，从左到右依次是“设置”，“新建文件夹”，“新建文件”，“搜索”，“启动服务器”

All the option buttons are at the top. From left to right, they are 'Settings,' 'New Folder,' 'New File,' 'Search,' and 'Start Server'.

"设置"中可以修改外观，语言，服务器端口，导入或导出笔记。

In 'Settings,' you can change the appearance, language, server port, and import or export notes.

在点击“启动服务器”后，可以在相同局域网的计算机浏览器中输入显示的http://ip:post进入前端页面。同时可以进入http://ip:post/admin以灵活修改前端界面

## 特性 Features

1. 自动保存：手机和网页版在修改后5秒左右会自动保存
2. 导入导出：可以按照目录结构导入和导出md文件。
3. 批量管理：批量删除文件夹和笔记
4. 搜索：搜索笔记内容
5. 支持浅色和深色模式
6. 可自定义网页版的端口
7. 开启服务器后自动阻止息屏
8. 安卓应用内部md迁移antgroup的fluid-markdown项目，支持表格、代码高亮、latex公式。
9. 网页版开放/dev/路由的文件，放在/Android/data/site.wuzeyu.phonote/files/web/目录下，方便前端开发。
10. 多国语言支持。

1. Auto-save: Both mobile and web versions automatically save about 5 seconds after changes.
2. Import/export: You can import and export md files according to the directory structure.
3. Batch management: Delete folders and notes in batches.
4. Search: Search note contents.
5. Supports light and dark modes.
6. Web version port is customizable.
7. Automatically prevents screen from sleeping when the server is on.
8. Android app supports md migration from antgroup's fluid-markdown project, including tables, code highlighting, and LaTeX formulas.
9. Web version opens files under /dev/ route, placed in /Android/data/site.wuzeyu.phonote/files/web/ for easier front-end development.
10. Multi-language support.

# DEVELOPMENT

## 项目结构 Project Structure

```
phonote/
├── app/src/main/java/site/wuzeyu/phonote/
│   ├── MainActivity.kt        # 主界面，包含所有 Compose UI、主题、导航逻辑
│   │                           # Main screen: all Compose UI, theming, navigation
│   ├── PhonoteApp.kt          # Application 类，初始化数据库、通知渠道、Markdown 渲染库
│   │                           # Application class: DB init, notification channel, Markdown init
│   ├── AppDatabase.kt         # Room 数据库单例（phonote_database）
│   │                           # Room database singleton
│   ├── NoteDao.kt             # Room DAO + NoteEntity 数据模型
│   │                           # Room DAO + NoteEntity data model
│   ├── NotesHttpServer.kt     # NanoHTTPD 内嵌 HTTP 服务器，提供 REST API + Web 前端
│   │                           # Embedded HTTP server with REST API + Web frontend
│   └── SimpleImageHandler.kt  # Markdown 图片下载器
│                               # Markdown image downloader
├── app/src/main/res/
│   ├── values/strings.xml         # 英文字符串（默认语言）/ English strings (default)
│   ├── values-zh/strings.xml      # 简体中文 / Simplified Chinese
│   ├── values-ja/strings.xml      # 日文 / Japanese
│   ├── values-zh-rTW/strings.xml  # 繁体中文 / Traditional Chinese
│   ├── values/colors.xml          # Material 3 浅色主题色 / Light theme colors
│   ├── values-night/colors.xml    # Material 3 深色主题色 / Dark theme colors
│   └── values/themes.xml          # XML 主题定义 / XML theme definitions
├── app/src/main/assets/
│   └── index.html             # 网页版前端 SPA / Web frontend SPA
├── fluid-markdown/            # AntGroup FluidMarkdown 库（Java）
│                               # AntGroup FluidMarkdown library
├── markwon-*/                 # Markwon 系列模块（本地 fork）
│                               # Markwon modules (local fork)
└── tools/
    └── dev_server.py          # 前端开发用 Flask Mock 服务器
                               # Flask mock server for frontend development
```

### 关键设计 Key Design Decisions

- **单 Activity 架构**：所有 UI 在 `MainActivity.kt` 中通过 Jetpack Compose 实现
  Single Activity: all UI in `MainActivity.kt` via Jetpack Compose

- **单表数据库**：`NoteEntity` 同时存储笔记和文件夹，通过 `parentId` 构建层级树
  Single-table DB: `NoteEntity` stores both notes and folders, hierarchy via `parentId`

- **主题切换**：`resolveColor()` 通过 `createConfigurationContext` 强制指定 night mode 解析颜色资源，避免设备配置限定符导致的崩溃
  Theming: `resolveColor()` uses `createConfigurationContext` to force night mode for color resolution, preventing device-qualifier crashes

- **多语言**：通过 `attachBaseContext` 覆写 locale 配置，支持动态切换语言（切换后 Activity 重建）
  i18n: locale override via `attachBaseContext`, supports dynamic language switching (Activity recreates)

- **HTTP 服务器**：NanoHTTPD 在独立端口运行，提供 REST API 和 Web 前端
  HTTP Server: NanoHTTPD runs on a configurable port, serves REST API and Web frontend

### 数据库结构 Database Schema

表名 `notes`（Room，数据库版本 1）/ Table `notes` (Room, DB version 1)

| 字段 Field | 类型 Type | 说明 Description |
|---|---|---|
| `id` | `Long` | 主键，自动生成 / Primary key, auto-generated |
| `title` | `String` | 标题 / Title |
| `content` | `String` | Markdown 内容，默认 `""` / Markdown content, default `""` |
| `folderPath` | `String` | 文件夹路径，默认 `""` / Folder path, default `""` |
| `isFolder` | `Boolean` | 是否为文件夹 / Whether this is a folder |
| `createdAt` | `Long` | 创建时间戳（毫秒）/ Created timestamp (ms) |
| `updatedAt` | `Long` | 更新时间戳（毫秒）/ Updated timestamp (ms) |
| `parentId` | `Long` | 父级 ID，`0` 表示根目录 / Parent ID, `0` = root level |

笔记和文件夹共用同一张表，通过 `isFolder` 字段区分。层级关系通过 `parentId` 自引用实现。

Notes and folders share the same table, distinguished by `isFolder`. The tree hierarchy is built via self-referencing `parentId`.

#### 主要查询 Main Queries

| 方法 Method | 说明 Description |
|---|---|
| `getFoldersByParent(parentId)` | 获取某级文件夹下的子文件夹（按标题排序）/ Get sub-folders (sorted by title) |
| `getNotesByParent(parentId)` | 获取某级文件夹下的笔记（按更新时间倒序）/ Get notes (sorted by updatedAt DESC) |
| `search(query)` | 按标题和内容模糊搜索 / Fuzzy search by title and content |
| `deleteByIdCascade(id)` | 删除项目及其直接子项（一级级联）/ Delete item and its direct children (one-level cascade) |

### HTTP 服务器 API HTTP Server API

服务器通过手机应用启动，监听可配置端口（默认 8080）。所有 API 返回 JSON。
The server runs on the Android app on a configurable port (default 8080). All APIs return JSON.

#### 笔记 Notes

| 方法 Method | 路径 Path | 说明 Description | 请求体 Request Body |
|---|---|---|---|
| GET | `/api/notes?folderId=0` | 获取文件夹内容 / List folders and notes in a folder | — |
| GET | `/api/notes/search?q=keyword` | 搜索笔记 / Search notes | — |
| GET | `/api/notes/{id}` | 获取单条笔记 / Get a single note | — |
| POST | `/api/notes` | 创建笔记或文件夹 / Create a note or folder | `{"title", "content", "folderId", "isFolder"}` |
| PUT | `/api/notes/{id}` | 更新笔记 / Update a note | `{"title", "content"}` |
| DELETE | `/api/notes/{id}` | 删除项目及子项 / Delete item and children | — |

#### 网页模板 Web Template

| 方法 Method | 路径 Path | 说明 Description |
|---|---|---|
| GET | `/` | 加载网页版主页 / Load web frontend |
| GET | `/admin` | 打开网页模板编辑器 / Open template editor |
| GET | `/api/html` | 获取当前模板 / Get current template |
| PUT | `/api/html` | 更新模板 / Update template (`{"html": "..."}`) |
| DELETE | `/api/html` | 恢复默认模板 / Reset to default template |

#### 开发路由 Dev Route

| 方法 Method | 路径 Path | 说明 Description |
|---|---|---|
| GET | `/dev` | 获取开发目录路径 / Get dev directory path |
| GET | `/dev/{path}` | 加载外部文件 / Serve file from `/Android/data/site.wuzeyu.phonote/files/web/` |

#### 响应示例 Response Examples

```json
// GET /api/notes?folderId=0
{
  "folders": [{"id": 1, "title": "Projects", "isFolder": true, "parentId": 0, ...}],
  "notes": [{"id": 2, "title": "My Note", "content": "# Hello", "isFolder": false, ...}]
}

// POST /api/notes
{"id": 3, "title": "New Note"}

// GET /api/notes/3
{"id": 3, "title": "New Note", "content": "", "createdAt": 1719000000000, ...}
```

## 开源协议 License

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。

This project is licensed under the GPL-3.0 License.

## 第三方依赖 Third-party Dependencies

| 组件 Component  | 许可证 LICENSE |
|------------------------------------------------------------|-------------|
| [FluidMarkdown](https://github.com/antgroup/FluidMarkdown) | Apache 2.0  |
| [Markwon](https://github.com/noties/Markwon)               | Apache 2.0  |
| [NanoHTTPD](https://github.com/NanoHTTPD/nanohttpd)        | MIT         |
| [Gson](https://github.com/google/gson)                     | Apache 2.0  |
