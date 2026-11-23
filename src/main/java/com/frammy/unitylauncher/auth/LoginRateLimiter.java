package com.frammy.unitylauncher.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Простой анти-брутфорс для логина по ключу (обычно uuid+ip). */
public final class LoginRateLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MS = 20_000; // 20 секунд бана после превышения

    private static final class State {
        int fails = 0;
        long lockUntil = 0L;
    }

    private final Map<String, State> map = new ConcurrentHashMap<>();

    /** @return 0 если можно, иначе — оставшиеся секунды блокировки (округлённые вверх). */
    public long checkAllowed(String key) {
        State st = map.get(key);
        long now = System.currentTimeMillis();
        if (st == null) return 0;
        if (st.lockUntil > now) {
            long ms = st.lockUntil - now;
            return (ms + 999) / 1000; // ceil
        }
        return 0;
    }

    /** Фиксируем неудачу. Если превысили порог — вешаем лок. */
    public void registerFailure(String key) {
        State st = map.computeIfAbsent(key, k -> new State());
        long now = System.currentTimeMillis();
        if (st.lockUntil > now) return; // уже в бане — оставляем как есть
        st.fails++;
        if (st.fails >= MAX_ATTEMPTS) {
            st.lockUntil = now + LOCK_MS;
            st.fails = 0; // обнулим накопленные попытки после выдачи бана
        }
    }

    /** Успешный вход — сбрасываем. */
    public void reset(String key) {
        map.remove(key);
    }
}
