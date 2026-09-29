package com.wildfirechat.pan.controller.admin;

import org.springframework.data.domain.PageRequest;

/**
 * 管理后台分页参数：限制单页大小，避免一次拉取过多数据
 */
final class AdminPaging {

    private static final int MAX_PAGE_SIZE = 200;

    private AdminPaging() {
    }

    static PageRequest of(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
