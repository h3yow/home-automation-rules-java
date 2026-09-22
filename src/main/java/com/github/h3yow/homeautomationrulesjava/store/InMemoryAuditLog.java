package com.github.h3yow.homeautomationrulesjava.store;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存审计日志：顺序追加、单调序号，提供事件与触发两个维度的可追溯记录。
 * 全部在本地内存，重启即清空（本地模拟用途）。
 */
@SuppressWarnings("unused")
@Component
public class InMemoryAuditLog implements AuditLog {

    private final List<EventRecord> eventRecords = new CopyOnWriteArrayList<>();
    private final List<TriggerRecord> triggerRecords = new CopyOnWriteArrayList<>();
    private final AtomicLong eventSeq = new AtomicLong();
    private final AtomicLong triggerSeq = new AtomicLong();

    @Override
    public void appendEvent(EventRecord record) {
        eventRecords.add(record);
    }

    @Override
    public void appendTrigger(TriggerRecord record) {
        triggerRecords.add(record);
    }

    @Override
    public List<EventRecord> events() {
        return List.copyOf(eventRecords);
    }

    @Override
    public List<TriggerRecord> triggers() {
        return List.copyOf(triggerRecords);
    }

    public long nextEventSeq() {
        return eventSeq.incrementAndGet();
    }

    public long nextTriggerSeq() {
        return triggerSeq.incrementAndGet();
    }

    /** 测试辅助。 */
    public void clear() {
        eventRecords.clear();
        triggerRecords.clear();
        eventSeq.set(0);
        triggerSeq.set(0);
    }
}
