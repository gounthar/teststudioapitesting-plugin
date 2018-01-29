package org.jenkinsci.plugins;


public enum TestType {
    SINGLE_TEST("test"),
    TEST_LIST("list");

    private final String name;

    TestType(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return this.name;
    }
}
