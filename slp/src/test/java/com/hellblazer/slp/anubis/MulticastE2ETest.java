package com.hellblazer.slp.anubis;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.hellblazer.jackal.testUtil.multicast.MulticastControllerConfig;
import com.hellblazer.jackal.testUtil.multicast.MulticastNodeCfg;

public class MulticastE2ETest extends EndToEndTest {

    @Configuration
    static class member extends multicastSlpConfig {
        private static final AtomicInteger id = new AtomicInteger(-1);

        @Override
        @Bean
        public int node() {
            return id.incrementAndGet();
        }
    }

    @Import({ SlpConfig.class })
    @Configuration
    static abstract class multicastSlpConfig extends MulticastNodeCfg {

    }

    @Override
    protected Class<?>[] getConfigs() {
        return new Class<?>[] { member.class, member.class, member.class,
                member.class, member.class, member.class, member.class,
                member.class, member.class, member.class };
    }

    @Override
    protected Class<?> getControllerConfig() {
        return MulticastControllerConfig.class;
    }

    @Override
    protected Logger getLogger() {
        return Logger.getLogger(MulticastE2ETest.class.getCanonicalName());
    }

}
