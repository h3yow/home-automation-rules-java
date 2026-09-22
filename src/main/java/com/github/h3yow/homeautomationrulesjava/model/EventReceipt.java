package com.github.h3yow.homeautomationrulesjava.model;

/**
 * 事件受理回执。
 *
 * @param status 受理状态
 * @param reason 拒绝原因（仅 REJECTED 时非空）
 * @param stateVersion 受理后设备状态版本（若可用）
 */
public record EventReceipt(ReceiptStatus status, RejectReason reason, Long stateVersion) {
}
