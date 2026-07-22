package com.shreyasnandurkar.botvinnikapi.security.ssrf;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record Cidr(byte[] network, int prefixBits) {

    public static Cidr parse(String spec) {
        int slash = spec.indexOf('/');
        String host = slash < 0 ? spec : spec.substring(0, slash);
        try {
            byte[] network = InetAddress.getByName(host).getAddress();
            int bits = slash < 0 ? network.length * 8 : Integer.parseInt(spec.substring(slash + 1));
            if (bits < 0 || bits > network.length * 8) {
                throw new IllegalArgumentException("Invalid prefix length in CIDR '" + spec + "'");
            }
            return new Cidr(network, bits);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid CIDR '" + spec + "'", e);
        }
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainder = prefixBits % 8;
        if (remainder == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainder);
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
