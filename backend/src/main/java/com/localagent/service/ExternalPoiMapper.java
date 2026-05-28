package com.localagent.service;

import com.localagent.model.Poi;
import com.localagent.model.PoiType;
import java.util.Locale;
import java.util.Map;

final class ExternalPoiMapper {
    private ExternalPoiMapper() {
    }

    static Poi fromAmap(Map<?, ?> raw, PoiType requestedType) {
        String name = stringValue(raw.get("name"), "未命名地点");
        String address = stringValue(raw.get("address"), "");
        String typeName = stringValue(raw.get("type"), requestedType.name().toLowerCase(Locale.ROOT));
        double[] location = parseLocation(stringValue(raw.get("location"), "0,0"));
        PoiType type = requestedType == PoiType.EXTRA ? PoiType.EXTRA : requestedType;
        boolean dining = type == PoiType.DINING;
        boolean culture = type == PoiType.CULTURE;
        boolean kidFriendly = containsAny(name + typeName, "\u513f\u7ae5", "\u4eb2\u5b50", "\u516c\u56ed", "\u79d1\u6280", "\u5267\u573a");
        boolean lowCalorie = containsAny(name + typeName, "\u8f7b\u98df", "\u5065\u5eb7", "\u7d20\u98df", "\u6c99\u62c9");
        boolean social = !kidFriendly || containsAny(typeName, "\u5c55\u89c8", "\u9152\u5427", "\u5496\u5561", "\u5c0f\u5403");
        return new Poi(
                name,
                type,
                typeName,
                address,
                location[0],
                location[1],
                dining ? 85 : culture ? 90 : 70,
                dining ? 110 : culture ? 70 : 45,
                4.5,
                kidFriendly,
                lowCalorie,
                true,
                social,
                false,
                false
        );
    }

    private static double[] parseLocation(String location) {
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return new double[] {0.0, 0.0};
        }
        try {
            return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (NumberFormatException e) {
            return new double[] {0.0, 0.0};
        }
    }

    private static boolean containsAny(String text, String... parts) {
        for (String part : parts) {
            if (text.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "[]".equals(text) ? fallback : text;
    }
}
