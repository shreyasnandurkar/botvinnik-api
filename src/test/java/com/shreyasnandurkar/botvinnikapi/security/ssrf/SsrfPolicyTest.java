package com.shreyasnandurkar.botvinnikapi.security.ssrf;

import com.shreyasnandurkar.botvinnikapi.config.GatewayProperties;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfPolicyTest {

    private static SsrfPolicy policy(String... allowedCidrs) {
        return new SsrfPolicy(new GatewayProperties(null,
                new GatewayProperties.SecurityProps(false, null, List.of(allowedCidrs)), null, null));
    }

    private static InetAddress ip(String address) throws Exception {
        return InetAddress.getByName(address);
    }

    @Test
    void blocksTheRangesThatMatter() throws Exception {
        SsrfPolicy strict = policy();
        for (String blocked : List.of("169.254.169.254", "127.0.0.1", "10.1.2.3", "172.16.9.9",
                "192.168.1.15", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1")) {
            InetAddress address = ip(blocked);
            assertThatThrownBy(() -> strict.validate(address))
                    .as(blocked)
                    .isInstanceOf(SsrfBlockedException.class);
        }
    }

    @Test
    void publicAddressesPass() throws Exception {
        InetAddress address = ip("93.184.216.34");
        assertThatCode(() -> policy().validate(address)).doesNotThrowAnyException();
    }

    @Test
    void allowlistWinsOverBlocklist() throws Exception {
        SsrfPolicy lan = policy("192.168.0.0/16", "127.0.0.0/8");
        lan.validate(ip("192.168.1.15"));
        lan.validate(ip("127.0.0.1"));
        InetAddress metadata = ip("169.254.169.254");
        assertThatThrownBy(() -> lan.validate(metadata)).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void ipv4MappedIpv6CannotSmuggleTheMetadataEndpoint() throws Exception {
        InetAddress mapped = InetAddress.getByAddress(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF,
                (byte) 169, (byte) 254, (byte) 169, (byte) 254});
        assertThatThrownBy(() -> policy().validate(mapped)).isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void resolverGroupFailsResolutionOfBlockedAddresses() throws Exception {
        GuardedResolverGroup group = new GuardedResolverGroup(policy());
        AddressResolver<InetSocketAddress> resolver = group.getResolver(GlobalEventExecutor.INSTANCE);
        Future<InetSocketAddress> future = resolver
                .resolve(InetSocketAddress.createUnresolved("localhost", 80));
        future.await(5, TimeUnit.SECONDS);
        assertThat(future.isSuccess()).isFalse();
        assertThat(future.cause()).isInstanceOf(SsrfBlockedException.class);
        group.close();
    }

    @Test
    void resolverGroupPassesAllowedAddressesThrough() throws Exception {
        GuardedResolverGroup group = new GuardedResolverGroup(policy("127.0.0.0/8", "::1/128"));
        AddressResolver<InetSocketAddress> resolver = group.getResolver(GlobalEventExecutor.INSTANCE);
        Future<InetSocketAddress> future = resolver
                .resolve(InetSocketAddress.createUnresolved("localhost", 80));
        future.await(5, TimeUnit.SECONDS);
        assertThat(future.isSuccess()).isTrue();
        assertThat(future.getNow().getAddress().isLoopbackAddress()).isTrue();
        group.close();
    }
}
