"""转写客户端（极简自用版）：读取本机转写历史，供查看与一键复制。

分层：
- 纯函数 seam：parse_history / read_latest（可无显示环境单测）。
- GUI：tkinter 窗口（本文件后半），仅在 __main__ 里启动。

数据来源：server/history.txt（每行 "[时间] 文字"，最新在末尾）与 server/latest.txt。
不承担录音、不承担转写；不修改服务端。
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.join(os.path.dirname(HERE), "server")
HISTORY_TXT = os.path.join(SERVER_DIR, "history.txt")
LATEST_TXT = os.path.join(SERVER_DIR, "latest.txt")

# [2026-11-24 09:19:05] 正文
_LINE_RE = re.compile(r"^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\]\s?(.*)$")


def parse_history(path: str) -> list[dict]:
    """解析 history.txt，返回 [{time, text}]，最新在前。

    每行格式 "[YYYY-MM-DD HH:MM:SS] 正文"。空行、无时间戳前缀的行跳过。
    文件不存在返回 []。
    """
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw_lines = f.read().splitlines()
    except OSError:
        return []

    items = []
    for line in raw_lines:
        if not line.strip():
            continue
        m = _LINE_RE.match(line)
        if not m:
            continue
        items.append({"time": m.group(1), "text": m.group(2)})
    items.reverse()  # 文件末尾最新 -> 反转成最新在前
    return items


def read_latest(path: str) -> str:
    """读取 latest.txt 的当前文本（去尾部换行）；文件不存在返回空串。"""
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def copy_to_clipboard(text: str) -> bool:
    """把文字写入 Windows 剪贴板。优先 tkinter，退回 PowerShell Set-Clipboard。"""
    if not text:
        return False
    try:
        import tkinter
    except ImportError:
        tkinter = None
    if tkinter is not None:
        try:
            root = tkinter.Tk()
            root.withdraw()
            root.clipboard_clear()
            root.clipboard_append(text)
            root.update()
            root.destroy()
            return True
        except tkinter.TclError:
            pass  # 无显示/剪贴板不可用，走 PowerShell 兜底
    if sys.platform.startswith("win"):
        try:
            import base64
            b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
            ps = ("$t=[Text.Encoding]::UTF8.GetString("
                  "[Convert]::FromBase64String('%s'));Set-Clipboard -Value $t" % b64)
            r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                               check=False, timeout=10,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return r.returncode == 0
        except (OSError, subprocess.SubprocessError):
            return False
    return False


POLL_MS = 2000
SUMMARY_MAX = 60
EMPTY_HINT = "暂无转写历史"


def _list_label(text: str) -> str:
    """列表行用的单行标签：压平换行，超长截断加省略号。"""
    flat = text.replace("\n", " ")
    return flat if len(flat) <= SUMMARY_MAX else flat[:SUMMARY_MAX] + "…"


def run_gui() -> None:
    import tkinter as tk

    root = tk.Tk()
    root.title("转写客户端")
    root.geometry("520x480")

    status = tk.StringVar(value="每 %d 秒自动刷新" % (POLL_MS // 1000))
    state: dict = {"history": []}  # 当前列表对应的条目，索引与 Listbox 行一一对应

    top = tk.Frame(root)
    top.pack(fill="x", padx=8, pady=(8, 4))
    tk.Label(top, text="转写历史（最新在上，单击即复制）", anchor="w").pack(side="left")
    tk.Label(top, textvariable=status, anchor="e", fg="#c47f00").pack(side="right")

    # 最新一条（读取 latest.txt，供快速回看最近一次结果）
    latest_var = tk.StringVar(value="")
    latest_frame = tk.Frame(root)
    latest_frame.pack(fill="x", padx=8, pady=(0, 4))
    tk.Label(latest_frame, text="最新：", anchor="w").pack(side="left")
    tk.Label(latest_frame, textvariable=latest_var, anchor="w", fg="#2d6cdf",
             wraplength=440, justify="left").pack(side="left", fill="x", expand=True)

    # 列表
    list_frame = tk.Frame(root)
    list_frame.pack(fill="both", expand=True, padx=8)
    scroll = tk.Scrollbar(list_frame)
    scroll.pack(side="right", fill="y")
    lb = tk.Listbox(list_frame, yscrollcommand=scroll.set, activestyle="dotbox",
                    exportselection=False)
    lb.pack(side="left", fill="both", expand=True)
    scroll.config(command=lb.yview)

    # 详情框（全文，可手动选中复制）
    tk.Label(root, text="详情（全文，可手动选中复制）", anchor="w").pack(fill="x", padx=8, pady=(8, 0))
    detail = tk.Text(root, height=8, wrap="word")
    detail.pack(fill="x", padx=8, pady=(0, 8))

    def show_detail(item: dict | None) -> None:
        detail.config(state="normal")
        detail.delete("1.0", "end")
        detail.insert("1.0", item["text"] if item else "")
        detail.config(state="disabled")

    def refresh() -> None:
        latest_var.set(read_latest(LATEST_TXT))
        history = parse_history(HISTORY_TXT)
        # 仅在内容变化时重建，避免频繁刷新打断点击
        incoming = [(h["time"], h["text"]) for h in history]
        current = [(h["time"], h["text"]) for h in state["history"]]
        if current != incoming:
            lb.delete(0, "end")
            state["history"] = history
            if not history:
                lb.insert("end", EMPTY_HINT)
            else:
                for h in history:
                    lb.insert("end", "%s  %s" % (h["time"], _list_label(h["text"])))
        root.after(POLL_MS, refresh)

    def copy_selected() -> None:
        sel = lb.curselection()
        if not sel:
            return
        idx = sel[0]
        history = state["history"]
        if not history or idx >= len(history):
            return  # 空态占位行
        item = history[idx]
        show_detail(item)
        ok = copy_to_clipboard(item["text"])
        status.set("已复制" if ok else "复制失败，请手动选中详情复制")

    # 用 Button-1（而非仅 <<ListboxSelect>>）：同一条目再点也能再次复制
    lb.bind("<Button-1>", lambda _e: root.after(10, copy_selected))

    refresh()
    root.mainloop()


if __name__ == "__main__":
    run_gui()
