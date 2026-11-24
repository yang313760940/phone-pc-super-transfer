"""转写客户端（#06）的纯函数单测：解析转写历史与最新结果。

只测两个 seam：parse_history / read_latest。GUI 不在此测（自用，手动验）。
运行：python -m unittest client.test_app -v    （在仓库根目录）
"""
import os
import sys
import tempfile
import unittest

# 允许从仓库根 import client.app
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from client.app import parse_history, read_latest  # noqa: E402


class TempFileWriteMixin:
    """两个测试类共用：写一个临时文件并在用例结束后清理。"""

    def _write(self, content):
        fd, path = tempfile.mkstemp(suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return path


class ParseHistoryTest(TempFileWriteMixin, unittest.TestCase):
    def _write(self, content):
        fd, path = tempfile.mkstemp(suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return path

    def test_parses_lines_into_time_and_text(self):
        # 独立期望：已知输入 -> 已知输出（不是用代码重算）
        path = self._write(
            "[2026-11-24 09:19:05] 第一句\n"
            "[2026-11-24 09:19:29] 第二句\n"
        )
        result = parse_history(path)
        self.assertEqual(
            result,
            [
                {"time": "2026-11-24 09:19:29", "text": "第二句"},
                {"time": "2026-11-24 09:19:05", "text": "第一句"},
            ],
        )

    def test_newest_first(self):
        path = self._write(
            "[2026-11-24 10:00:00] 早的\n"
            "[2026-11-24 11:00:00] 晚的\n"
        )
        result = parse_history(path)
        self.assertEqual(result[0]["text"], "晚的")
        self.assertEqual(result[-1]["text"], "早的")

    def test_missing_file_returns_empty_list(self):
        self.assertEqual(parse_history("/no/such/history.txt"), [])

    def test_text_with_brackets_preserved(self):
        # 正文里带方括号不能被时间戳解析吞掉
        path = self._write("[2026-11-24 09:19:05] 看 [备注] 这句\n")
        result = parse_history(path)
        self.assertEqual(result[0]["text"], "看 [备注] 这句")

    def test_blank_lines_skipped(self):
        path = self._write("\n[2026-11-24 09:19:05] 有效\n\n")
        result = parse_history(path)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["text"], "有效")


class ReadLatestTest(TempFileWriteMixin, unittest.TestCase):
    def test_reads_plain_text(self):
        path = self._write("最新一条转写")
        self.assertEqual(read_latest(path), "最新一条转写")

    def test_strips_trailing_newline(self):
        path = self._write("最新一条转写\n")
        self.assertEqual(read_latest(path), "最新一条转写")

    def test_missing_file_returns_empty(self):
        self.assertEqual(read_latest("/no/such/latest.txt"), "")


if __name__ == "__main__":
    unittest.main()
