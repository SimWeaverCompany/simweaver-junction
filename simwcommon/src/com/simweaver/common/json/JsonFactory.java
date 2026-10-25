package com.simweaver.common.json;

public class JsonFactory {
    public static String rfc9457(String type, String title, int status, String detail, String instance) {
        return Json.object().put("type",title).put("title",title).put("status",new Integer(status))
                .put("detail",detail).put("instance",instance).toString();
    }
}
