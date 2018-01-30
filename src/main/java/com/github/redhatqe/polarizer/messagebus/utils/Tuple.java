package com.github.redhatqe.polarizer.messagebus.utils;

public class Tuple<T1, T2> {
    public T1 first;
    public T2 second;

    public Tuple(T1 f, T2 s) {
        this.first = f;
        this.second = s;
    }

    public Tuple() {

    }
}
