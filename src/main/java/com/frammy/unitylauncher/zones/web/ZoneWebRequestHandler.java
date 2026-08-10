package com.frammy.unitylauncher.zones.web;

public interface ZoneWebRequestHandler {
    record Result(boolean success, String message, String markerId) {
        public static Result ok(String markerId) { return new Result(true, null, markerId); }
        public static Result error(String msg) { return new Result(false, msg, null); }
    }

    /** Вызывается ГАРАНТИРОВАННО в главном потоке сервера. */
    Result handle(ZoneWebRequestService.ZoneWebRequest request);
}