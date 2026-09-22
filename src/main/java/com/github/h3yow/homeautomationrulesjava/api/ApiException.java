package com.github.h3yow.homeautomationrulesjava.api;

/** 携带可区分错误码的 API 异常。 */
public class ApiException extends RuntimeException {

    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
