package com.shreyasnandurkar.botvinnikapi.security.ssrf;

public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String address) {
        super("Connection to '" + address + "' is blocked: the address is in a private, "
                + "loopback, link-local or otherwise restricted range.");
    }
}
