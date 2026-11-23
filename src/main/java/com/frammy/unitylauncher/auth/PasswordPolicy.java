package com.frammy.unitylauncher.auth;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    // Настройки — можешь подправить
    public static final int MIN_LEN = 7;
    public static final int MAX_LEN = 128;
    public static final int MAX_BYTES = 512; // safety на PBKDF2

    /**
     * @return null если пароль валиден, иначе короткое сообщение об ошибке (для игрока).
     */
    public static String validate(String password) {
        if (password == null) return "Пароль обязателен.";
        // Длина символов
        int len = password.length();
        if (len < MIN_LEN) return "Слишком короткий пароль (мин. " + MIN_LEN + ").";
        if (len > MAX_LEN) return "Слишком длинный пароль (макс. " + MAX_LEN + ").";

        // Запрещаем пробелы и управляющие символы
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isWhitespace(c)) {
                return "Пароль не должен содержать пробелы/переводы строки.";
            }
            if (Character.isISOControl(c)) {
                return "Пароль содержит недопустимые управляющие символы.";
            }
        }

        // Ограничим байты в UTF-8 (для защиты CPU на хэшировании)
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "Пароль слишком объёмный.";
        }

        // Юникод допустим, нормализации/тримминга специально нет
        return null; // OK
    }
}
