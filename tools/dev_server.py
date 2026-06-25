"""
Phonote Frontend Dev Server
Simulates the Android HTTP server API for local frontend development.

Usage:
    cd D:\pdev\phonote\tools
    pip install -r requirements.txt
    python dev_server.py

Then open http://localhost:8080 in your browser.
Edit files in tools/web/ and refresh to see changes.
"""

import os
import json
import time
from flask import Flask, request, jsonify, send_from_directory, send_file

app = Flask(__name__)

WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")

# In-memory mock database
notes_db = [
    {"id": 1, "title": "示例笔记", "content": 
     '''
# Welcome to Leanote! 欢迎来到Leanote!
 
## 1. 排版
 
**粗体** *斜体* 
 
~~这是一段错误的文本。~~
 
引用:
 
> 引用Leanote官方的话, 为什么要做Leanote, 原因是...
 
有充列表:
 1. 支持Vim
 2. 支持Emacs
 
无序列表:
 
 - 项目1
 - 项目2
 
 
## 2. 图片与链接
 
图片:
![leanote](http://leanote.com/images/logo/leanote_icon_blue.png)
链接:
 
[这是去往Leanote官方博客的链接](http://leanote.leanote.com)
 
## 3. 标题
 
以下是各级标题, 最多支持5级标题
 

# h1
## h2
### h3
#### h4
##### h4
###### h5

 
## 4. 代码
 
示例:
 
    function get(key) {
        return m[key];
    }
    
代码高亮示例:
 
```javascript
/**
* nth element in the fibonacci series.
* @param n >= 0
* @return the nth element, >= 0.
*/
function fib(n) {
  var a = 1, b = 1;
  var tmp;
  while (--n >= 0) {
    tmp = a;
    a += b;
    b = tmp;
  }
  return a;
}
 
document.write(fib(10));
```
 
```python
class Employee:
   empCount = 0
 
   def __init__(self, name, salary):
        self.name = name
        self.salary = salary
        Employee.empCount += 1
```
 
# 5. Markdown 扩展
 
Markdown 扩展支持:
 
* 表格
* 定义型列表
* Html 标签
* 脚注
* 目录
* 时序图与流程图
* MathJax 公式
 
## 5.1 表格
 
Item     | Value
-------- | ---
Computer | \$1600
Phone    | \$12
Pipe     | \$1
 
可以指定对齐方式, 如Item列左对齐, Value列右对齐, Qty列居中对齐
 
| Item     | Value | Qty   |
| :------- | ----: | :---: |
| Computer | \$1600 |  5    |
| Phone    | \$12   |  12   |
| Pipe     | \$1    |  234  |
 
 
## 5.2 定义型列表
 
名词 1
:   定义 1（左侧有一个可见的冒号和四个不可见的空格）
 
代码块 2
:   这是代码块的定义（左侧有一个可见的冒号和四个不可见的空格）
 
        代码块（左侧有八个不可见的空格）
 
## 5.3 Html 标签
 
支持在 Markdown 语法中嵌套 Html 标签，譬如，你可以用 Html 写一个纵跨两行的表格：
 

    <table>
        <tr>
            <th rowspan="2">值班人员</th>
            <th>星期一</th>
            <th>星期二</th>
            <th>星期三</th>
        </tr>
        <tr>
            <td>李强</td>
            <td>张明</td>
            <td>王平</td>
        </tr>
    </table>
 
 
<table>
    <tr>
        <th rowspan="2">值班人员</th>
        <th>星期一</th>
        <th>星期二</th>
        <th>星期三</th>
    </tr>
    <tr>
        <td>李强</td>
        <td>张明</td>
        <td>王平</td>
    </tr>
</table>
 
**提示**, 如果想对图片的宽度和高度进行控制, 你也可以通过img标签, 如:
 
<img src="http://leanote.com/images/logo/leanote_icon_blue.png" width="50px" />
 
## 5.4 脚注
 
Leanote[^footnote]来创建一个脚注
  [^footnote]: Leanote是一款强大的开源云笔记产品.
 
## 5.5 目录
 
通过 `[TOC]` 在文档中插入目录, 如:
 
[TOC]
 
## 5.6 时序图与流程图
 
```sequence
Alice->Bob: Hello Bob, how are you?
Note right of Bob: Bob thinks
Bob-->Alice: I am good thanks!
```
 
流程图:
 
```flow
st=>start: Start
e=>end
op=>operation: My Operation
cond=>condition: Yes or No?
 
st->op->cond
cond(yes)->e
cond(no)->op
```
 
> **提示:** 更多关于时序图与流程图的语法请参考:
 
> - [时序图语法](http://bramp.github.io/js-sequence-diagrams/)
> - [流程图语法](http://adrai.github.io/flowchart.js)
 
## 5.7 MathJax 公式
 
$ 表示行内公式： 
 
质能守恒方程可以用一个很简洁的方程式 $E=mc^2$ 来表达。
 
$$ 表示整行公式：
 
$$\sum_{i=1}^n a_i=0$$
 
$$f(x_1,x_x,\ldots,x_n) = x_1^2 + x_2^2 + \cdots + x_n^2 $$
 
$$\sum^{j-1}_{k=0}{\widehat{\gamma}_{kj} z_k}$$
 
更复杂的公式:
$$
\begin{eqnarray}
\vec\nabla \times (\vec\nabla f) & = & 0  \cdots\cdots梯度场必是无旋场\\
\vec\nabla \cdot(\vec\nabla \times \vec F) & = & 0\cdots\cdots旋度场必是无散场\\
\vec\nabla \cdot (\vec\nabla f) & = & {\vec\nabla}^2f\\
\vec\nabla \times(\vec\nabla \times \vec F) & = & \vec\nabla(\vec\nabla \cdot \vec F) - {\vec\nabla}^2 \vec F\\
\end{eqnarray}
$$
 
访问 [MathJax](http://meta.math.stackexchange.com/questions/5020/mathjax-basic-tutorial-and-quick-reference) 参考更多使用方法。

'''
     , "folderPath": "", "isFolder": False, "createdAt": int(time.time()*1000), "updatedAt": int(time.time()*1000), "parentId": 0},
    {"id": 2, "title": "工作", "content": "", "folderPath": "", "isFolder": True, "createdAt": int(time.time()*1000), "updatedAt": int(time.time()*1000), "parentId": 0},
    {"id": 3, "title": "学习笔记", "content": "# 学习笔记\n\n## Kotlin\n\n```kotlin\nfun main() {\n    println(\"Hello!\")\n}\n```\n\n## 表格\n\n| 名称 | 说明 |\n|------|------|\n| A | 第一行 |\n| B | 第二行 |", "folderPath": "", "isFolder": False, "createdAt": int(time.time()*1000), "updatedAt": int(time.time()*1000), "parentId": 2},
]
next_id = 4


@app.route("/")
def index():
    index_file = os.path.join(WEB_DIR, "index.html")
    if os.path.exists(index_file):
        return send_file(index_file, mimetype="text/html")
    return "<h1>Phonote Dev Server</h1><p>Place your index.html in tools/web/</p>", 200


@app.route("/dev/<path:filepath>")
def serve_dev_file(filepath):
    return send_from_directory(WEB_DIR, filepath)


@app.route("/dev")
def dev_info():
    return jsonify({"path": WEB_DIR, "files": os.listdir(WEB_DIR) if os.path.exists(WEB_DIR) else []})


@app.route("/admin")
def admin():
    return "<h1>Admin</h1><p>Use the Android app's /admin page for template editing.</p>"


# ── Notes API ──────────────────────────────────────────────

@app.route("/api/notes", methods=["GET"])
def list_notes():
    folder_id = request.args.get("folderId", 0, type=int)
    folders = [n for n in notes_db if n["isFolder"] and n["parentId"] == folder_id]
    notes = [n for n in notes_db if not n["isFolder"] and n["parentId"] == folder_id]
    notes.sort(key=lambda x: x["updatedAt"], reverse=True)
    folders.sort(key=lambda x: x["title"])
    return jsonify({"folders": folders, "notes": notes})


@app.route("/api/notes/search", methods=["GET"])
def search_notes():
    q = request.args.get("q", "").lower()
    if not q:
        return jsonify({"notes": []})
    results = [n for n in notes_db if not n["isFolder"] and (q in n["title"].lower() or q in n["content"].lower())]
    return jsonify({"notes": results})


@app.route("/api/notes/<int:note_id>", methods=["GET"])
def get_note(note_id):
    for n in notes_db:
        if n["id"] == note_id:
            return jsonify(n)
    return jsonify({"error": "not found"}), 404


@app.route("/api/notes", methods=["POST"])
def create_note():
    global next_id
    data = request.get_json()
    note = {
        "id": next_id,
        "title": data.get("title", "新笔记"),
        "content": data.get("content", ""),
        "folderPath": "",
        "isFolder": data.get("isFolder", False),
        "createdAt": int(time.time() * 1000),
        "updatedAt": int(time.time() * 1000),
        "parentId": data.get("folderId", 0),
    }
    next_id += 1
    notes_db.append(note)
    return jsonify({"id": note["id"], "title": note["title"]})


@app.route("/api/notes/<int:note_id>", methods=["PUT"])
def update_note(note_id):
    for n in notes_db:
        if n["id"] == note_id:
            data = request.get_json()
            if "title" in data:
                n["title"] = data["title"]
            if "content" in data:
                n["content"] = data["content"]
            n["updatedAt"] = int(time.time() * 1000)
            return jsonify({"ok": True})
    return jsonify({"error": "not found"}), 404


@app.route("/api/notes/<int:note_id>", methods=["DELETE"])
def delete_note(note_id):
    global notes_db
    notes_db = [n for n in notes_db if n["id"] != note_id and n["parentId"] != note_id]
    return jsonify({"ok": True})


# ── Static files fallback ──────────────────────────────────

@app.route("/<path:filepath>")
def serve_static(filepath):
    if os.path.exists(WEB_DIR) and os.path.isfile(os.path.join(WEB_DIR, filepath)):
        return send_from_directory(WEB_DIR, filepath)
    return "Not found", 404


if __name__ == "__main__":
    os.makedirs(WEB_DIR, exist_ok=True)
    print(f"\n  Phonote Dev Server")
    print(f"  ==================")
    print(f"  Open:  http://localhost:8080")
    print(f"  Files: {WEB_DIR}\n")
    app.run(host="0.0.0.0", port=8080, debug=True)
