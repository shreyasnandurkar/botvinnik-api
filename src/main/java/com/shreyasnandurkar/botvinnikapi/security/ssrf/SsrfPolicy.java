package com.shreyasnandurkar.botvinnikapi.security.ssrf;

import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * §11 blocklist, checked against the *resolved* IP. The explicit allowlist wins
 * first — a LAN gateway's whole job is talking to RFC1918 addresses.
 */
@Component
public class SsrfPolicy {

    private static final List<Cidr> BLOCKED = List.of(
            Cidr.parse("0.0.0.0/8"),
            Cidr.parse("10.0.0.0/8"),
            Cidr.parse("100.64.0.0/10"),
            Cidr.parse("127.0.0.0/8"),
            Cidr.parse("169.254.0.0/16"),
            Cidr.parse("172.16.0.0/12"),
            Cidr.parse("192.168.0.0/16"),
            Cidr.parse("::1/128"),
            Cidr.parse("fc00::/7"),
            Cidr.parse("fe80::/10"));

    private final List<Cidr> allowed;

    public SsrfPolicy(GatewayProperties properties) {
        this.allowed = properties.security().allowedCidrs().stream().map(Cidr::parse).toList();
    }

    public void validate(InetAddress address) {
        InetAddress effective = unmapIpv4(address);
        for (Cidr cidr : allowed) {
            if (cidr.contains(effective)) {
                return;
            }
        }
        for (Cidr cidr : BLOCKED) {
            if (cidr.contains(effective)) {
                throw new SsrfBlockedException(effective.getHostAddress());
            }
        }
    }

    /** ::ffff:169.254.169.254 must be judged as its IPv4 self, or the mapping bypasses every v4 rule. */
    private static InetAddress unmapIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        if (bytes[10] != (byte) 0xFF || bytes[11] != (byte) 0xFF) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException e) {
            return address;
        }
    }
}
