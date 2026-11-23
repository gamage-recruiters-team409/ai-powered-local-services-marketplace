package com.gamagerecruiters.marketplace.modules.notification.service;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class NotificationDataSanitizerTest {
    private final NotificationDataSanitizer sanitizer = new NotificationDataSanitizer();
    @Test void dropsForbiddenAndInvalidKeysAndNullValues() { Map<String,String> data = new LinkedHashMap<>(); for (String key : List.of("token","resetLink","password","address","email","phone","apiKey","not-valid!")) data.put(key,"x"); data.put("district",null); assertTrue(sanitizer.sanitize(data, key -> {}).isEmpty()); }
    @Test void trimsLongValuesAndLimitsEntries() { Map<String,String> data = new LinkedHashMap<>(); for (int i=0;i<12;i++) data.put("key".replace("key", "field") + i, " "+"x".repeat(201)); Map<String,String> result=sanitizer.sanitize(data,key->{}); assertEquals(10,result.size()); assertEquals(200,result.get("field0").length()); }
    @Test void handlesNullMap() { assertTrue(sanitizer.sanitize(null,key->{}).isEmpty()); }
}
