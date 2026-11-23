package com.frammy.unitylauncher.zones;

import java.util.Locale;
import java.util.Map;

/**
 * Единая нормализация имени страны в "каноническое" ASCII-имя — используется и как
 * имя LuckPerms-группы (см. UpgradeCondition.getCountryGroup), и как ключ сравнения
 * "это одна и та же страна?" по всему проекту (ZoneManager/ZoneValidationService/
 * ZoneQuotaService).
 * <p>
 * ВАЖНО: раньше тут была просто {@code replaceAll("[^a-z0-9_\\-.]", "")} — кириллица
 * не входит в этот набор символов и вырезалась ПОЛНОСТЬЮ, поэтому "Беларусь" превращалось
 * в пустую строку "". Из-за этого: (а) LuckPerms не находил группу страны — выдача прав
 * при покупке улучшений молча ломалась; (б) ЛЮБЫЕ две кириллические страны нормализовались
 * в одинаковую "" и ложно считались "той же страной" везде, где используется normCountry
 * для сравнения (потенциальная дыра в правах между разными странами). Транслитерация
 * кириллицы в латиницу до фильтрации решает оба случая разом.
 */
public final class CountryNameUtil {
    private CountryNameUtil() {}

    // посимвольная транслитерация — обычная (не паспортная) схема, этого достаточно
    // для стабильного и различимого ASCII-ключа, читаемость транслита не критична
    private static final Map<Character, String> CYR_TO_LAT = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "c"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sch"), Map.entry('ъ', ""),
            Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"),
            // украинские/белорусские варианты некоторых букв
            Map.entry('і', "i"), Map.entry('ї', "yi"), Map.entry('є', "ye"), Map.entry('ґ', "g"),
            Map.entry('ў', "w")
    );

    public static String transliterate(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = CYR_TO_LAT.get(Character.toLowerCase(c));
            sb.append(rep != null ? rep : c);
        }
        return sb.toString();
    }

    /** Каноническое имя страны = имя LP-группы / ключ сравнения "та же страна?". */
    public static String normalizeCountry(String raw) {
        if (raw == null) return null;
        String t = transliterate(raw.trim().toLowerCase(Locale.ROOT));
        t = t.replace(' ', '_');
        return t.replaceAll("[^a-z0-9_\\-.]", "");
    }
}
