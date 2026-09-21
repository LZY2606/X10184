package com.regmold.sim;

public class SimOperation {
    public String type;
    public String register;
    public Long value;
    public Integer word;

    public SimOperation() {
    }

    public SimOperation(String type, String register, Long value, Integer word) {
        this.type = type;
        this.register = register;
        this.value = value;
        this.word = word;
    }
}
