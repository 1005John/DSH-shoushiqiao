package com.dsh.bridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 用真实触摸注入点击指定控件。
 *
 * 实机踩出来的五个坑：
 *  1. Flutter 不响应 ACTION_CLICK → 用 dispatchGesture 注入真实触摸
 *  2. App 刚启动时 getRootInActiveWindow() 可能返回旧窗口 → 校验 package + 重试
 *  3. 树里有大量隐藏节点（bounds 高度为 0）→ isVisibleToUser + 高度过滤
 *  4. 同名节点有多个 → 取屏幕最下方的那个
 *  5. 冷启动时 Flutter 语义树还没铺开，按钮描述会被合并进父容器
 *     （比如 [48,0][1108,2055] 这种整块区域）
 *     → **只接受按钮尺寸的节点，否则继续等**，绝不拿大容器凑数
 */
public class ClickA11yService extends AccessibilityService {

    private static final String TAG = "DSHBridge";
    private static ClickA11yService instance;

    // 按钮尺寸范围（实测：语音输入按钮 108x108，发送键约 135x135）
    private static final int MIN_H = 40, MAX_H = 360;
    private static final int MIN_W = 40, MAX_W = 520;

    public static boolean isRunning() { return instance != null; }

    /**
     * 主动锁屏（发完消息后自动息屏用）。
     * 走无障碍的 GLOBAL_ACTION_LOCK_SCREEN，不需要设备管理权限。
     * 锁屏只是熄灭屏幕，正在播放的 TTS 音频不会中断。
     */
    public static boolean lockScreen() {
        ClickA11yService s = instance;
        if (s == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                boolean ok = s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                BridgeService.log("锁屏动作=" + ok);
                return ok;
            }
        } catch (Throwable t) {
            BridgeService.log("锁屏失败：" + t);
        }
        return false;
    }

    public static ClickA11yService get() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    public boolean tapAt(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 120);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            boolean ok = dispatchGesture(gd, null, null);
            BridgeService.log("手势点击 (" + x + "," + y + ") 提交=" + ok);
            return ok;
        } catch (Exception e) {
            BridgeService.log("手势点击异常：" + e);
            return false;
        }
    }

    /**
     * 找指定包里的按钮并点击。
     * 只接受"按钮尺寸"的可见节点，找不到就继续等（最长 6 秒）。
     */
    public boolean clickInPackage(String pkg, String... descs) {
        for (int attempt = 1; attempt <= 30; attempt++) {
            AccessibilityNodeInfo root = getTargetRoot(pkg);
            if (root != null) {
                for (String d : descs) {
                    AccessibilityNodeInfo best = findButton(root, d);
                    if (best != null) {
                        Rect r = new Rect();
                        best.getBoundsInScreen(r);
                        BridgeService.log("选中 '" + d + "' " + r.toShortString()
                                + "（第" + attempt + "次）");
                        if (tapAt(r.centerX(), r.centerY())) return true;
                    }
                }
            } else if (attempt == 1 || attempt % 10 == 0) {
                String cur = (root == null) ? "null" : String.valueOf(root.getPackageName());
                BridgeService.log("窗口还不是目标（当前=" + cur + "）第" + attempt + "次");
            }
            sleep(200);
        }
        BridgeService.log("超时未找到按钮：" + android.text.TextUtils.join("/", descs));
        return false;
    }

    public boolean isForeground(String pkg) {
        return getTargetRoot(pkg) != null;
    }

    /**
     * 拿到目标包的窗口根节点。
     *
     * ⚠️ 这里**绝对不能调用 getWindows()**：
     * 无障碍连接处于半死状态时它是 binder 调用，会一直阻塞到超时，
     * 实测卡住 29 秒，表现为"点了输入框就没下文"。
     *
     * 改为：如果前台是输入法窗口，就用 BACK 收起键盘，
     * 让 DSH Go 重新成为活动窗口。
     */
    private AccessibilityNodeInfo getTargetRoot(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (matches(root, pkg)) return root;

        String cur = root == null ? "null" : String.valueOf(root.getPackageName());
        if (isIme(cur)) {
            BridgeService.log("前台是输入法（" + cur + "），收起键盘");
            try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable ignored) { }
            sleep(350);
            root = getRootInActiveWindow();
            if (matches(root, pkg)) return root;
        }
        return null;
    }

    private boolean matches(AccessibilityNodeInfo root, String pkg) {
        return root != null && pkg.contentEquals(String.valueOf(root.getPackageName()));
    }

    private boolean isIme(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("inputmethod") || p.contains("sogou")
                || p.contains("baidu") || p.contains("iflytek")
                || p.contains("gboard") || p.contains(".ime");
    }

    /** 等目标应用成为前台，最多 timeoutMs（按真实耗时算，别用迭代次数） */
    public boolean waitFor(String pkg, long timeoutMs) {
        long start = System.currentTimeMillis();
        String last = "";
        while (System.currentTimeMillis() - start < timeoutMs) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            String cur = (r == null) ? "null" : String.valueOf(r.getPackageName());
            if (pkg.equals(cur)) return true;
            if (!cur.equals(last)) {
                BridgeService.log("等待中，当前前台=" + cur);
                last = cur;
            }
            sleep(200);
        }
        BridgeService.log("等待超时，最后前台=" + last);
        return false;
    }

    /** 键盘开着就收起来（避免输入法窗口挡住目标应用） */
    public void closeKeyboardIfIme(String pkg) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String cur = String.valueOf(root.getPackageName());
        if (isIme(cur)) {
            BridgeService.log("收起键盘（当前 " + cur + "）");
            try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable ignored) { }
            sleep(300);
        }
    }

    /** 目标窗口里是否有包含指定文字的元素（用于判断"正在生成"） */
    public boolean hasText(String pkg, String needle) {
        AccessibilityNodeInfo root = getTargetRoot(pkg);
        return root != null && containsText(root, needle, 0);
    }

    private boolean containsText(AccessibilityNodeInfo n, String needle, int depth) {
        if (n == null || depth > 50) return false;
        CharSequence cd = n.getContentDescription();
        CharSequence tx = n.getText();
        if ((cd != null && cd.toString().contains(needle))
                || (tx != null && tx.toString().contains(needle))) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            if (containsText(n.getChild(i), needle, depth + 1)) return true;
        }
        return false;
    }

    // 界面上这些是固定文案，不是回复内容
    private static final String[] CHROME = {
            "上下文已用", "查看用量", "本轮", "查看本轮轨迹", "自动允许命令",
            "模型：", "添加图片或文件", "语音输入", "停止生成", "发送",
            "复制", "分享", "从这里分叉", "重新回答", "更多", "对话列表",
            "新对话", "智能体团队", "已完成", "生成中", "工作区"
    };

    /** 抓取当前界面上所有文本块（用于发送前后做差集） */
    public List<String> textBlocks(String pkg) {
        AccessibilityNodeInfo root = getTargetRoot(pkg);
        List<String> out = new ArrayList<>();
        if (root != null) collectTexts(root, out, 0);
        return out;
    }

    /**
     * 从"新增的文本块"里挑出回复。
     * before 是发送前的快照，只有不在快照里的才是这次新产生的。
     */
    public String pickNewReply(String pkg, List<String> before, String exclude, int minLen) {
        List<String> after = textBlocks(pkg);
        String best = null;
        for (String raw : after) {
            String t = raw.trim();
            if (t.length() < minLen) continue;
            if (exclude != null && t.equals(exclude)) continue;
            if (before != null && before.contains(t)) continue;   // 旧内容，跳过
            boolean chrome = false;
            for (String c : CHROME) {
                if (t.startsWith(c) || t.equals(c)) { chrome = true; break; }
            }
            if (chrome) continue;
            // 去掉混在正文里的界面统计行
            t = t.replaceAll("本轮\\s*\\d+\\s*秒[^\\n]{0,60}查看本轮轨迹", " ").trim();
            if (t.length() < minLen) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    /** 取界面上最像"助手回复"的一段文字（兜底用） */
    public String lastTextBlock(String pkg, String exclude, int minLen) {
        AccessibilityNodeInfo root = getTargetRoot(pkg);
        if (root == null) return null;
        List<String> out = new ArrayList<>();
        collectTexts(root, out, 0);

        String best = null;
        for (String raw : out) {
            String t = raw.trim();
            if (t.length() < minLen) continue;
            if (exclude != null && t.equals(exclude)) continue;
            boolean chrome = false;
            for (String c : CHROME) {
                if (t.startsWith(c) || t.equals(c)) { chrome = true; break; }
            }
            if (chrome) continue;
            // 去掉混在正文里的界面统计行
            t = t.replaceAll("本轮\\s*\\d+\\s*秒[^\\n]{0,60}查看本轮轨迹", " ").trim();
            if (t.length() < minLen) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    private void collectTexts(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 50) return;
        CharSequence tx = n.getText();
        CharSequence cd = n.getContentDescription();
        if (tx != null && tx.length() > 0) out.add(tx.toString());
        else if (cd != null && cd.length() > 0) out.add(cd.toString());
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), out, depth + 1);
    }

    /** 真实点一下输入框，唤起键盘并让 App 进入编辑态 */
    public boolean tapEditable(String pkg) {
        AccessibilityNodeInfo root = getTargetRoot(pkg);
        if (root == null) return false;
        AccessibilityNodeInfo edit = findEditable(root, 0);
        if (edit == null) {
            BridgeService.log("点输入框失败：没找到可编辑节点");
            return false;
        }
        Rect r = new Rect();
        edit.getBoundsInScreen(r);
        if (r.width() == 0 || r.height() == 0) return false;
        return tapAt(r.centerX(), r.centerY());
    }

    /** 直接把文字写进目标 App 的输入框（不经过键盘） */
    public boolean setText(String pkg, String text) {
        AccessibilityNodeInfo root = getTargetRoot(pkg);
        if (root == null) {
            BridgeService.log("写入失败：找不到目标窗口");
            return false;
        }
        AccessibilityNodeInfo edit = findEditable(root, 0);
        if (edit == null) {
            BridgeService.log("写入失败：没找到可编辑输入框");
            return false;
        }
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        return ok;
    }

    /** 找最大的那个可编辑节点（输入框） */
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 50) return null;
        CharSequence cls = node.getClassName();
        boolean isEdit = (cls != null && cls.toString().contains("EditText"))
                || node.isEditable();
        if (isEdit) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.height() > 0 && node.isVisibleToUser()) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = findEditable(node.getChild(i), depth + 1);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * 只返回"输入栏按钮"尺寸的可见节点里最靠下的那个。
     *
     * 输入栏的语音/停止/发送按钮固定在屏幕右侧（x ≈ 0.9 屏宽），
     * 而聊天内容里的卡片是居中的（x ≈ 0.5）。
     * 用 x 位置区分，可以避免误点到聊天里的文件卡片 / 重新回答等。
     * 这个判据也不受软键盘影响（键盘只会改变 y，不改变 x）。
     */
    private AccessibilityNodeInfo findButton(AccessibilityNodeInfo root, String desc) {
        List<AccessibilityNodeInfo> hits = new ArrayList<>();
        collect(root, desc, 0, hits);
        if (hits.isEmpty()) return null;

        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        float rightLimit = screen.width() * 0.78f;

        AccessibilityNodeInfo best = null;
        int bestY = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : hits) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() < MIN_H || r.height() > MAX_H) continue;
            if (r.width() < MIN_W || r.width() > MAX_W) continue;
            if (!n.isVisibleToUser()) continue;
            if (r.centerX() < rightLimit) {
                BridgeService.log("'" + desc + "' 命中但不在右侧（x=" + r.centerX()
                        + " < " + (int) rightLimit + "），跳过");
                continue;
            }
            if (r.centerY() > bestY) { bestY = r.centerY(); best = n; }
        }
        return best;
    }

    private void collect(AccessibilityNodeInfo node, String desc, int depth,
                         List<AccessibilityNodeInfo> out) {
        if (node == null || depth > 50) return;
        CharSequence cd = node.getContentDescription();
        CharSequence tx = node.getText();
        if ((cd != null && cd.toString().contains(desc))
                || (tx != null && tx.toString().contains(desc))) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), desc, depth + 1, out);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
