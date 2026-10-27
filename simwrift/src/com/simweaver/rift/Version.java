package com.simweaver.rift;

public class Version {
    public static final int VERSION_MAJOR = 0;
    public static final int VERSION_MINOR = 9;
    public static final int VERSION_REVISION = 22;
    public static final String VERSION_TAG = "";
    public static final String PRODUCT_NAME = "SimWeaver Junction & Rift Server";
    public static final String LICENSE_TAG = "This application is open source software licensed under the\nBSD 3-Clause License\nCopyright(c) 2026 Simweaver AB";

    public static String getVersion(){
        StringBuffer sb = new StringBuffer();
        sb.append(VERSION_MAJOR).append(".").append(VERSION_MINOR).append(".").append(VERSION_REVISION);
        if (!VERSION_TAG.isEmpty()){
            sb.append("-").append(VERSION_TAG);
        }
        return sb.toString();
    }
    
}
