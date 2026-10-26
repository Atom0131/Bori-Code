package com.bori.test.locallib;

/** Source of app/libs/greeter.jar (javac --release 8). */
public final class Greeter {
    private Greeter() {}

    public static String greet(String name) {
        return "Hola, " + name;
    }
}
