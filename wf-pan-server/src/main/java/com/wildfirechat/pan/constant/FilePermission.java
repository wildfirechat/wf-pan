package com.wildfirechat.pan.constant;

/**
 * 用户对某个文件的实际权限（空间规则、单独分享、群分享三者取最大）
 * 顺序即大小，比较用 ordinal
 */
public enum FilePermission {
    NONE,   // 无权访问
    VIEW,   // 可查看、下载
    EDIT;   // 可编辑（在线编辑保存为新版本）

    public boolean atLeast(FilePermission other) {
        return this.ordinal() >= other.ordinal();
    }

    public static FilePermission max(FilePermission a, FilePermission b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
