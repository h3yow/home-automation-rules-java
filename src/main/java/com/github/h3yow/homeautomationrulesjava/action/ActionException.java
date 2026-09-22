package com.github.h3yow.homeautomationrulesjava.action;

/** 动作执行异常，携带可区分失败类型。 */
public class ActionException extends RuntimeException {
    private final FailureKind kind;

    public ActionException(FailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public FailureKind kind() {
        return kind;
    }
}
