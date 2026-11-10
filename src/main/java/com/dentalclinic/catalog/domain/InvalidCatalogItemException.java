package com.dentalclinic.catalog.domain;

public class InvalidCatalogItemException extends RuntimeException {
    public InvalidCatalogItemException(String message) {
        super(message);
    }
}
