package com.simweaver.common.rest;

public final class XrResultPair {

    private final String text;
    private final int value;

    public XrResultPair(String text, int value) {
        this.text = text;
        this.value = value;
    }

    public String getText() { return text; }
    public int getValue() { return value; }
}



