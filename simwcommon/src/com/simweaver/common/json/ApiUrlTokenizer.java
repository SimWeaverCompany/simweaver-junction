package com.simweaver.common.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class ApiUrlTokenizer {

    private List<String> tokens;

    public ApiUrlTokenizer(String url) {
        tokens = new ArrayList<>();
        String[] mainParts = url.split(Pattern.quote("?"));

        if (mainParts.length > 0){
            String urlPart = mainParts[0];
            tokens = new ArrayList<>();
            for (String s : urlPart.split("/")) {
                if (!s.isBlank()){
                    tokens.add(s);
                }
            }

        }
    }

    public boolean hasToken(int index){
        return (tokens.size() > index);
    }

    public String getToken(int index){
        if (hasToken(index)){
            return tokens.get(index);
        }
        else{
            return "";
        }
    }

    public boolean tokenEquals(int index,String search){
        return tokenEquals(index,search,true);
    }

    public boolean tokenEquals(int index,String search,boolean ignoreCase){
        if (ignoreCase){
            return getToken(index).equalsIgnoreCase(search);
        }
        else{
            return getToken(index).equals(search);
        }
    }

    public String dump(){
        String dump = "";
        int index = 0;
        for (String token : tokens) {
            dump += "Token " + index++  + " " + token + "\n";
        }
        return dump;
    }
}
