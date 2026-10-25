package com.simweaver.common;

public class SimwUtils {

    public static void sleep(long millis){
        try{
            Thread.sleep(millis);
        }
        catch (InterruptedException e){

        }
    }
}
