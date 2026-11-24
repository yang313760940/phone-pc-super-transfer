"""生成 VoiceSend 安卓图标（A 方案：红底白麦+波形）。
- 自适应图标前景层：透明底，白色麦+波形，绘制在 108dp 图的中间安全区（图形约占 66dp）。
- 传统兜底图标：红色圆角方块底 + 白色麦+波形。
输出到 res/mipmap-<density>/ 对应文件名；同时导出 512 预览图。
"""
import os
from PIL import Image, ImageDraw

RED = (220, 38, 38, 255)      # #DC2626
WHITE = (255, 255, 255, 255)

BASE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BASE, "app", "src", "main", "res")

# density -> (foreground px[108dp], legacy px[48dp])
DENS = {
    "mdpi": (108, 48),
    "hdpi": (162, 72),
    "xhdpi": (216, 96),
    "xxhdpi": (324, 144),
    "xxxhdpi": (432, 192),
}

SS = 8  # 超采样倍数（抗锯齿）


def draw_glyph(dr, cx, cy, unit, color):
    """以 (cx,cy) 为像素中心，unit = 每"视图单位"的像素。视图坐标原点在(54,54)中心。"""
    w = color
    def px(v):   # 视图 x/y -> 像素（相对中心 54）
        return (v - 54) * unit
    def R(vx, vy, vw, vh, vr):
        x0 = cx + px(vx); y0 = cy + px(vy)
        dr.rounded_rectangle([x0, y0, x0 + vw * unit, y0 + vh * unit],
                             radius=vr * unit, fill=w)

    # 麦克风头（胶囊）
    R(48, 30, 12, 22, 6)

    # U 形支架：左右竖线 + 正下方半圆弧（以 x=54 为轴）
    lw = max(2, int(round(2.4 * unit)))
    r = 14                       # 支架半径（视图单位）
    cy_arc = 52                  # 半圆圆心 y（视图）
    top = 42                     # 竖线顶端 y（视图）
    x_left_px = cx + px(54 - r)
    x_right_px = cx + px(54 + r)
    y_top_px = cy + px(top)
    y_arc_px = cy + px(cy_arc)
    dr.line([x_left_px, y_top_px, x_left_px, y_arc_px], fill=w, width=lw)
    dr.line([x_right_px, y_top_px, x_right_px, y_arc_px], fill=w, width=lw)
    # 下半圆（0..180度）
    rp = r * unit
    cxp = cx + px(54); cyp = cy + px(cy_arc)
    dr.arc([cxp - rp, cyp - rp, cxp + rp, cyp + rp], start=0, end=180, fill=w, width=lw)

    # 麦克风杆（从麦克风头底部到 U 弧之上）
    R(52, 58, 4, 8, 2)

    # 底部波形（5 根）
    heights = [5, 9, 13, 9, 5]
    xs = [42, 48, 54, 60, 66]
    for x, h in zip(xs, heights):
        R(x - 1.7, 82 - h, 3.4, h, 1.7)


def render(size_px, glyph_frac, bg=None, with_bg_round=False):
    """渲染一张 size_px 图。glyph_frac=图形占图宽比例。bg None=透明。"""
    big = size_px * SS
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    if bg is not None:
        if with_bg_round:
            r = int(big * 0.22)
            dr.rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=bg)
        else:
            dr.rectangle([0, 0, big - 1, big - 1], fill=bg)
    # 图形：108 单位视图里，麦+波形大致占 x[36..72] y[26..86]（约 46x60）
    # glyph_frac 控制整体缩放：unit = big * glyph_frac / 108
    unit = big * glyph_frac / 108.0
    draw_glyph(dr, big / 2, big / 2, unit, WHITE)
    return im.resize((size_px, size_px), Image.LANCZOS)


def main():
    for dens, (fg_px, legacy_px) in DENS.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        # 自适应前景：图形占 ~66/108（安全区内留边）
        fg = render(fg_px, glyph_frac=0.66)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        # 传统兜底：红圆角底 + 图形占 ~72%
        legacy = render(legacy_px, glyph_frac=0.72, bg=RED, with_bg_round=True)
        legacy.save(os.path.join(d, "ic_launcher.png"))
        print("wrote", dens)

    # 512 预览（模拟：红圆角底 + 白标，展示实际启动器观感）
    preview = render(512, glyph_frac=0.72, bg=RED, with_bg_round=True)
    preview.save(os.path.join(BASE, "preview_final.png"))
    # 自适应合成预览：红底圆 + 前景
    print("done")


if __name__ == "__main__":
    main()
