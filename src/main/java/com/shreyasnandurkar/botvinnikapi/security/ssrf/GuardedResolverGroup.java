package com.shreyasnandurkar.botvinnikapi.security.ssrf;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/**
 * DNS-rebinding defense (§11): the IP is validated at the moment of connection,
 * on the exact resolution WebClient will dial — a parse-time check re-resolves
 * and can be handed a different answer.
 */
public class GuardedResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final AddressResolverGroup<InetSocketAddress> delegate;
    private final SsrfPolicy policy;

    public GuardedResolverGroup(SsrfPolicy policy) {
        this(DefaultAddressResolverGroup.INSTANCE, policy);
    }

    GuardedResolverGroup(AddressResolverGroup<InetSocketAddress> delegate, SsrfPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        AddressResolver<InetSocketAddress> inner = delegate.getResolver(executor);
        return new AddressResolver<>() {

            @Override
            public boolean isSupported(SocketAddress address) {
                return inner.isSupported(address);
            }

            @Override
            public boolean isResolved(SocketAddress address) {
                return inner.isResolved(address);
            }

            @Override
            public Future<InetSocketAddress> resolve(SocketAddress address) {
                return validated(executor.newPromise(), inner.resolve(address));
            }

            @Override
            public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
                validated(promise, inner.resolve(address));
                return promise;
            }

            @Override
            public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) {
                return validatedAll(executor.newPromise(), inner.resolveAll(address));
            }

            @Override
            public Future<List<InetSocketAddress>> resolveAll(SocketAddress address,
                                                              Promise<List<InetSocketAddress>> promise) {
                validatedAll(promise, inner.resolveAll(address));
                return promise;
            }

            @Override
            public void close() {
                inner.close();
            }
        };
    }

    private Future<InetSocketAddress> validated(Promise<InetSocketAddress> promise,
                                                Future<InetSocketAddress> resolution) {
        resolution.addListener(f -> {
            if (!f.isSuccess()) {
                promise.tryFailure(f.cause());
                return;
            }
            InetSocketAddress resolved = (InetSocketAddress) f.getNow();
            try {
                policy.validate(resolved.getAddress());
                promise.trySuccess(resolved);
            } catch (Exception e) {
                promise.tryFailure(e);
            }
        });
        return promise;
    }

    private Future<List<InetSocketAddress>> validatedAll(Promise<List<InetSocketAddress>> promise,
                                                         Future<List<InetSocketAddress>> resolution) {
        resolution.addListener(f -> {
            if (!f.isSuccess()) {
                promise.tryFailure(f.cause());
                return;
            }
            @SuppressWarnings("unchecked")
            List<InetSocketAddress> resolved = (List<InetSocketAddress>) f.getNow();
            try {
                resolved.forEach(a -> policy.validate(a.getAddress()));
                promise.trySuccess(resolved);
            } catch (Exception e) {
                promise.tryFailure(e);
            }
        });
        return promise;
    }
}
