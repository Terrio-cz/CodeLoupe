package com.example.model;

public interface Store<T> {
    void save(T item);

    T find(String id);
}
