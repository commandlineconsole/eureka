/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka2.client.registry;

import java.util.concurrent.TimeUnit;

import com.netflix.eureka2.client.channel.ClientChannelFactory;
import com.netflix.eureka2.client.channel.ClientInterestChannel;
import com.netflix.eureka2.client.channel.RetryableInterestChannel;
import com.netflix.eureka2.client.registry.swap.ThresholdStrategy;
import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.interests.ChangeNotification.Kind;
import com.netflix.eureka2.interests.Interest;
import com.netflix.eureka2.interests.Interests;
import com.netflix.eureka2.registry.InstanceInfo;
import com.netflix.eureka2.testkit.data.builder.SampleInstanceInfo;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import rx.Observable;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;
import rx.subjects.ReplaySubject;

import static com.netflix.eureka2.client.metric.EurekaClientMetricFactory.*;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Tomasz Bak
 */
public class EurekaClientRegistryProxyTest {

    private final TestScheduler testScheduler = Schedulers.test();

    private static final InstanceInfo INFO = SampleInstanceInfo.DiscoveryServer.build();
    private static final Interest<InstanceInfo> INTEREST = Interests.forFullRegistry();

    private final ClientInterestChannel mockInterestChannel = mock(ClientInterestChannel.class);
    private final ClientChannelFactory mockClientChannelFactory = mock(ClientChannelFactory.class);
    private final EurekaClientRegistry<InstanceInfo> internalRegistry = new EurekaClientRegistryImpl(clientMetrics().getRegistryMetrics());

    private RetryableInterestChannel retryableInterestChannel;
    private EurekaClientRegistryProxy registryProxy;

    @Before
    public void setUp() throws Exception {
        ReplaySubject<Void> channelLifecycle = ReplaySubject.create();
        when(mockInterestChannel.asLifecycleObservable()).thenReturn(channelLifecycle);

        when(mockClientChannelFactory.newInterestChannel(org.mockito.Matchers.any(EurekaClientRegistryImpl.class))).thenReturn(mockInterestChannel);
        retryableInterestChannel = spy(new RetryableInterestChannel
                (mockClientChannelFactory, ThresholdStrategy.factoryFor(testScheduler), clientMetrics(), 10, testScheduler));

        EurekaClientRegistry<InstanceInfo> internalRegistry = captureInternalRegistryFromChannel();
        when(mockInterestChannel.associatedRegistry()).thenReturn(internalRegistry);

        registryProxy = new EurekaClientRegistryProxy(retryableInterestChannel, testScheduler);
        internalRegistry.register(INFO).subscribe();
    }

    @Test
    public void testDelegatesForInterestToInternalRegistry() throws Exception {
        when(mockInterestChannel.appendInterest(INTEREST)).thenReturn(Observable.<Void>empty());

        // forInterest
        ChangeNotification<InstanceInfo> notification =
                registryProxy.forInterest(INTEREST).take(1).timeout(1, TimeUnit.SECONDS).toBlocking().first();

        // Ensure InterestChannelInvoker runs the scheduled task
        testScheduler.triggerActions();

        verify(retryableInterestChannel, times(1)).appendInterest(INTEREST);
        assertThat(notification.getKind(), is(equalTo(Kind.Add)));
        assertThat(notification.getData(), is(equalTo(INFO)));
    }

    @Test
    public void testDelegatesForSnapshotToInternalRegistry() throws Exception {
        // forSnapshot
        InstanceInfo instanceInfo =
                registryProxy.forSnapshot(INTEREST).take(1).timeout(1, TimeUnit.SECONDS).toBlocking().first();

        assertThat(instanceInfo, is(equalTo(INFO)));
    }

    @Test
    public void testShutdownClosesChannel() throws Exception {
        registryProxy.shutdown();
        verify(retryableInterestChannel, times(1)).close();
    }

    protected EurekaClientRegistry<InstanceInfo> captureInternalRegistryFromChannel() {
        ArgumentCaptor<EurekaClientRegistry> argCaptor = ArgumentCaptor.forClass(EurekaClientRegistry.class);
        verify(mockClientChannelFactory, atLeastOnce()).newInterestChannel(argCaptor.capture());

        return argCaptor.getValue();
    }
}